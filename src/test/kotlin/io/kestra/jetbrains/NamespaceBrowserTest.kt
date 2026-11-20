package io.kestra.jetbrains

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.progress.impl.CoreProgressManager
import com.intellij.openapi.progress.util.TitledIndicator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.TestActionEvent
import io.kestra.jetbrains.namespace.KestraNamespacesPanel
import io.kestra.jetbrains.settings.KestraSettings
import io.kestra.jetbrains.vfs.ExitGuard
import io.kestra.jetbrains.vfs.KestraFileSystem
import io.kestra.jetbrains.vfs.KestraVirtualFile
import io.kestra.jetbrains.vfs.NamespaceSaver
import io.kestra.jetbrains.vfs.SaveOnExit
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

    // Makes the reads of namespace files fail, as an instance that is down.
    @Volatile private var failReads = false

    // Holds the upload of a file whose name contains "slowwrite", so a save stays in flight.
    @Volatile private var writeGate: CountDownLatch? = null

    // What the instance reports as the modification time of every file: raised, a file changed there.
    @Volatile private var modifiedTime = 1L

    // As the real instance: a folder without any file answers "Directory not found" instead of an empty list.
    @Volatile private var strictDirectories = false

    // The questions the IDE asked, answered by answer().
    private val dialogs = CopyOnWriteArrayList<String>()

    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        // The saver is an application service that outlives a test: each one gets its own, with nothing refused or queued.
        ApplicationManager.getApplication().replaceService(NamespaceSaver::class.java, NamespaceSaver(), testRootDisposable)
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
            if (strictDirectories && names.isEmpty() && dir.isNotEmpty()) return@handle FakeKestra.respond(exchange, 404, """{"message":"Directory not found"}""")
            val entries = names.map { it.substringBefore('/') to it.contains('/') }.distinct().joinToString(",") { (name, isDir) ->
                """{"type":"${if (isDir) "Directory" else "File"}","size":1,"fileName":"$name","lastModifiedTime":$modifiedTime}"""
            }
            FakeKestra.respond(exchange, 200, "[$entries]")
        }
        kestra.handle("GET", "/namespaces/company.team/files") { exchange, request ->
            if (failReads) return@handle FakeKestra.respond(exchange, 500, """{"message":"down"}""")
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
        assertEquals(listOf(locked), NamespaceSaver.instance.unsyncedFiles())
        modifiedTime = 2
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
        // The failure is reported a moment before the save stops running.
        waitFor { notifications.any { it.title == "Not saved to Kestra" } && !NamespaceSaver.instance.isSaving(file) }
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
        assertTrue(document.isWritable)
        assertFalse(NamespaceSaver.instance.hasUnsyncedChanges(locked))
        assertEquals(emptyList<KestraVirtualFile>(), NamespaceSaver.instance.unsyncedFiles())
        assertEquals("x", stored["/locked.txt"])
    }

    // The notification expires; the file stays unsynced, so the tool window has to offer the way out as well.
    fun testDiscardIsStillOfferedByTheToolWindowAfterTheNotificationExpired() {
        stored["/locked.txt"] = "x"
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { "locked.txt" in rows(panel) }
        val (locked, notification) = refuseSave("/locked.txt")
        val document = FileDocumentManager.getInstance().getDocument(locked)!!
        notification.expire()
        assertEquals("y", document.text)

        select(panel, "locked.txt")
        TestDialogManager.setTestDialog(TestDialog.OK)
        panel.discardLocalChanges()
        waitFor { document.text == "x" }

        assertEquals("x", document.text)
        assertFalse(NamespaceSaver.instance.hasUnsyncedChanges(locked))
    }

    // The file is gone from the instance, its refused edit is not: only the user drops it.
    fun testRefusedEditSurvivesTheFileLeavingTheInstance() {
        val fs = KestraFileSystem.instance
        val root = fs.root(instance, "company.team")
        val (locked, _) = refuseSave("/locked.txt")
        assertTrue(fs.list(root).contains(locked))

        stored.remove("/locked.txt")
        val listed = fs.list(root)

        // Not listed by the instance any more, but still here, with its edit.
        assertFalse(listed.toString(), listed.contains(locked))
        assertTrue(root.children.contains(locked))
        assertTrue(locked.isValid)
        assertTrue(NamespaceSaver.instance.hasUnsyncedChanges(locked))
        assertEquals("y", String(locked.contentsToByteArray()))
        // A file that really left, with nothing to lose, is forgotten.
        stored.remove("/README.md")
        assertFalse(fs.list(root).any { it.name == "README.md" })
        assertFalse(root.children.any { it.name == "README.md" })
    }

    // Discarding what the instance no longer has cannot bring anything back: the file leaves the IDE too.
    fun testDiscardingTheEditOfAFileThatLeftTheInstanceForgetsIt() {
        val (locked, _) = refuseSave("/locked.txt")
        stored.remove("/locked.txt")

        NamespaceSaver.instance.discardChanges(locked)
        waitFor { !locked.isValid }

        assertFalse(locked.isValid)
        assertEquals(emptyList<KestraVirtualFile>(), NamespaceSaver.instance.unsyncedFiles())
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(locked))
    }

    // A kept folder is not listed again: the instance answers "Directory not found", and that failed Refresh for every namespace.
    fun testKeptFolderDoesNotBreakRefresh() {
        stored["/scripts/locked.txt"] = "x"
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { "scripts" in rows(panel) }
        panel.tree.expandRow(rows(panel).indexOf("scripts"))
        waitFor { "locked.txt" in rows(panel) }
        refuseSave("/scripts/locked.txt")

        strictDirectories = true
        stored.keys.removeIf { it.startsWith("/scripts/") }
        waitUntilDone(panel.refresh())

        assertTrue(notifications.toString(), notifications.none { it.content.contains("failed") })
        assertTrue(rows(panel).toString(), rows(panel).containsAll(listOf("scripts", "locked.txt")))
    }

    // Quit and Restart IDE reach the application listener through different methods.
    private val guard get() = service<ExitGuard>()

    // The user's answer to the question of the exit: Quit Anyway, or Keep Open.
    private fun answer(quitAnyway: Boolean) {
        TestDialogManager.setTestDialog { message ->
            dialogs += message
            if (quitAnyway) Messages.OK else Messages.CANCEL
        }
    }

    // The user pressing Cancel on the progress of the exit, once it is shown: true when there was one to cancel.
    private fun cancelWaitingForSaves(): CompletableFuture<Boolean> = CompletableFuture.supplyAsync {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            // Ours: saving the documents has a progress of its own just before.
            val indicators = CoreProgressManager.getCurrentIndicators().filter { it is TitledIndicator && it.title == "Saving to Kestra" }
            if (indicators.isNotEmpty()) {
                indicators.forEach { it.cancel() }
                return@supplyAsync true
            }
            Thread.sleep(10)
        }
        false
    }

    // A save held in flight by the instance, for the exits that have to decide what to do with it.
    private fun holdASave(): KestraVirtualFile {
        stored["/slowwrite.txt"] = "a"
        val slow = KestraFileSystem.instance.findFileByPath("$instance/company.team/slowwrite.txt") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(slow, true)
        val document = FileDocumentManager.getInstance().getDocument(slow)!!
        WriteAction.run<RuntimeException> { document.setText("b") }
        return slow
    }

    // A tab restored at startup, edited, then Cmd+Q: the saver was never used, and the platform saves the document
    // only when it closes the projects, after the IDE stopped asking. The exit has to save it first.
    fun testExitSavesWhatWasTypedLastBeforeCheckingForUnsavedFiles() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        val readme = KestraFileSystem.instance.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!
        // Typed, not saved: the platform saves it only when it closes the projects.
        WriteAction.run<RuntimeException> { document.setText("# typed right before quitting") }
        SaveOnExit().appClosing()

        assertTrue(guard.canExitApplication())

        assertEquals("# typed right before quitting", stored["/README.md"])
        // Nothing was left to ask about: the dialog of the test platform would have failed.
        assertEquals(emptyList<String>(), dialogs.toList())
    }

    fun testRestartAsksLikeQuitAndQuitAnywayKeepsACopy() {
        answer(quitAnyway = false)
        val (locked, _) = refuseSave("/locked.txt")
        SaveOnExit().appClosing()

        assertFalse(guard.canRestartApplication())
        assertFalse(guard.canExitApplication())
        assertEquals(2, dialogs.size)
        assertTrue(dialogs.toString(), dialogs.all { it.contains("/locked.txt") })

        // A second file whose save is in flight when the user gives up waiting, and chooses Quit Anyway.
        val release = CountDownLatch(1).also { writeGate = it }
        try {
            holdASave()
            answer(quitAnyway = true)
            val cancelled = cancelWaitingForSaves()
            assertTrue(guard.canRestartApplication())
            assertTrue(cancelled.get(30, TimeUnit.SECONDS))
            assertTrue(dialogs.last().contains("/locked.txt") && dialogs.last().contains("/slowwrite.txt"))

            // Nothing waits after Quit Anyway, although a save is still in flight: the exit hook returns at once.
            // (Captures the hook: a lambda capturing nothing is a no-argument method named test…, taken for a test.)
            val hook = SaveOnExit()
            CompletableFuture.runAsync { hook.appWillBeClosed(true) }.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
        }
        assertEquals(setOf("y", "b"), keptCopies().toSet())
    }

    // Quit Anyway is the answer to one exit attempt: when another listener then refused the exit, the next attempt
    // starts over, and a forced exit that skips the question waits for the saves again.
    fun testAnExitAttemptStartsOverAfterAnEarlierQuitAnyway() {
        val release = CountDownLatch(1).also { writeGate = it }
        try {
            holdASave()
            answer(quitAnyway = true)
            val cancelled = cancelWaitingForSaves()
            assertTrue(guard.canExitApplication())
            assertTrue(cancelled.get(30, TimeUnit.SECONDS))

            SaveOnExit().appClosing()
            val waited = cancelWaitingForSaves()
            SaveOnExit().appWillBeClosed(false)
            assertTrue(waited.get(30, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }

    // The progress that waits for the uploads can be cancelled: the user is not held for the 15 seconds.
    fun testWaitingForTheSavesCanBeCancelledThenAsksWhatToDoWithThem() {
        answer(quitAnyway = false)
        val release = CountDownLatch(1).also { writeGate = it }
        try {
            holdASave()
            SaveOnExit().appClosing()
            val cancelled = cancelWaitingForSaves()
            val start = System.currentTimeMillis()

            assertFalse(guard.canExitApplication())

            assertTrue(cancelled.get(30, TimeUnit.SECONDS))
            assertTrue(System.currentTimeMillis() - start < NamespaceSaver.EXIT_WAIT_MILLIS)
            // Asked about the file whose save is still in flight.
            assertEquals(1, dialogs.size)
            assertTrue(dialogs.single().contains("/slowwrite.txt"))
        } finally {
            release.countDown()
        }
    }

    // An exit that skipped the prompt still leaves a copy of what the instance did not take.
    fun testExitWithoutThePromptKeepsACopyOfWhatWasRefused() {
        refuseSave("/locked.txt")

        // A forced exit: the platform publishes appClosing, skips the listeners that may refuse, then appWillBeClosed.
        SaveOnExit().appClosing()
        SaveOnExit().appWillBeClosed(false)

        assertEquals(listOf("y"), keptCopies())
        // Reported once at the next start, then left quiet.
        notifications.clear()
        UnsavedCopies.report(project)
        assertEquals(1, notifications.count { it.content.contains("were not saved to the instance") })
        UnsavedCopies.report(project)
        assertEquals(1, notifications.count { it.content.contains("were not saved to the instance") })
    }

    // /a/b.txt and /a_b.txt used to become the same file name, and the second copy replaced the first.
    fun testCopiesOfFilesWhoseNamesFlattenTheSameAreAllKept() {
        val fs = KestraFileSystem.instance
        val nested = fs.findFileByPath("$instance/company.team/a/b.txt") as KestraVirtualFile
        val flat = fs.findFileByPath("$instance/company.team/a_b.txt") as KestraVirtualFile

        UnsavedCopies.keep(listOf(nested to "nested".toByteArray(), flat to "flat".toByteArray()))

        assertEquals(setOf("nested", "flat"), keptCopies().toSet())
    }

    // The folder of the copies a user was told about does not grow for ever.
    fun testReportedCopiesAreDeletedAfterAMonth() {
        val reported = UnsavedCopies.directory.resolve("reported")
        val old = reported.resolve((System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(31)).toString())
        val recent = reported.resolve((System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)).toString())
        listOf(old, recent).forEach { java.nio.file.Files.createDirectories(it) }

        UnsavedCopies.report(project)

        assertFalse(java.nio.file.Files.exists(old))
        assertTrue(java.nio.file.Files.exists(recent))
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

    // EditorsSplitters reads a restored tab on a pooled thread, outside any read action, and reports any exception
    // as a plugin error (the test framework fails on it): the failure must stay quiet, and the editor then opens
    // the file read-only and reloads it when the content lands.
    fun testRestoringATabOfAnUnreachableInstanceDoesNotThrowOnABackgroundThread() {
        stored["/slow.txt"] = "the real content"
        failReads = true
        val file = VirtualFileManager.getInstance().findFileByUrl("kestra://$instance/company.team/slow.txt") as KestraVirtualFile
        val restored = restore(file)
        assertEquals(0, restored.size)
        failReads = false

        val gate = Gate().also { readGate = it }
        try {
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("", document.text)
            assertFalse(document.isWritable)
            assertFalse(file.isWritable)

            gate.release.countDown()
            waitFor { document.text == "the real content" }
            assertTrue(document.isWritable)
        } finally {
            gate.release.countDown()
        }
    }

    fun testRestoringATabOfAnotherInstanceDoesNotThrowOnABackgroundThread() {
        val other = VirtualFileManager.getInstance().findFileByUrl("kestra://elsewhere:8080/company.team/README.md") as KestraVirtualFile
        assertEquals(0, restore(other).size)

        val document = FileDocumentManager.getInstance().getDocument(other)!!
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("", document.text)
        assertFalse(document.isWritable)
        assertThrows(IOException::class.java) { other.getOutputStream(this) }
        assertTrue(notifications.any { it.content.contains("connected to another instance") })
        // The error stays for the callers that rely on it: only the platform's preload is quiet.
        assertThrows(IOException::class.java) { other.contentsToByteArray() }
    }

    // What EditorsSplitters.computeFileEntry does: contentsToByteArray(true) on a pooled thread, no read action.
    private fun restore(file: KestraVirtualFile): ByteArray =
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<ByteArray> { file.contentsToByteArray(true) }.get(60, TimeUnit.SECONDS)

    // A tab restored at startup asks for its content before a slow instance answered: the platform turns the
    // error into a blank document and never asks again. The file is not writable without content, so the document
    // is read-only, and it is unlocked and filled when the content lands.
    fun testDocumentOfASlowFileIsReadOnlyUntilItsContentLands() {
        stored["/slow.txt"] = "the real content"
        val gate = Gate().also { readGate = it }
        try {
            val file = VirtualFileManager.getInstance().findFileByUrl("kestra://$instance/company.team/slow.txt") as KestraVirtualFile
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            assertEquals("", document.text)
            assertFalse(file.isWritable)
            // Nothing can be typed into the blank text, so nothing can be saved over the instance's file.
            assertFalse(document.isWritable)
            assertThrows(com.intellij.openapi.editor.ReadOnlyModificationException::class.java) {
                WriteAction.run<RuntimeException> { document.setText("typed over nothing") }
            }
            assertThrows(IOException::class.java) { file.getOutputStream(this) }

            gate.release.countDown()
            waitFor { document.text == "the real content" }

            assertEquals("the real content", document.text)
            assertTrue(document.isWritable)
            assertTrue(file.isWritable)
            assertTrue(kestra.requests.none { it.method == "POST" })
        } finally {
            gate.release.countDown()
        }
    }

    // A failed read is tried again while an editor shows the file: it used to stay locked until a manual Refresh.
    fun testBlankDocumentIsReadAgainByItselfWhenTheInstanceComesBack() {
        stored["/retry.txt"] = "the real content"
        failReads = true
        val file = VirtualFileManager.getInstance().findFileByUrl("kestra://$instance/company.team/retry.txt") as KestraVirtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        assertEquals("", document.text)
        assertFalse(document.isWritable)
        waitFor { notifications.any { it.content.contains("could not be read from the instance yet") } }
        assertTrue(notifications.toString(), notifications.any { it.content.contains("could not be read from the instance yet") })

        failReads = false
        waitFor { document.text == "the real content" }

        assertEquals("the real content", document.text)
        assertTrue(document.isWritable)
    }

    // A listing that finds the file changed does not replace the text of an editor with unsaved changes, so that
    // Ctrl+S, even with the instance unreachable for reads, saves what was typed instead of failing in a read,
    // which counts the document as saved and loses the edit at the next Refresh.
    fun testListingNeverReplacesAnUnsavedEditor() {
        val fs = KestraFileSystem.instance
        val root = fs.root(instance, "company.team")
        fs.list(root)
        val readme = fs.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!
        assertEquals("# Hello", document.text)
        WriteAction.run<RuntimeException> { document.setText("# typed") }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))

        modifiedTime = 2
        stored["/README.md"] = "# Changed on the instance"
        fs.list(root)

        assertTrue(readme.hasContent)
        assertTrue(document.isWritable)
        assertEquals("# typed", document.text)

        failReads = true
        FileDocumentManager.getInstance().saveDocument(document)
        waitFor { stored["/README.md"] == "# typed" }
        assertEquals("# typed", stored["/README.md"])
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }

    // An open file the listing found changed is locked while it is read again, then shows the new text.
    fun testRefreshReloadsAChangedOpenFileAndLocksItMeanwhile() {
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { rows(panel).size >= 4 }
        val readme = KestraFileSystem.instance.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!
        assertEquals("# Hello", document.text)
        assertTrue(document.isWritable)

        modifiedTime = 2
        stored["/README.md"] = "# Changed on the instance"
        val gate = Gate().also { readGate = it }
        try {
            val refreshed = panel.refresh()
            waitFor { gate.entered.count == 0L }
            assertEquals(0L, gate.entered.count)
            // The text is the old one: nothing may be typed into it while the new one is on its way.
            assertEquals("# Hello", document.text)
            assertFalse(document.isWritable)
            gate.release.countDown()
            waitUntilDone(refreshed)
            waitFor { document.text == "# Changed on the instance" }

            assertEquals("# Changed on the instance", document.text)
            assertTrue(document.isWritable)
        } finally {
            gate.release.countDown()
        }
    }

    // A read that fails while an open file is reloaded keeps its text, says why, and is read again by itself:
    // the file is not left locked, and no message pretends it was never read.
    fun testFailedReloadOfAnOpenFileIsReportedAndReadAgainByItself() {
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { rows(panel).size >= 4 }
        val readme = KestraFileSystem.instance.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!

        modifiedTime = 2
        stored["/README.md"] = "# Changed on the instance"
        failReads = true
        waitUntilDone(panel.refresh())
        waitFor { notifications.any { it.content.contains("could not be read from the instance yet") } }

        assertTrue(notifications.toString(), notifications.any { it.content.contains("could not be read from the instance yet") })
        assertFalse(document.isWritable)
        assertEquals("# Hello", document.text)
        assertTrue(notifications.toString(), notifications.none { it.title == "Not loaded from Kestra" || it.content.contains("shown empty") })

        failReads = false
        waitFor { document.text == "# Changed on the instance" }

        assertEquals("# Changed on the instance", document.text)
        assertTrue(document.isWritable)
    }

    // A namespace that fails to list must not leave the open files of the namespaces listed before it locked.
    fun testChangedOpenFileIsReloadedWhenALaterNamespaceFailsToList() {
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { rows(panel).size >= 4 }
        // The fake instance has no such namespace: listing it fails, after company.team was listed.
        panel.open("missing.team")
        Disposer.register(testRootDisposable) { project.service<io.kestra.jetbrains.namespace.KestraNamespaces>().remove("missing.team") }
        val readme = KestraFileSystem.instance.findFileByPath("$instance/company.team/README.md") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(readme, true)
        val document = FileDocumentManager.getInstance().getDocument(readme)!!
        waitFor { document.text == "# Hello" }

        modifiedTime = 2
        stored["/README.md"] = "# Changed on the instance"
        waitUntilDone(panel.refresh())
        waitFor { document.text == "# Changed on the instance" }

        assertTrue(notifications.toString(), notifications.any { it.content.contains("Refreshing Kestra namespaces failed") })
        assertEquals("# Changed on the instance", document.text)
        assertTrue(document.isWritable)
    }

    // Discarding while the file's upload is in flight would let that upload land after the discard: the instance
    // would keep the edit while the editor shows the old version as saved. Not offered, and refused if asked.
    fun testDiscardIsRefusedWhileTheFileIsBeingSaved() {
        stored["/slowwrite.txt"] = "x"
        val panel = KestraNamespacesPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.open("company.team")
        waitFor { "slowwrite.txt" in rows(panel) }
        val file = KestraFileSystem.instance.findFileByPath("$instance/company.team/slowwrite.txt") as KestraVirtualFile
        FileEditorManager.getInstance(project).openFile(file, true)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        waitFor { document.text == "x" }
        val release = CountDownLatch(1).also { writeGate = it }
        WriteAction.run<RuntimeException> { document.setText("y") }
        FileDocumentManager.getInstance().saveDocument(document)
        assertTrue(NamespaceSaver.instance.isSaving(file))

        // The tool window does not offer it: no question is asked.
        select(panel, "slowwrite.txt")
        TestDialogManager.setTestDialog { message -> dialogs += message; Messages.OK }
        panel.discardLocalChanges()
        assertEquals(emptyList<String>(), dialogs)

        // Asked anyway, from the notification of an earlier refused save: refused, the edit is kept.
        NamespaceSaver.instance.discardChanges(file)
        waitFor { notifications.any { it.title == "Not discarded" } }
        assertTrue(notifications.toString(), notifications.any { it.title == "Not discarded" && it.content.contains("in progress") })
        assertEquals("y", document.text)

        release.countDown()
        waitFor { !NamespaceSaver.instance.hasUnsyncedChanges(file) }
        assertEquals("y", stored["/slowwrite.txt"])
        assertEquals("y", document.text)
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
