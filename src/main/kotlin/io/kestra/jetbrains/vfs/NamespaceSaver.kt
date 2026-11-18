package io.kestra.jetbrains.vfs

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.text.StringUtil
import java.util.concurrent.ConcurrentHashMap

// Uploads saved namespace files in the background, so a slow instance never blocks the editor.
// Saves of one file are sent in order and only the latest pending content is sent. A failure leaves
// a notification with the instance's reason and a Retry action, and the file keeps its content: a listing
// never replaces it with the instance's version until a save went through.
@Service
class NamespaceSaver {
    private val pending = ConcurrentHashMap<KestraVirtualFile, ByteArray>()
    private val running = ConcurrentHashMap.newKeySet<KestraVirtualFile>()
    private val failed = ConcurrentHashMap.newKeySet<KestraVirtualFile>()

    fun save(file: KestraVirtualFile, content: ByteArray) {
        pending[file] = content
        if (running.add(file)) ApplicationManager.getApplication().executeOnPooledThread { drain(file) }
    }

    // True while the content of the file is not on the instance: a save is queued, in flight, or was refused.
    fun hasUnsyncedChanges(file: KestraVirtualFile) = pending.containsKey(file) || file in running || file in failed

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

        val instance: NamespaceSaver get() = service()
    }
}
