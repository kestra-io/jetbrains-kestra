package io.kestra.jetbrains.vfs

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.api.NamespaceFilesApi
import io.kestra.jetbrains.namespace.NamespacePaths
import io.kestra.jetbrains.settings.KestraSettings
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// kestra://<instance>/<namespace>/<path>: the namespace files of an instance, as the IDE's editors see them.
// The instance is part of the URL, so a file opened from one instance is never written to another one
// after the settings change. Changes go through the Kestra Namespaces tool window, which calls the
// instance first and then updates this model.
class KestraFileSystem : DeprecatedVirtualFileSystem() {
    private val files = ConcurrentHashMap<String, KestraVirtualFile>()

    init {
        startEventPropagation()
    }

    override fun getProtocol() = PROTOCOL

    // Also called to reopen editor tabs on startup, before anything was listed: the file is created
    // from its path and read on first access.
    override fun findFileByPath(path: String): VirtualFile? {
        files[path]?.let { return it }
        val segments = path.trim('/').split('/')
        if (segments.size < 2 || segments.any { it.isEmpty() }) return null
        var current = root(segments[0], segments[1])
        for ((index, name) in segments.drop(2).withIndex()) {
            current = child(current, name, directory = index < segments.size - 3)
        }
        return current
    }

    override fun refresh(asynchronous: Boolean) = Unit

    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

    override fun isReadOnly() = false

    fun root(instance: String, namespace: String): KestraVirtualFile =
        files.getOrPut("$instance/$namespace") { KestraVirtualFile(this, instance, namespace, "/", true, null) }

    internal fun child(parent: KestraVirtualFile, name: String, directory: Boolean): KestraVirtualFile {
        val relative = NamespacePaths.join(parent.relativePath, name)
        val path = "${parent.instance}/${parent.namespace}$relative"
        files[path]?.takeIf { it.isDirectory == directory && it.valid }?.let { return it }
        return KestraVirtualFile(this, parent.instance, parent.namespace, relative, directory, parent).also { files[path] = it }
    }

    // Lists a folder from the instance. Network: background threads only.
    fun list(dir: KestraVirtualFile): List<KestraVirtualFile> {
        val entries = io { api(dir).list(dir.namespace, dir.relativePath) }
        val children = entries.filter { it.name !in HIDDEN }.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() })).map { entry ->
            child(dir, entry.name, entry.directory).also {
                // A file changed on the instance is read again, unless it has a save in flight.
                if (it.modified != entry.modified && !NamespaceSaver.instance.isPending(it)) it.content = null
                it.size = entry.size
                it.modified = entry.modified
            }
        }
        dir.loadedChildren?.filter { it !in children }?.forEach(::forget)
        dir.loadedChildren = children
        return children
    }

    internal fun read(file: KestraVirtualFile): ByteArray = io { api(file).read(file.namespace, file.relativePath) }

    internal fun write(file: KestraVirtualFile, content: ByteArray) = io { api(file).write(file.namespace, file.relativePath, content) }

    // Model updates once the instance accepted the change. EDT.
    fun created(parent: KestraVirtualFile, name: String, directory: Boolean): KestraVirtualFile {
        val file = child(parent, name, directory)
        if (!directory) file.content = ByteArray(0)
        if (directory) file.loadedChildren = emptyList()
        parent.loadedChildren = (parent.loadedChildren.orEmpty().filter { it != file } + file)
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        fireFileCreated(null, file)
        return file
    }

    fun deleted(file: KestraVirtualFile) {
        fireBeforeFileDeletion(null, file)
        file.parentFile?.let { parent -> parent.loadedChildren = parent.loadedChildren?.filter { it != file } }
        forget(file)
        fireFileDeleted(null, file, file.name, file.parentFile)
    }

    fun renamed(file: KestraVirtualFile, newName: String) {
        val oldName = file.name
        fireBeforePropertyChange(null, file, VirtualFile.PROP_NAME, oldName, newName)
        val oldPrefix = file.path
        val newRelative = NamespacePaths.join(NamespacePaths.parent(file.relativePath), newName)
        // Every known file under it moves with it.
        files.entries.filter { it.key == oldPrefix || it.key.startsWith("$oldPrefix/") }.forEach { (key, moved) ->
            files.remove(key)
            moved.relativePath = newRelative + moved.relativePath.removePrefix(file.relativePath)
            files[moved.path] = moved
        }
        file.parentFile?.let { parent -> parent.loadedChildren = parent.loadedChildren?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }
        firePropertyChanged(null, file, VirtualFile.PROP_NAME, oldName, newName)
    }

    private fun forget(file: KestraVirtualFile) {
        files.entries.removeIf { (key, known) -> (key == file.path || key.startsWith(file.path + "/")).also { if (it) known.valid = false } }
    }

    // The instance a file belongs to must still be the configured one.
    private fun api(file: KestraVirtualFile): NamespaceFilesApi {
        val current = currentInstance()
        if (file.instance != current) {
            throw IOException("${file.presentableUrl} belongs to ${file.instance}, but the IDE is now connected to ${current ?: "no instance"}. Open it again from the Kestra Namespaces tool window.")
        }
        return NamespaceFilesApi()
    }

    private fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: KestraApiException) {
        throw IOException(e.message, e)
    }

    override fun deleteFile(requestor: Any?, file: VirtualFile) = unsupported()

    override fun moveFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile) = unsupported()

    override fun renameFile(requestor: Any?, file: VirtualFile, newName: String) = unsupported()

    override fun createChildFile(requestor: Any?, parent: VirtualFile, file: String): VirtualFile = unsupported()

    override fun createChildDirectory(requestor: Any?, parent: VirtualFile, dir: String): VirtualFile = unsupported()

    override fun copyFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile = unsupported()

    private fun unsupported(): Nothing = throw IOException("Use the Kestra Namespaces tool window to change namespace files.")

    companion object {
        const val PROTOCOL = "kestra"

        // The VS Code extension refuses these too: tools would otherwise write their metadata to the namespace.
        val HIDDEN = setOf(".git", ".vscode", ".idea")

        val instance: KestraFileSystem get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as KestraFileSystem

        // host:port of the configured instance, plus ~tenant on a multi-tenant one.
        fun currentInstance(): String? {
            val settings = KestraSettings.instance
            val base = KestraUrls.apiBase(settings.url).ifEmpty { return null }
            val host = base.substringAfter("://").substringBefore('/')
            return host + settings.tenant.takeIf { it.isNotEmpty() }?.let { "~$it" }.orEmpty()
        }
    }
}
