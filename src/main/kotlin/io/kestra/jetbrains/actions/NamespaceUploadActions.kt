package io.kestra.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import io.kestra.jetbrains.namespace.NamespaceUpload

private fun AnActionEvent.localSelection(): List<VirtualFile> =
    getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.filter { it.isInLocalFileSystem }.orEmpty()

// Uploads the selected files and folders, or the file in the editor.
class UploadToNamespaceAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NamespaceUpload.upload(project, e.localSelection(), "Upload to Kestra Namespace")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.localSelection().isNotEmpty()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

// Uploads every file of a folder: the selected one, or one picked from disk.
class SyncFolderToNamespaceAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val folders = e.localSelection().filter { it.isDirectory }.ifEmpty {
            listOfNotNull(FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Folder to Sync"), project, null))
        }
        if (folders.isNotEmpty()) NamespaceUpload.upload(project, folders, "Sync Folder to Kestra Namespace")
    }

    override fun update(e: AnActionEvent) {
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        // From a menu without a selection it asks for a folder; in the Project view only on folders.
        e.presentation.isEnabledAndVisible = e.project != null && (selection == null || selection.any { it.isDirectory && it.isInLocalFileSystem })
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
