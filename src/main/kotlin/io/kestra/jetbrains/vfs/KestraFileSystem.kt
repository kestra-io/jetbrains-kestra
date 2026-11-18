package io.kestra.jetbrains.vfs

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.api.NamespaceEntry
import io.kestra.jetbrains.api.NamespaceFilesApi
import io.kestra.jetbrains.namespace.NamespacePaths
import io.kestra.jetbrains.settings.KestraSettings
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

// kestra://<instance>/<namespace>/<path>: the namespace files of an instance, as the IDE's editors see them.
// The instance is the encoded API base plus the tenant, see currentInstance. It is part of the URL, so a file opened from one instance is never written to another one
// after the settings change. Changes go through the Kestra Namespaces tool window, which calls the
// instance first and then updates this model.
class KestraFileSystem : DeprecatedVirtualFileSystem() {
    private val files = ConcurrentHashMap<String, KestraVirtualFile>()

    init {
        startEventPropagation()
    }

    override fun getProtocol() = PROTOCOL

    // Also called to reopen editor tabs on startup, before anything was listed: the file is created
    // from its path and read on first access. The read starts right away in the background, so it is
    // usually done when the editor asks for the content.
    override fun findFileByPath(path: String): VirtualFile? {
        files[path]?.let { return it }
        val segments = path.trim('/').split('/')
        if (segments.size < 2 || segments.any { it.isEmpty() }) return null
        var current = root(segments[0], segments[1])
        for ((index, name) in segments.drop(2).withIndex()) {
            current = child(current, name, directory = index < segments.size - 3)
        }
        if (!current.isDirectory && !current.hasContent) current.prefetch()
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
    // The root also holds a _flows folder: the namespace's flows as <id>.yml files, as in the VS Code extension.
    fun list(dir: KestraVirtualFile): List<KestraVirtualFile> {
        val entries = if (isFlowsFolder(dir)) {
            // Flows have no modification time to compare: they are always read again.
            io { api(dir).flowIds(dir.namespace) }.map { NamespaceEntry("$it.yml", false, 0, 0) }
        } else {
            io { api(dir).list(dir.namespace, dir.relativePath) }.filter { it.name !in HIDDEN && !(dir.isRoot && it.name == FLOWS) } +
                listOfNotNull(NamespaceEntry(FLOWS, true, 0, 0).takeIf { dir.isRoot })
        }
        val children = entries.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() })).map { entry ->
            child(dir, entry.name, entry.directory).also {
                // A file changed on the instance is read again, unless it holds changes the instance has not accepted.
                if ((it.modified != entry.modified || isFlowsFolder(dir)) && !NamespaceSaver.instance.hasUnsyncedChanges(it)) it.content = null
                it.size = entry.size
                it.modified = entry.modified
            }
        }
        // What left the instance is forgotten, unless it holds changes the instance has not accepted: they stay
        // until the user discards them, or a save goes through (which recreates the file).
        val gone = dir.loadedChildren.orEmpty().filter { it !in children }
        val kept = gone.filter(::holdsUnsyncedChanges)
        (gone - kept.toSet()).forEach(::forget)
        val all = if (kept.isEmpty()) children else (children + kept).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        dir.loadedChildren = all
        return all
    }

    internal fun read(file: KestraVirtualFile): ByteArray = io {
        if (isFlow(file)) api(file).flowSource(file.namespace, flowId(file)).toByteArray() else api(file).read(file.namespace, file.relativePath)
    }

    // A flow is saved through the flows API, which validates it and reports why it refuses it.
    internal fun write(file: KestraVirtualFile, content: ByteArray) = io {
        if (isFlow(file)) api(file).saveFlow(file.namespace, flowId(file), String(content, Charsets.UTF_8))
        else api(file).write(file.namespace, file.relativePath, content)
    }

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

    fun isFlowsFolder(file: KestraVirtualFile) = file.relativePath == "/$FLOWS"

    fun isFlow(file: KestraVirtualFile) = !file.isDirectory && NamespacePaths.parent(file.relativePath) == "/$FLOWS"

    fun flowId(file: KestraVirtualFile) = file.name.substringBeforeLast('.')

    // The file, or a file under it when it is a folder.
    private fun holdsUnsyncedChanges(file: KestraVirtualFile) =
        NamespaceSaver.instance.unsyncedFiles().any { it === file || it.path.startsWith(file.path + "/") }

    private fun forget(file: KestraVirtualFile) {
        files.entries.removeIf { (key, known) ->
            (key == file.path || key.startsWith(file.path + "/")).also {
                if (it) {
                    known.valid = false
                    NamespaceSaver.instance.discard(known)
                }
            }
        }
    }

    internal fun isForeign(file: KestraVirtualFile) = file.instance != currentInstance()

    // The instance a file belongs to must still be the configured one.
    private fun api(file: KestraVirtualFile): NamespaceFilesApi {
        val current = currentInstance()
        if (file.instance != current) {
            throw IOException("${file.location} belongs to ${describe(file.instance)}, but the IDE is now connected to ${current?.let(::describe) ?: "no instance"}. Open it again from the Kestra Namespaces tool window.")
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
        const val FLOWS = "_flows"

        // Tools would otherwise write their metadata to the namespace. The VS Code extension hides only .git and
        // .vscode; .idea is hidden here for the same reason, as this IDE's own folder.
        val HIDDEN = setOf(".git", ".vscode", ".idea")

        val instance: KestraFileSystem get() = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as KestraFileSystem

        // The configured API base, with the tenant on a multi-tenant instance, as one URL segment. The whole
        // base, not only host:port: https://x/prod and https://x/staging are two instances. "~" separates the
        // tenant, so the one in the base is escaped.
        fun currentInstance(): String? {
            val settings = KestraSettings.instance
            val base = KestraUrls.apiBase(settings.url).ifEmpty { return null }
            return KestraUrls.segment(base).replace("~", "%7E") + settings.tenant.takeIf { it.isNotEmpty() }?.let { "~" + KestraUrls.segment(it) }.orEmpty()
        }

        // The instance of a path, as the user typed it: "https://x/prod/api/v1" or "... (tenant)".
        fun describe(instance: String): String {
            fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8)
            val tenant = instance.substringAfter('~', "")
            return decode(instance.substringBefore('~')) + if (tenant.isEmpty()) "" else " (tenant ${decode(tenant)})"
        }
    }
}
