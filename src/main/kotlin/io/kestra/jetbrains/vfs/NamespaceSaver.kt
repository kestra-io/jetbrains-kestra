package io.kestra.jetbrains.vfs

import com.intellij.notification.NotificationAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import java.util.concurrent.ConcurrentHashMap

// Uploads saved namespace files in the background, so a slow instance never blocks the editor.
// Saves of one file are sent in order and only the latest pending content is sent. A failure leaves
// a notification with the instance's reason and a Retry action, and the file keeps its content: a listing
// never replaces it with the instance's version until a save went through.
// Quitting or restarting the IDE saves the open files, waits for the queued saves, then asks before dropping
// the ones the instance refused. What is dropped anyway is kept in a local copy, see UnsavedCopies.
@Service
class NamespaceSaver : Disposable {
    private val pending = ConcurrentHashMap<KestraVirtualFile, ByteArray>()
    private val running = ConcurrentHashMap.newKeySet<KestraVirtualFile>()
    private val failed = ConcurrentHashMap.newKeySet<KestraVirtualFile>()

    // The question and the wait of the exit prompt. Replaced in tests, which cannot show a dialog.
    internal interface ExitPrompt {
        fun waitForSaves(timeoutMillis: Long)

        fun confirmQuit(files: List<KestraVirtualFile>): Boolean
    }

    internal var prompt: ExitPrompt = SwingExitPrompt

    // True once the user chose Quit Anyway: the exit then waits for nothing, see SaveOnExit.
    @Volatile private var quitAnyway = false

    // Quit and Restart IDE (also Invalidate Caches and plugin installs) take different paths through
    // ApplicationImpl.canExit: the listener's canRestartApplication, or its canExitApplication.
    internal val exitListener = object : ApplicationListener {
        override fun canExitApplication() = confirmExit()

        override fun canRestartApplication() = confirmExit()
    }

    init {
        // Registered when the service is created, by the first listing or the first save: before that no
        // file of ours has changes to lose.
        ApplicationManager.getApplication().addApplicationListener(exitListener, this)
    }

    fun save(file: KestraVirtualFile, content: ByteArray) {
        pending[file] = content
        if (running.add(file)) ApplicationManager.getApplication().executeOnPooledThread { drain(file) }
    }

    // True while the content of the file is not on the instance: a save is queued, in flight, or was refused.
    fun hasUnsyncedChanges(file: KestraVirtualFile) = pending.containsKey(file) || file in running || file in failed

    // Files whose last content is not on the instance: queued, in flight, or refused.
    fun unsyncedFiles(): List<KestraVirtualFile> = (pending.keys + running + failed).filter { it.isValid }.distinct()

    // The file is gone, its changes with it.
    fun discard(file: KestraVirtualFile) {
        pending.remove(file)
        failed.remove(file)
    }

    // Waits for the queued and in-flight saves, up to the timeout. For the IDE exit: a save still running then is lost.
    fun flush(timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while ((pending.isNotEmpty() || running.isNotEmpty()) && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    // Runs before the platform closes the projects, whose close handler saves the documents: an edit typed
    // since the last save would reach save() only after this check passed, so save here first.
    internal fun confirmExit(): Boolean {
        quitAnyway = false
        FileDocumentManager.getInstance().saveAllDocuments()
        if (pending.isNotEmpty() || running.isNotEmpty()) prompt.waitForSaves(EXIT_WAIT_MILLIS)
        val unsynced = unsyncedFiles()
        if (unsynced.isEmpty()) return true
        if (!prompt.confirmQuit(unsynced)) return false
        quitAnyway = true
        keepCopies(unsynced)
        return true
    }

    // For the exits that skip the prompt above. Nothing may wait after Quit Anyway, and whatever is still not on
    // the instance at the end is kept locally: there is no window left to ask in.
    internal fun finishOnExit() {
        if (quitAnyway) return
        flush(EXIT_WAIT_MILLIS)
        keepCopies(unsyncedFiles())
    }

    private fun keepCopies(files: List<KestraVirtualFile>) {
        UnsavedCopies.keep(files.mapNotNull { file -> (pending[file] ?: file.content)?.let { file to it } })
    }

    // The saver outlives a test: its prompt and exit state must not leak into the next one.
    internal fun resetForTests() {
        prompt = SwingExitPrompt
        quitAnyway = false
    }

    // The failure notification's own way out: the file goes back to what the instance has.
    internal fun discardChanges(file: KestraVirtualFile) {
        discard(file)
        file.reloadFromInstance()
    }

    // A document typed into while its content was unreadable: offered, never forced.
    internal fun offerReload(file: KestraVirtualFile) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(
                "Not loaded from Kestra",
                StringUtil.escapeXmlEntities("${file.location} was typed into before it was read from the instance, so it cannot be saved. Reloading replaces the editor's text with the instance's file."),
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Reload from instance") { file.reloadDocument() })
            .notify(null)
    }

    private object SwingExitPrompt : ExitPrompt {
        private val unattended get() = ApplicationManager.getApplication().let { it.isUnitTestMode || it.isHeadlessEnvironment }

        // A cancellable-less progress window: the exit is blocked until the saves are done or the time is up.
        override fun waitForSaves(timeoutMillis: Long) {
            if (unattended) return
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { instance.flush(timeoutMillis) }, "Saving to Kestra", false, null,
            )
        }

        override fun confirmQuit(files: List<KestraVirtualFile>): Boolean {
            if (unattended) return true
            val names = files.take(MAX_LISTED).joinToString("\n") { "  ${it.location}" } +
                if (files.size > MAX_LISTED) "\n  and ${files.size - MAX_LISTED} more" else ""
            return Messages.showOkCancelDialog(
                "These files were not saved to Kestra:\n$names\n\nQuit anyway to keep a copy of each in ${UnsavedCopies.directory}.",
                "Changes Not Saved to Kestra",
                "Quit Anyway",
                "Keep Open",
                Messages.getWarningIcon(),
            ) == Messages.OK
        }
    }

    override fun dispose() = Unit

    private fun drain(file: KestraVirtualFile) {
        try {
            while (true) {
                val content = pending.remove(file)
                if (content == null) {
                    running.remove(file)
                    // A save that arrived after the check above restarts the loop if nobody else did.
                    if (!pending.containsKey(file) || !running.add(file)) return
                    continue
                }
                try {
                    file.fileSystem.write(file, content)
                    failed.remove(file)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    // Not only IOException: whatever it is, the file must not stay "running" and queue every later save.
                    failed.add(file)
                    notifyFailure(file, e)
                }
            }
        } catch (e: Throwable) {
            running.remove(file)
            throw e
        }
    }

    private fun notifyFailure(file: KestraVirtualFile, e: Exception) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(
                "Not saved to Kestra",
                StringUtil.escapeXmlEntities("${file.location}: ${e.message ?: e.javaClass.simpleName}. The editor keeps your changes."),
                NotificationType.ERROR,
            )
            .addAction(NotificationAction.createSimpleExpiring("Retry") {
                if (file.isValid) file.content?.let { save(file, it) }
            })
            // A refused file never picks up the instance's version and holds the exit prompt open: this is the way out.
            .addAction(NotificationAction.createSimpleExpiring("Discard local changes") {
                if (file.isValid) discardChanges(file)
            })
            .notify(null)
    }

    companion object {
        const val GROUP = "Kestra Namespace Files"
        const val EXIT_WAIT_MILLIS = 15_000L
        private const val MAX_LISTED = 10

        val instance: NamespaceSaver get() = service()
    }
}
