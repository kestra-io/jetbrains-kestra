package io.kestra.jetbrains.vfs

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// Uploads saved namespace files in the background, so a slow instance never blocks the editor.
// Saves of one file are sent in order and only the latest pending content is sent. A failure leaves
// a notification with the instance's reason and a Retry action.
@Service
class NamespaceSaver {
    private val pending = ConcurrentHashMap<KestraVirtualFile, ByteArray>()
    private val running = ConcurrentHashMap.newKeySet<KestraVirtualFile>()

    fun save(file: KestraVirtualFile, content: ByteArray) {
        pending[file] = content
        if (running.add(file)) ApplicationManager.getApplication().executeOnPooledThread { drain(file) }
    }

    fun isPending(file: KestraVirtualFile) = pending.containsKey(file) || file in running

    private fun drain(file: KestraVirtualFile) {
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
            } catch (e: IOException) {
                failed(file, e)
            }
        }
    }

    private fun failed(file: KestraVirtualFile, e: IOException) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification("Not saved to Kestra", StringUtil.escapeXmlEntities("${file.presentableUrl}: ${e.message}"), NotificationType.ERROR)
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
