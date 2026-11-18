package io.kestra.jetbrains.vfs

import com.intellij.notification.NotificationAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import java.util.concurrent.ConcurrentHashMap

// Uploads saved namespace files in the background, so a slow instance never blocks the editor.
// Saves of one file are sent in order and only the latest pending content is sent. A failure leaves
// a notification with the instance's reason and a Retry action, and the file keeps its content: a listing
// never replaces it with the instance's version until a save went through.
// Quitting the IDE waits for the queued saves, then asks before dropping the ones the instance refused.
@Service
class NamespaceSaver : Disposable {
    private val pending = ConcurrentHashMap<KestraVirtualFile, ByteArray>()
    private val running = ConcurrentHashMap.newKeySet<KestraVirtualFile>()
    private val failed = ConcurrentHashMap.newKeySet<KestraVirtualFile>()

    init {
        // Registered when the first save creates the service: with no save, there is nothing to lose.
        ApplicationManager.getApplication().addApplicationListener(object : ApplicationListener {
            override fun canExitApplication() = confirmExit()
        }, this)
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

    private fun confirmExit(): Boolean {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return true
        if (pending.isNotEmpty() || running.isNotEmpty()) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { flush(EXIT_WAIT_MILLIS) }, "Saving to Kestra", false, null,
            )
        }
        val unsynced = unsyncedFiles()
        if (unsynced.isEmpty()) return true
        val names = unsynced.take(MAX_LISTED).joinToString("\n") { "  ${it.location}" } +
            if (unsynced.size > MAX_LISTED) "\n  and ${unsynced.size - MAX_LISTED} more" else ""
        return Messages.showOkCancelDialog(
            "These files were not saved to Kestra, and their changes will be lost:\n$names",
            "Changes Not Saved to Kestra",
            "Quit Anyway",
            "Keep Open",
            Messages.getWarningIcon(),
        ) == Messages.OK
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
            .notify(null)
    }

    companion object {
        const val GROUP = "Kestra Namespace Files"
        const val EXIT_WAIT_MILLIS = 15_000L
        private const val MAX_LISTED = 10

        val instance: NamespaceSaver get() = service()
    }
}
