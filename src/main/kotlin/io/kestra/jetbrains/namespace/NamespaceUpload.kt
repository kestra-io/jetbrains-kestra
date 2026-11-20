package io.kestra.jetbrains.namespace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.api.NamespaceFilesApi
import io.kestra.jetbrains.api.NamespaceFilesException
import io.kestra.jetbrains.settings.KestraSettings

data class LocalFile(val file: VirtualFile, val relative: String)

// Uploads local files to a namespace: one file to an exact path, or a selection of files and folders
// under a base path. Additive: files at the same path are overwritten, files only on the instance stay.
object NamespaceUpload {
    fun upload(project: Project, selection: List<VirtualFile>, title: String) {
        if (selection.isEmpty()) {
            Messages.showErrorDialog(project, "Select the files or folders to upload.", title)
            return
        }
        val namespaces = NamespaceTargetDialog.loadNamespaces(project) ?: return
        val single = selection.singleOrNull()?.takeIf { !it.isDirectory }

        if (single != null) {
            val dialog = NamespaceTargetDialog(project, title, namespaces, "/${single.name}", requireFileName = true, note = null)
            if (!dialog.showAndGet() || !dialog.confirmNew(project)) return
            run(project, dialog.selectedNamespace(), listOf(LocalFile(single, NamespacePaths.name(dialog.targetPath()))), NamespacePaths.parent(dialog.targetPath()))
            return
        }

        val files = gather(selection, KestraSettings.instance.excludedNames)
        if (files.isEmpty()) {
            KestraNotifier.info(project, "No files to upload: the selection is empty or only holds excluded names.")
            return
        }
        val source = selection.singleOrNull()?.let { "\"${it.name}\"" } ?: "${selection.size} items"
        val dialog = NamespaceTargetDialog(project, title, namespaces, "/", requireFileName = false,
            note = "${files.size} file(s) from $source. Files at the same path are overwritten, other files are kept.")
        if (!dialog.showAndGet() || !dialog.confirmNew(project)) return
        run(project, dialog.selectedNamespace(), files, dialog.targetPath())
    }

    // Expands the selection into namespace-relative files. A folder keeps its name as the first segment.
    // Excluded names are skipped in folders and in the selection itself, which can be a blanket one.
    fun gather(selection: List<VirtualFile>, excluded: List<String>): List<LocalFile> {
        val files = ArrayList<LocalFile>()
        fun walk(dir: VirtualFile, prefix: String) {
            for (child in dir.children.orEmpty()) {
                if (NamespacePaths.isExcluded(child.name, excluded)) continue
                val relative = "$prefix/${child.name}"
                when {
                    // A symlinked folder could loop.
                    child.isDirectory -> if (!child.`is`(VFileProperty.SYMLINK)) walk(child, relative)
                    else -> files += LocalFile(child, relative)
                }
            }
        }
        for (item in selection) {
            if (NamespacePaths.isExcluded(item.name, excluded)) continue
            if (item.isDirectory) walk(item, item.name) else files += LocalFile(item, item.name)
        }
        return files
    }

    internal fun run(project: Project, namespace: String, files: List<LocalFile>, basePath: String) {
        object : Task.Backgroundable(project, "Uploading to $namespace", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val api = NamespaceFilesApi()
                var uploaded = 0
                val failed = ArrayList<String>()
                var stopped: String? = null
                for ((index, file) in files.withIndex()) {
                    if (indicator.isCanceled) {
                        stopped = "Cancelled."
                        break
                    }
                    indicator.fraction = index.toDouble() / files.size
                    indicator.text2 = "${index + 1}/${files.size} ${file.relative}"
                    try {
                        api.write(namespace, NamespacePaths.join(basePath, file.relative), content(file.file))
                        uploaded++
                    } catch (e: KestraUnauthorizedException) {
                        KestraNotifier.signInRequired(project, "${e.origin} requires authentication. Sign in to upload to $namespace.")
                        return
                    } catch (e: NamespaceFilesException) {
                        failed += file.relative
                        // Permissions will not change for the remaining files.
                        if (e.denied) {
                            stopped = e.message
                            break
                        }
                    } catch (e: KestraApiException) {
                        stopped = e.message
                        break
                    } catch (_: java.io.IOException) {
                        failed += file.relative
                    }
                }
                notify(project, namespace, files.size, uploaded, failed, stopped)
                NamespaceFilesEvents.changed(namespace)
            }
        }.queue()
    }

    // The editor's unsaved text when there is any, otherwise the exact bytes on disk.
    private fun content(file: VirtualFile): ByteArray {
        val unsaved = ApplicationManager.getApplication().runReadAction<String?> {
            val documents = FileDocumentManager.getInstance()
            documents.getCachedDocument(file)?.takeIf { documents.isDocumentUnsaved(it) }?.text
        }
        return unsaved?.toByteArray(file.charset) ?: file.contentsToByteArray()
    }

    private fun notify(project: Project, namespace: String, total: Int, uploaded: Int, failed: List<String>, stopped: String?) {
        val target = if (total == 1) "1 file" else "$total files"
        if (uploaded == total) {
            KestraNotifier.info(project, "Uploaded $target to $namespace.")
            return
        }
        val failures = if (failed.isEmpty()) "" else " Failed: ${failed.take(5).joinToString()}${if (failed.size > 5) " and ${failed.size - 5} more" else ""}."
        KestraNotifier.warn(project, "Uploaded $uploaded of $target to $namespace.$failures${stopped?.let { " $it" }.orEmpty()}")
    }
}
