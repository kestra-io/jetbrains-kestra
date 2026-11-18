package io.kestra.jetbrains

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import io.kestra.jetbrains.namespace.KestraNamespacesPanel
import io.kestra.jetbrains.settings.KestraSettings
import io.kestra.jetbrains.vfs.KestraFileSystem
import io.kestra.jetbrains.vfs.KestraVirtualFile
import io.kestra.jetbrains.vfs.NamespaceSaver
import io.kestra.jetbrains.vfs.UnsavedCopies
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.tree.DefaultMutableTreeNode

class NamespaceBrowserTest : KestraTestCase() {
    private val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val instance get() = KestraFileSystem.currentInstance()!!

    // Holds the reads of namespace files until released, so a test can act while one is in flight.
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    @Volatile private var readGate: Gate? = null

    // Holds the upload of a file whose name contains "slowwrite", so a save stays in flight.
    @Volatile private var writeGate: CountDownLatch? = null

    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        stored["/README.md"] = "# Hello"
        stored["/scripts/run.py"] = "print(1)"
        stored["/.git/config"] = "x"
        // Notifications raised for a project go to its bus, the others to the application's.
        val recorder = object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, recorder)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, recorder)
        // A tiny namespace store: list, read, write, move and delete over the map above.
        kestra.handle("GET", "/namespaces/company.team/files/directory") { exchange, request ->
            val dir = query(request, "path").trimEnd('/')
            val names = stored.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
            val entries = names.map { it.substringBefore('/') to it.contains('/') }.distinct().joinToString(",") { (name, isDir) ->
                """{"type":"${if (isDir) "Directory" else "File"}","size":1,"fileName":"$name","lastModifiedTime":1}"""
            }
            FakeKestra.respond(exchange, 200, "[$entries]")
        }
        kestra.handle("GET", "/namespaces/company.team/files") { exchange, request ->
            readGate?.let {
                it.entered.countDown()
                it.release.await(30, TimeUnit.SECONDS)
            }
            val content = stored[query(request, "path")]
            FakeKestra.respond(exchange, if (content == null) 404 else 200, content ?: """{"detail":"not found"}""")
        }
        kestra.handle("POST", "/namespaces/company.team/files") { exchange, request ->
            val path = query(request, "path")
            if (path.contains("slowwrite")) writeGate?.await(30, TimeUnit.SECONDS)
            if (path.contains("locked")) return@handle FakeKestra.respond(exchange, 403, """{"message":"Missing NAMESPACE UPDATE permission"}""")
            stored[path] = request.body.substringAfter("\r\n\r\n").substringBefore("\r\n--")
            FakeKestra.respond(exchange, 200, "")
        }
        kestra.handle("PUT", "/namespaces/company.team/files") { exchange, request ->
            val from = java.net.URLDecoder.decode(query(request, "from"), Charsets.UTF_8)
            val to = java.net.URLDecoder.decode(query(request, "to"), Charsets.UTF_8)
            stored.keys.filter { it == from || it.startsWith("$from/") }.forEach { stored[to + it.removePrefix(from)] = stored.remove(it)!! }
            FakeKestra.respond(exchange, 200, "")
        }
        kestra.handle("DELETE", "/namespaces/company.team/files") { exchange, request ->
            val path = query(request, "path")
            stored.keys.removeIf { it == path || it.startsWith("$path/") }
            FakeKestra.respond(exchange, 200, "")
        }
    }

    private fun query(request: FakeKestra.Request, name: String): String =
        java.net.URLDecoder.decode(request.path.substringAfter("$name=").substringBefore('&'), Charsets.UTF_8)

    fun testUrlsResolveAndBelongToTheirInstance() {
        val file = VirtualFileManager.getInstance().findFileByUrl("kestra://$instance/company.team/scripts/run.py") as KestraVirtualFile
        assertEquals("/scripts/run.py", file.relativePath)
        assertEquals("print(1)", String(file.contentsToByteArray()))
        assertEquals("scripts", file.parent!!.name)
        assertEquals("company.team", file.parent!!.parent!!.name)

        val other = VirtualFileManager.getInstance().findFileByUrl("kestra://elsewhere:8080/company.team/README.md")!!
        val error = runCatching { other.contentsToByteArray() }.exceptionOrNull()
        assertTrue(error.toString(), error is IOException && error.message!!.contains("belongs to elsewhere:8080"))
    }

    fun testBrowseCreateRenameDeleteAndSave() {
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { rows(panel).size >= 4 }
        assertEquals(listOf("company.team", "_flows", "scripts", "README.md"), rows(panel))

        // Saving a file uploads it in the background.
        val readme = KestraFileSystem.instance.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!
        WriteAction.run<RuntimeException> { document.setText("# Updated") }
        FileDocumentManager.getInstance().saveDocument(document)
        waitFor { stored["/README.md"] == "# Updated" }
        assertEquals("# Updated", stored["/README.md"])

        select(panel, "README.md")
        TestDialogManager.setTestInputDialog(TestInputDialog { "notes.md" })
        panel.create(directory = false)
        waitFor { "notes.md" in rows(panel) }
        assertEquals("", stored["/notes.md"])

        select(panel, "README.md")
        TestDialogManager.setTestInputDialog(TestInputDialog { "INTRO.md" })
        panel.rename()
        waitFor { "INTRO.md" in rows(panel) }
        assertEquals("# Updated", stored["/INTRO.md"])
        assertEquals("/INTRO.md", readme.relativePath)
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(readme))

        select(panel, "INTRO.md")
        TestDialogManager.setTestDialog(TestDialog.OK)
        panel.delete()
        waitFor { "INTRO.md" !in rows(panel) }
        assertNull(stored["/INTRO.md"])
        assertFalse(readme.isValid)
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(readme))
    }

    fun testRefusedSaveIsReportedWithRetry() {
        stored["/locked.txt"] = "x"
        val (locked, notification) = refuseSave("/locked.txt")
        assertTrue(notification.content, notification.content.contains("Missing NAMESPACE UPDATE permission"))
        assertEquals(listOf("Retry", "Discard local changes"), notification.actions.map { it.templateText })
        assertEquals("x", stored["/locked.txt"])

        // The refused edit is not replaced by the instance's version when the folder is listed again.
        assertTrue(NamespaceSaver.instance.hasUnsyncedChanges(locked))
        // Quitting the IDE asks before dropping it.
        assertEquals(listOf(locked), NamespaceSaver.instance.unsyncedFiles())
        KestraFileSystem.instance.list(KestraFileSystem.instance.root(instance, "company.team"))
        assertEquals("y", String(locked.contentsToByteArray()))
    }

    // Types into a file the instance refuses, saves it, and returns the file and the failure it was reported with.
    private fun refuseSave(path: String): Pair<KestraVirtualFile, Notification> {
        stored.putIfAbsent(path, "x")
        val file = KestraFileSystem.instance.findFileByPath("$instance/company.team$path") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(file, true)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteAction.run<RuntimeException> { document.setText("y") }
        FileDocumentManager.getInstance().saveDocument(document)
        waitFor { notifications.any { it.title == "Not saved to Kestra" } }
        return file to notifications.single { it.title == "Not saved to Kestra" }
    }

    // The failure notification's way out: the file goes back to what the instance has.
    fun testDiscardingARefusedSaveBringsBackTheInstancesFile() {
        val (locked, notification) = refuseSave("/locked.txt")
        val document = FileDocumentManager.getInstance().getDocument(locked)!!
        assertTrue(NamespaceSaver.instance.hasUnsyncedChanges(locked))

        val discard = notification.actions.single { it.templateText == "Discard local changes" }
        (discard as com.intellij.notification.NotificationAction).actionPerformed(TestActionEvent.createTestEvent(discard), notification)
        waitFor { document.text == "x" }

        assertEquals("x", document.text)
        assertFalse(NamespaceSaver.instance.hasUnsyncedChanges(locked))
        assertEquals(emptyList<KestraVirtualFile>(), NamespaceSaver.instance.unsyncedFiles())
        assertEquals("x", stored["/locked.txt"])
    }

    // The file is gone from the instance, its refused edit is not: only the user drops it.
    fun testRefusedEditSurvivesTheFileLeavingTheInstance() {
        val fs = KestraFileSystem.instance
        val root = fs.root(instance, "company.team")
        val (locked, _) = refuseSave("/locked.txt")
        assertTrue(fs.list(root).contains(locked))

        stored.remove("/locked.txt")
        val after = fs.list(root)

        assertTrue(after.toString(), after.contains(locked))
        assertTrue(locked.isValid)
        assertTrue(NamespaceSaver.instance.hasUnsyncedChanges(locked))
        assertEquals("y", String(locked.contentsToByteArray()))
        // A file that really left, with nothing to lose, is forgotten.
        stored.remove("/README.md")
        assertFalse(fs.list(root).any { it.name == "README.md" })
    }

    // Quit and Restart IDE reach the application listener through different methods.
    private class Prompt(var quit: Boolean, private val flush: Boolean = true) : NamespaceSaver.ExitPrompt {
        val waits = CopyOnWriteArrayList<Long>()
        val asked = CopyOnWriteArrayList<List<KestraVirtualFile>>()

        override fun waitForSaves(timeoutMillis: Long) {
            waits += timeoutMillis
            if (flush) NamespaceSaver.instance.flush(timeoutMillis)
        }

        override fun confirmQuit(files: List<KestraVirtualFile>): Boolean {
            asked += files
            return quit
        }
    }

    fun testExitSavesWhatWasTypedLastBeforeCheckingForUnsavedFiles() {
        val prompt = Prompt(quit = false, flush = true)
        NamespaceSaver.instance.prompt = prompt
        val readme = KestraFileSystem.instance.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!
        // Typed, not saved: the platform saves it only when it closes the projects, after this listener ran.
        WriteAction.run<RuntimeException> { document.setText("# typed right before quitting") }

        assertTrue(NamespaceSaver.instance.exitListener.canExitApplication())

        assertEquals("# typed right before quitting", stored["/README.md"])
        assertEquals(emptyList<List<KestraVirtualFile>>(), prompt.asked)
        assertEquals(1, prompt.waits.size)
    }

    fun testRestartAsksLikeQuitAndQuitAnywayKeepsACopy() {
        val saver = NamespaceSaver.instance
        val prompt = Prompt(quit = false, flush = false).also { saver.prompt = it }
        val (locked, _) = refuseSave("/locked.txt")

        assertFalse(saver.exitListener.canRestartApplication())
        assertFalse(saver.exitListener.canExitApplication())
        assertEquals(listOf(listOf(locked), listOf(locked)), prompt.asked.toList())

        // A second file whose save is still in flight when the user chooses Quit Anyway.
        val release = CountDownLatch(1).also { writeGate = it }
        try {
            stored["/slowwrite.txt"] = "a"
            val slow = KestraFileSystem.instance.findFileByPath("$instance/company.team/slowwrite.txt") as KestraVirtualFile
            FileEditorManager.getInstance(project).openFile(slow, true)
            val document = FileDocumentManager.getInstance().getDocument(slow)!!
            WriteAction.run<RuntimeException> { document.setText("b") }

            prompt.quit = true
            assertTrue(saver.exitListener.canRestartApplication())
            assertEquals(setOf(locked, slow), prompt.asked.last().toSet())

            // Nothing waits after Quit Anyway, although a save is still in flight: the exit hook returns at once.
            CompletableFuture.runAsync { saver.finishOnExit() }.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            prompt.quit = false
            saver.exitListener.canExitApplication()
        }
        assertEquals(setOf("y", "b"), keptCopies().toSet())
    }

    // An exit that skipped the prompt still leaves a copy of what the instance did not take.
    fun testExitWithoutThePromptKeepsACopyOfWhatWasRefused() {
        val saver = NamespaceSaver.instance
        saver.prompt = Prompt(quit = false)
        refuseSave("/locked.txt")

        saver.finishOnExit()

        assertEquals(listOf("y"), keptCopies())
        // Reported once at the next start, then left quiet.
        notifications.clear()
        UnsavedCopies.report(project)
        assertEquals(1, notifications.count { it.content.contains("were not saved to the instance") })
        UnsavedCopies.report(project)
        assertEquals(1, notifications.count { it.content.contains("were not saved to the instance") })
    }

    private fun keptCopies(): List<String> = java.nio.file.Files.walk(UnsavedCopies.directory).use { paths ->
        paths.filter { java.nio.file.Files.isRegularFile(it) }.map { java.nio.file.Files.readString(it) }.toList()
    }

    fun testNewFileDoesNotOverwriteAFileOfAFolderNeverExpanded() {
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { "scripts" in rows(panel) }
        select(panel, "scripts")
        TestDialogManager.setTestInputDialog(TestInputDialog { "run.py" })
        // Completes once the refusal was reported: nothing is left in flight before the asserts.
        waitUntilDone(panel.create(directory = false))
        assertTrue(notifications.any { it.content.contains("already exists") })

        assertEquals("print(1)", stored["/scripts/run.py"])
        assertTrue(kestra.requests.none { it.method == "POST" && it.path.startsWith("/namespaces/company.team/files") })
    }

    fun testInstanceIsTheWholeApiBaseAndTheTenant() {
        fun instanceOf(url: String, tenant: String = ""): String {
            KestraSettings.instance.update(url, tenant, "INFO")
            return KestraFileSystem.currentInstance()!!
        }
        val prod = instanceOf("https://x.example/prod")
        val staging = instanceOf("https://x.example/staging")
        assertFalse(prod == staging)
        assertFalse(prod.contains('/'))
        assertEquals("https://x.example/prod/api/v1", KestraFileSystem.describe(prod))
        assertEquals("https://x.example/prod/api/v1 (tenant acme)", KestraFileSystem.describe(instanceOf("https://x.example/prod", "acme")))

        // A URL built from it resolves to the same folder.
        val root = KestraFileSystem.instance.root(prod, "company.team")
        assertSame(root, VirtualFileManager.getInstance().findFileByUrl("kestra://$prod/company.team"))
        assertEquals("company.team on https://x.example/prod/api/v1", root.location)
    }

    fun testFileReadOnTheUiThreadDoesNotWaitForASlowInstance() {
        val gate = Gate().also { readGate = it }
        try {
            val file = VirtualFileManager.getInstance().findFileByUrl("kestra://$instance/company.team/slow.txt") as KestraVirtualFile
            val start = System.currentTimeMillis()
            val error = runCatching { file.contentsToByteArray() }.exceptionOrNull()
            assertTrue(error.toString(), error is IOException && error.message!!.contains("still loading"))
            assertTrue(System.currentTimeMillis() - start < 10_000)
        } finally {
            gate.release.countDown()
        }
    }

    // A tab restored at startup asks for its content before a slow instance answered: the platform turns the
    // error into a blank document and never asks again, so the file reloads it when the content lands.
    fun testBlankDocumentOfASlowFileIsReloadedWhenItsContentLands() {
        stored["/slow.txt"] = "the real content"
        val gate = Gate().also { readGate = it }
        try {
            val file = VirtualFileManager.getInstance().findFileByUrl("kestra://$instance/company.team/slow.txt") as KestraVirtualFile
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            assertEquals("", document.text)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            // Nothing can be typed into the blank text meanwhile.
            assertFalse(document.isWritable)
            assertTrue(file.unread)

            gate.release.countDown()
            waitFor { document.text == "the real content" }

            assertEquals("the real content", document.text)
            assertTrue(document.isWritable)
            assertFalse(file.unread)
        } finally {
            gate.release.countDown()
        }
    }

    // Typed into before the blank text was locked: it cannot overwrite the instance's file, and the user chooses.
    fun testTextTypedIntoABlankDocumentIsNeverSavedOverTheInstancesFile() {
        stored["/slow.txt"] = "the real content"
        val gate = Gate().also { readGate = it }
        try {
            val file = VirtualFileManager.getInstance().findFileByUrl("kestra://$instance/company.team/slow.txt") as KestraVirtualFile
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            WriteAction.run<RuntimeException> { document.setText("typed over nothing") }
            assertThrows(IOException::class.java) { file.getOutputStream(this) }

            gate.release.countDown()
            waitFor { notifications.any { it.title == "Not loaded from Kestra" } }
            assertEquals("typed over nothing", document.text)
            assertThrows(IOException::class.java) { file.getOutputStream(this) }

            val notification = notifications.single { it.title == "Not loaded from Kestra" }
            val reload = notification.actions.single()
            (reload as com.intellij.notification.NotificationAction).actionPerformed(TestActionEvent.createTestEvent(reload), notification)
            assertEquals("the real content", document.text)
            assertFalse(file.unread)
            assertTrue(kestra.requests.none { it.method == "POST" })
        } finally {
            gate.release.countDown()
        }
    }

    // What was typed while the refresh read the instance's version is not overwritten by it.
    fun testRefreshDoesNotOverwriteWhatWasTypedDuringIt() {
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        val readme = KestraFileSystem.instance.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!
        panel.open("company.team")
        waitFor { rows(panel).size >= 4 }
        // The first listing found the file changed on the instance: it has to be read again.
        assertFalse(readme.hasContent)

        stored["/README.md"] = "# Changed on the instance"
        val gate = Gate().also { readGate = it }
        try {
            val refreshed = panel.refresh()
            // The listing needs the EDT before it reads the file: wait for the read to start without blocking it.
            waitFor { gate.entered.count == 0L }
            assertEquals(0L, gate.entered.count)
            WriteAction.run<RuntimeException> { document.setText("# typed during the refresh") }
            gate.release.countDown()
            waitUntilDone(refreshed)

            assertEquals("# typed during the refresh", document.text)
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        } finally {
            gate.release.countDown()
        }
    }

    fun testExcludedFoldersAreHiddenAndFilesOfAnotherInstanceAreNotListed() {
        val root = KestraFileSystem.instance.root(instance, "company.team")
        assertEquals(listOf("_flows", "scripts", "README.md"), KestraFileSystem.instance.list(root).map { it.name })
        KestraSettings.instance.update("http://elsewhere:8080", "", "INFO")
        assertThrows(IOException::class.java) { KestraFileSystem.instance.list(root) }
    }

    fun testFlowsFolderEditsTheNamespaceFlows() {
        val flows = java.util.concurrent.ConcurrentHashMap(mapOf("hello" to "id: hello\nnamespace: company.team\ntasks: []\n"))
        kestra.handle("GET", "/flows/company.team") { exchange, _ ->
            FakeKestra.respond(exchange, 200, flows.keys.joinToString(",", "[", "]") { """{"id":"$it"}""" })
        }
        kestra.handle("GET", "/flows/company.team/hello") { exchange, _ ->
            FakeKestra.respond(exchange, 200, com.google.gson.JsonObject().apply { addProperty("source", flows["hello"]) }.toString())
        }
        kestra.handle("PUT", "/flows/company.team/hello") { exchange, request ->
            flows["hello"] = request.body
            FakeKestra.respond(exchange, 200, "{}")
        }
        kestra.json("PUT", "/flows/company.team/created", status = 404) { "{}" }
        kestra.handle("POST", "/flows") { exchange, request ->
            flows["created"] = request.body
            FakeKestra.respond(exchange, 200, "{}")
        }
        kestra.handle("DELETE", "/flows/company.team/hello") { exchange, _ ->
            flows.remove("hello")
            FakeKestra.respond(exchange, 204, "")
        }

        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { "_flows" in rows(panel) }
        panel.tree.expandRow(rows(panel).indexOf("_flows"))
        waitFor { "hello.yml" in rows(panel) }

        val hello = KestraFileSystem.instance.findFileByPath("$instance/company.team/_flows/hello.yml") as KestraVirtualFile
        assertTrue(KestraFileSystem.instance.isFlow(hello))
        val document = FileDocumentManager.getInstance().getDocument(hello)!!
        assertEquals(flows["hello"], document.text)
        // The schema, validation, run and topology recognize it as a flow.
        assertTrue(io.kestra.jetbrains.flow.FlowYaml.isFlow(com.intellij.psi.PsiManager.getInstance(project).findFile(hello)!!))
        WriteAction.run<RuntimeException> { document.setText("id: hello\nnamespace: company.team\ntasks: [] # edited\n") }
        FileDocumentManager.getInstance().saveDocument(document)
        waitFor { flows["hello"]!!.contains("# edited") }
        assertTrue(flows["hello"]!!.contains("# edited"))

        select(panel, "hello.yml")
        TestDialogManager.setTestInputDialog(TestInputDialog { "created.yml" })
        panel.create(directory = false)
        waitFor { "created.yml" in rows(panel) }
        assertTrue(flows["created"]!!.startsWith("id: created\nnamespace: company.team\n"))

        // An existing flow is not replaced by a template.
        val listings = { kestra.requests.count { it.method == "GET" && it.path == "/flows/company.team" } }
        val before = listings()
        select(panel, "hello.yml")
        TestInputDialog { "hello.yml" }.let { TestDialogManager.setTestInputDialog(it) }
        waitUntilDone(panel.create(directory = false))
        assertTrue(listings() > before)
        assertTrue(notifications.any { it.content.contains("already exists") })
        assertTrue(flows["hello"]!!.contains("# edited"))

        select(panel, "hello.yml")
        TestDialogManager.setTestDialog(TestDialog.OK)
        panel.delete()
        waitFor { "hello.yml" !in rows(panel) }
        assertFalse(flows.containsKey("hello"))
        assertTrue(kestra.requests.none { it.path.startsWith("/namespaces/company.team/files") && it.method != "GET" })
    }

    private fun rows(panel: KestraNamespacesPanel): List<String> = (0 until panel.tree.rowCount).mapNotNull { row ->
        ((panel.tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode).userObject as? KestraVirtualFile)?.name
    }

    private fun select(panel: KestraNamespacesPanel, name: String) {
        val row = rows(panel).indexOf(name)
        assertTrue("$name not shown in ${rows(panel)}", row >= 0)
        panel.tree.setSelectionRow(row)
    }

    private fun waitUntilDone(future: CompletableFuture<Unit>) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        assertTrue("not done in time", future.isDone)
        future.getNow(Unit)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            // The saver outlives the tests: what a test left refused must not be asked about in the next one.
            NamespaceSaver.instance.unsyncedFiles().forEach(NamespaceSaver.instance::discard)
            NamespaceSaver.instance.resetForTests()
            writeGate?.countDown()
            readGate?.release?.countDown()
            runCatching { UnsavedCopies.directory.toFile().deleteRecursively() }
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
        } finally {
            super.tearDown()
        }
    }
}
