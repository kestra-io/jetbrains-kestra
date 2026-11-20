package io.kestra.jetbrains.vfs

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import io.kestra.jetbrains.api.NamespaceFilesException
import io.kestra.jetbrains.namespace.NamespaceFilesEvents
import java.util.concurrent.ConcurrentHashMap

// Uploads saved namespace files in the background, so a slow instance never blocks the editor.
// Saves of one file are sent in order and only the latest pending content is sent. A failure leaves
// a notification with the instance's reason and a Retry action, and the file keeps its content: a listing
// never replaces it with the instance's version until a save went through or the user discards it.
// What happens when the IDE quits with saves outstanding is in ExitGuard.
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

    // A save of the file is queued or in flight.
    fun isSaving(file: KestraVirtualFile) = pending.containsKey(file) || file in running

    // Files whose last content is not on the instance: queued, in flight, or refused.
    fun unsyncedFiles(): List<KestraVirtualFile> = (pending.keys + running + failed).filter { it.isValid }.distinct()

    // The file is gone, its changes with it.
    fun discard(file: KestraVirtualFile) {
        pending.remove(file)
        failed.remove(file)
    }

    // Waits for the queued and in-flight saves, up to the timeout, behind a progress the user can cancel. A save
    // still running afterwards is not waited for: the caller asks what to do with it.
    fun waitForSaves(timeoutMillis: Long) {
        if (pending.isEmpty() && running.isEmpty()) return
        ProgressManager.getInstance().runProcessWithProgressSynchronously({ flush(timeoutMillis) }, "Saving to Kestra", true, null)
    }

    private fun flush(timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while ((pending.isNotEmpty() || running.isNotEmpty()) && System.currentTimeMillis() < deadline) {
            ProgressManager.checkCanceled()
            Thread.sleep(50)
        }
    }

    // What is not on the instance, kept locally: the IDE is about to close without it.
    fun keepCopies(files: List<KestraVirtualFile>) {
        UnsavedCopies.keep(files.mapNotNull { file -> (pending[file] ?: file.content)?.let { file to it } })
    }

    // The way out of a refused save: the instance's version replaces the local one, in the editor too.
    // The changes are kept when the instance cannot be read, and while a save of the file is queued or in flight:
    // that upload would land after the discard, so the instance would keep the discarded edit while the editor
    // shows its old version as saved. Checked again on the EDT right before the swap, as saves start on the EDT.
    fun discardChanges(file: KestraVirtualFile) {
        if (isSaving(file)) {
            notifyDiscardFailed(file, IllegalStateException("a save to the instance is in progress"))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val read = runCatching { file.fileSystem.read(file) }
            ApplicationManager.getApplication().invokeLater({
                if (isSaving(file)) {
                    notifyDiscardFailed(file, IllegalStateException("a save to the instance started meanwhile"))
                    return@invokeLater
                }
                read.onSuccess { bytes ->
                    discard(file)
                    file.adopt(bytes)
                }.onFailure { e ->
                    if ((e.cause as? NamespaceFilesException)?.status == 404) forgetGone(file) else notifyDiscardFailed(file, e)
                }
            }, { !file.isValid })
        }
    }

    // The file is not on the instance any more: nothing is left to bring back, it leaves the IDE as well.
    private fun forgetGone(file: KestraVirtualFile) {
        ProjectManager.getInstance().openProjects.forEach { FileEditorManager.getInstance(it).closeFile(file) }
        WriteAction.run<RuntimeException> { file.fileSystem.deleted(file) }
        NamespaceFilesEvents.changed(file.namespace)
    }

    private fun notifyDiscardFailed(file: KestraVirtualFile, e: Throwable) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(
                "Not discarded",
                StringUtil.escapeXmlEntities("${file.location}: ${e.message ?: e.javaClass.simpleName}. The changes are kept."),
                NotificationType.ERROR,
            )
            .notify(null)
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
            // The notification expires; the Kestra Namespaces tool window offers the same way out afterwards.
            .addAction(NotificationAction.createSimpleExpiring("Discard local changes") {
                if (file.isValid) discardChanges(file)
            })
            .notify(null)
    }

    companion object {
        const val GROUP = "Kestra Namespace Files"
        const val EXIT_WAIT_MILLIS = 15_000L

        val instance: NamespaceSaver get() = service()
    }
}
