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
import io.kestra.jetbrains.namespace.KestraNamespacesPanel
import io.kestra.jetbrains.settings.KestraSettings
import io.kestra.jetbrains.vfs.KestraFileSystem
import io.kestra.jetbrains.vfs.KestraVirtualFile
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.tree.DefaultMutableTreeNode

class NamespaceBrowserTest : KestraTestCase() {
    private val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val instance get() = KestraFileSystem.currentInstance()!!

    override fun setUp() {
        super.setUp()
        stored["/README.md"] = "# Hello"
        stored["/scripts/run.py"] = "print(1)"
        stored["/.git/config"] = "x"
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
            val content = stored[query(request, "path")]
            FakeKestra.respond(exchange, if (content == null) 404 else 200, content ?: """{"detail":"not found"}""")
        }
        kestra.handle("POST", "/namespaces/company.team/files") { exchange, request ->
            val path = query(request, "path")
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
        val notifications = CopyOnWriteArrayList<Notification>()
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
        val locked = KestraFileSystem.instance.findFileByPath("$instance/company.team/locked.txt") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(locked, true)
        val document = FileDocumentManager.getInstance().getDocument(locked)!!
        WriteAction.run<RuntimeException> { document.setText("y") }
        FileDocumentManager.getInstance().saveDocument(document)
        waitFor { notifications.isNotEmpty() }
        val notification = notifications.single()
        assertTrue(notification.content, notification.content.contains("Missing NAMESPACE UPDATE permission"))
        assertEquals(listOf("Retry"), notification.actions.map { it.templateText })
        assertEquals("x", stored["/locked.txt"])
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
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
        } finally {
            super.tearDown()
        }
    }
}
