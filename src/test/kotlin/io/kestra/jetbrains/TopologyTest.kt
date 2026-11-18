package io.kestra.jetbrains

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.PlatformTestUtil
import io.kestra.jetbrains.settings.KestraSettings
import io.kestra.jetbrains.topology.FlowGraph
import io.kestra.jetbrains.topology.KestraTopologyService
import io.kestra.jetbrains.topology.PluginIcons
import io.kestra.jetbrains.topology.TopologyPage
import io.kestra.jetbrains.topology.TopologyRenderer

class TopologyTest : KestraTestCase() {
    // Answered by Kestra 2.0.10 for a flow with a task, a Parallel of two Logs and a Schedule trigger.
    private val graph = javaClass.getResource("/graph.json")!!.readText()

    private class Recorder : TopologyRenderer {
        val calls = ArrayList<String>()
        // Every drawing, in order: the test thread dispatches events, so a later one can land before an assert.
        val renders = ArrayList<String>()
        val iconUpdates = ArrayList<String>()

        override fun render(elements: String) {
            renders += elements
            calls += "render"
        }

        override fun updateIcons(elements: String) {
            iconUpdates += elements
            calls += "icons"
        }

        override fun notice(text: String) {
            calls += "notice $text"
        }

        override fun setTaskState(taskId: String, bucket: String?) {
            calls += "state $taskId $bucket"
        }

        override fun resetStates() {
            calls += "reset"
        }

        override fun setTheme(theme: String) = Unit
    }

    override fun setUp() {
        super.setUp()
        PluginIcons.instance.clear()
    }

    fun testGraphBecomesNestedElements() {
        val elements = FlowGraph.elements(JsonParser.parseString(graph), mapOf("io.kestra.plugin.core.log.Log" to "data:log"))!!
        val byId = elements.json.map { it.asJsonObject }.associateBy { it.getAsJsonObject("data").get("id").asString }
        fun data(id: String): JsonObject = byId.getValue(id).getAsJsonObject("data")

        assertEquals("par", data("cluster_root.par").get("label").asString)
        assertEquals("cluster", byId.getValue("cluster_root.par").get("classes").asString)
        assertEquals("cluster_root.par", data("root.par.a").get("parent").asString)
        assertEquals("data:log", data("root.par.a").get("icon").asString)
        assertEquals("none", data("root.greet").get("icon").asString)
        assertEquals("Triggers", data("cluster_root.Triggers").get("label").asString)
        assertEquals("cluster triggers", byId.getValue("cluster_root.Triggers").get("classes").asString)
        assertEquals("daily", data("root.Triggers.daily").get("taskId").asString)
        assertEquals("boundary", byId.values.first { it.getAsJsonObject("data").get("id").asString.startsWith("root.root-") }.get("classes").asString)
        assertTrue(byId.containsKey("root.greet->root.par.root-2z6mh1xVR5wijh4ZKONAV4"))
        assertContainsElements(elements.pluginTypes, "io.kestra.plugin.core.log.Log", "io.kestra.plugin.core.trigger.Schedule")
    }

    fun testPreviewFollowsTheEditorAndTheRun() {
        kestra.json("POST", "/flows/graph") { graph }
        kestra.json("GET", "/plugins/icons/io.kestra.plugin.core.log.Log") { """{"icon":{"name":"Log","icon":"PHN2Zy8+"}}""" }
        myFixture.configureByText("flow.yml", FLOW)
        val (service, recorder) = attached()

        // Drawn at once without the icons not fetched yet, then the icons follow without a new layout.
        assertFalse(recorder.renders.first().contains("data:image/svg+xml;base64,PHN2Zy8+"))
        waitFor { recorder.calls.size >= 2 }
        assertEquals(listOf("render", "icons"), recorder.calls)
        assertTrue(recorder.iconUpdates.single(), recorder.iconUpdates.single().contains("data:image/svg+xml;base64,PHN2Zy8+"))
        assertEquals(FLOW, kestra.requests.single { it.path == "/flows/graph" }.body)

        // A refresh without an edit is free.
        service.refresh()
        assertEquals(1, kestra.requests.count { it.path == "/flows/graph" })

        service.runStarted("company.team.hello")
        service.taskState("company.team.hello", "greet", "FAILED")
        service.taskState("company.team.other", "greet", "SUCCESS")
        waitFor { recorder.calls.size >= 4 }
        assertEquals(listOf("render", "icons", "reset", "state greet failed"), recorder.calls)

        service.reveal("greet")
        val line = myFixture.editor.document.getLineNumber(myFixture.editor.caretModel.offset)
        assertEquals("  - id: greet", myFixture.editor.document.text.lines()[line])
    }

    // The graph of a flow still waiting for its icons must not be drawn over what the editor shows now.
    fun testIconsOfAFlowAreNotDrawnOverTheNoticeOfTheNextFile() {
        val iconRequested = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        kestra.json("POST", "/flows/graph") { graph }
        kestra.handle("GET", "/plugins/icons/io.kestra.plugin.core.log.Log") { exchange, _ ->
            iconRequested.countDown()
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            FakeKestra.respond(exchange, 200, """{"icon":{"name":"Log","icon":"PHN2Zy8+"}}""")
        }
        kestra.json("GET", "/plugins/icons/io.kestra.plugin.core.trigger.Schedule") { """{"icon":{"icon":"PHN2Zy8+"}}""" }
        try {
            myFixture.configureByText("flow.yml", FLOW)
            val (service, recorder) = attached()
            assertTrue(iconRequested.await(30, java.util.concurrent.TimeUnit.SECONDS))
            val flowRefresh = service.lastRefresh

            // The editor moves to a file that is not a flow while the icons are still being fetched.
            myFixture.configureByText("README.md", "# Not a flow")
            // (The tab change schedules a refresh of its own, so the notice may be shown more than once.)
            service.refresh().also { waitUntilDone(it) }
            val notice = "notice Open a flow to preview its topology."
            assertEquals(notice, recorder.calls.last())
            assertEquals(listOf("render"), recorder.calls.filter { !it.startsWith("notice") })

            release.countDown()
            // The refresh of the flow completes after its icon step, which was dropped as stale.
            waitUntilDone(flowRefresh)
            assertEquals(notice, recorder.calls.last())
            assertEquals(listOf("render"), recorder.calls.filter { !it.startsWith("notice") })
        } finally {
            release.countDown()
        }
    }

    fun testFailedIconsAreNotFetchedAgainRightAway() {
        kestra.json("GET", "/plugins/icons/io.kestra.plugin.core.log.Log", status = 503) { "{}" }
        val icons = PluginIcons.instance
        val types = listOf("io.kestra.plugin.core.log.Log")
        assertTrue(icons.hasMissing(types))
        assertTrue(icons.icons(types).isEmpty())
        assertEquals(1, kestra.requests.count { it.path == "/plugins/icons/io.kestra.plugin.core.log.Log" })

        // A debounced refresh a moment later does not ask again, and does not even start an icon stage.
        assertFalse(icons.hasMissing(types))
        assertTrue(icons.icons(types).isEmpty())
        assertEquals(1, kestra.requests.count { it.path == "/plugins/icons/io.kestra.plugin.core.log.Log" })
    }

    fun testConcurrentRefreshesShareOneIconRequest() {
        val release = java.util.concurrent.CountDownLatch(1)
        val requested = java.util.concurrent.CountDownLatch(1)
        kestra.handle("GET", "/plugins/icons/io.kestra.plugin.core.log.Log") { exchange, _ ->
            requested.countDown()
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            FakeKestra.respond(exchange, 200, """{"icon":{"icon":"QQ=="}}""")
        }
        val types = listOf("io.kestra.plugin.core.log.Log")
        val results = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        val first = Thread { results += PluginIcons.instance.icons(types) }.also { it.start() }
        try {
            assertTrue(requested.await(30, java.util.concurrent.TimeUnit.SECONDS))
            val second = Thread { results += PluginIcons.instance.icons(types) }.also { it.start() }
            // Parked on the fetch under way: it is shared, not repeated.
            val deadline = System.currentTimeMillis() + 10_000
            while (second.state != Thread.State.WAITING && System.currentTimeMillis() < deadline) Thread.onSpinWait()
            release.countDown()
            first.join(30_000)
            second.join(30_000)
        } finally {
            release.countDown()
        }
        assertEquals(2, results.size)
        assertTrue(results.all { it["io.kestra.plugin.core.log.Log"] == "data:image/svg+xml;base64,QQ==" })
        assertEquals(1, kestra.requests.count { it.path == "/plugins/icons/io.kestra.plugin.core.log.Log" })
    }

    fun testKestra1UsesTheIconIndex() {
        kestra.json("GET", "/configs") { """{"version":"1.3.47"}""" }
        KestraSettings.fireChanged()
        kestra.json("GET", "/plugins/icons") { """{"io.kestra.plugin.core.log.Log":{"icon":"QQ=="},"other.Type":{"icon":"Qg=="}}""" }
        val icons = PluginIcons.instance.icons(listOf("io.kestra.plugin.core.log.Log", "io.kestra.plugin.core.flow.Parallel"))
        assertEquals(mapOf("io.kestra.plugin.core.log.Log" to "data:image/svg+xml;base64,QQ=="), icons)
        assertTrue(kestra.requests.none { it.path.startsWith("/plugins/icons/") && it.path != "/plugins/icons/groups" })
    }

    // A server error on the 1.x index used to be dropped and an empty index kept for the life of the instance.
    fun testKestra1IconIndexIsBuiltAgainAfterAServerError() {
        kestra.json("GET", "/configs") { """{"version":"1.3.47"}""" }
        KestraSettings.fireChanged()
        val healthy = java.util.concurrent.atomic.AtomicBoolean(false)
        kestra.handle("GET", "/plugins/icons") { exchange, _ ->
            if (healthy.get()) FakeKestra.respond(exchange, 200, """{"a.A":{"icon":"QQ=="},"b.B":{"icon":"Qg=="}}""")
            else FakeKestra.respond(exchange, 503, "{}")
        }
        val icons = PluginIcons.instance
        assertTrue(icons.icons(listOf("a.A")).isEmpty())

        // Another type, so the cooldown of the first one does not hide the result.
        healthy.set(true)
        assertEquals(mapOf("b.B" to "data:image/svg+xml;base64,Qg=="), icons.icons(listOf("b.B")))
    }

    // A fetch of the old instance that ends after the settings changed must not leave its index behind.
    fun testKestra1IconIndexOfAPreviousInstanceIsNotKept() {
        kestra.json("GET", "/configs") { """{"version":"1.3.47"}""" }
        KestraSettings.fireChanged()
        val requested = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        kestra.handle("GET", "/plugins/icons") { exchange, _ ->
            requested.countDown()
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            FakeKestra.respond(exchange, 200, """{"a.A":{"icon":"QQ=="},"b.B":{"icon":"Qg=="}}""")
        }
        // Captures the types: a lambda capturing nothing compiles to a no-argument method named test…, which JUnit 3 takes for a test.
        val types = listOf("a.A")
        val first = Thread { PluginIcons.instance.icons(types) }.also { it.start() }
        try {
            assertTrue(requested.await(30, java.util.concurrent.TimeUnit.SECONDS))
            KestraSettings.fireChanged()
            release.countDown()
            first.join(30_000)
        } finally {
            release.countDown()
        }
        assertEquals(1, kestra.requests.count { it.path == "/plugins/icons" })

        assertEquals(mapOf("b.B" to "data:image/svg+xml;base64,Qg=="), PluginIcons.instance.icons(listOf("b.B")))
        assertEquals(2, kestra.requests.count { it.path == "/plugins/icons" })
    }

    fun testNoticesWhenNotAFlowOrNotSignedIn() {
        kestra.json("POST", "/flows/graph", status = 401) { "{}" }
        myFixture.configureByText("compose.yml", "services:\n  web: {}\n")
        val (service, recorder) = attached()
        assertEquals(listOf("notice Open a flow to preview its topology."), recorder.calls)

        myFixture.configureByText("flow.yml", FLOW)
        service.refresh()
        waitFor { recorder.calls.size >= 2 }
        assertTrue(recorder.calls.toString(), recorder.calls.last().contains("requires authentication"))
    }

    fun testPageBundlesTheLibrariesAndTheme() {
        val html = TopologyPage.html("window.kestraPost = function () {};")
        assertTrue(html.contains("Cytoscape Consortium"))
        assertTrue(html.contains("cytoscapeDagre"))
        assertTrue(html.contains("window.kestraTopology"))
        assertFalse(html.contains("/*TOPOLOGY*/"))
        assertTrue(TopologyPage.theme().get("failed").asString.matches(Regex("#[0-9a-f]{6}")))
    }

    private fun waitUntilDone(future: java.util.concurrent.CompletableFuture<Unit>) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        assertTrue("refresh did not finish", future.isDone)
        future.getNow(Unit)
    }

    private fun attached(): Pair<KestraTopologyService, Recorder> {
        val service = KestraTopologyService.getInstance(project)
        val recorder = Recorder()
        com.intellij.openapi.util.Disposer.register(testRootDisposable) { service.detach(recorder) }
        service.setVisible(true)
        service.attach(recorder)
        waitFor { recorder.calls.isNotEmpty() }
        return service to recorder
    }

    // Lets the background graph request finish and its result land on the EDT.
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
