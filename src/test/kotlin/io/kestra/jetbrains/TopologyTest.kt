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
        var elements: String? = null

        override fun render(elements: String) {
            this.elements = elements
            calls += "render"
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

        val elements = recorder.elements!!
        assertTrue(elements, elements.contains("data:image/svg+xml;base64,PHN2Zy8+"))
        assertEquals(FLOW, kestra.requests.single { it.path == "/flows/graph" }.body)

        // A refresh without an edit is free.
        service.refresh()
        assertEquals(1, kestra.requests.count { it.path == "/flows/graph" })

        service.runStarted("company.team.hello")
        service.taskState("company.team.hello", "greet", "FAILED")
        service.taskState("company.team.other", "greet", "SUCCESS")
        waitFor { recorder.calls.size >= 3 }
        assertEquals(listOf("render", "reset", "state greet failed"), recorder.calls)

        service.reveal("greet")
        val line = myFixture.editor.document.getLineNumber(myFixture.editor.caretModel.offset)
        assertEquals("  - id: greet", myFixture.editor.document.text.lines()[line])
    }

    fun testKestra1UsesTheIconIndex() {
        kestra.json("GET", "/configs") { """{"version":"1.3.47"}""" }
        KestraSettings.fireChanged()
        kestra.json("GET", "/plugins/icons") { """{"io.kestra.plugin.core.log.Log":{"icon":"QQ=="},"other.Type":{"icon":"Qg=="}}""" }
        val icons = PluginIcons.instance.icons(listOf("io.kestra.plugin.core.log.Log", "io.kestra.plugin.core.flow.Parallel"))
        assertEquals(mapOf("io.kestra.plugin.core.log.Log" to "data:image/svg+xml;base64,QQ=="), icons)
        assertTrue(kestra.requests.none { it.path.startsWith("/plugins/icons/") })
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
