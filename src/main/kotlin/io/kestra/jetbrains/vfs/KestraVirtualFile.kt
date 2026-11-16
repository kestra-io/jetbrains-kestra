package io.kestra.jetbrains.vfs

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.LocalTimeCounter
import io.kestra.jetbrains.namespace.NamespacePaths
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// A file or folder of a namespace on an instance. Its content lives in memory, never on disk: it is
// read from the instance on first access, and a save keeps the new content and uploads it in the background.
class KestraVirtualFile internal constructor(
    private val fs: KestraFileSystem,
    val instance: String,
    val namespace: String,
    relativePath: String,
    private val directory: Boolean,
    parent: KestraVirtualFile?,
) : VirtualFile() {
    @Volatile var relativePath: String = relativePath
        internal set
    @Volatile internal var parentFile: KestraVirtualFile? = parent
    @Volatile internal var content: ByteArray? = null
    @Volatile internal var size = 0L
    @Volatile internal var modified = 0L
    // Null until the folder is listed, which only happens in the background.
    @Volatile internal var loadedChildren: List<KestraVirtualFile>? = null
    @Volatile internal var valid = true
    @Volatile private var stamp = LocalTimeCounter.currentTime()

    val isRoot: Boolean get() = relativePath == "/"

    val isLoaded: Boolean get() = loadedChildren != null

    // False once the instance reported a newer version: the next read fetches it.
    val hasContent: Boolean get() = content != null

    override fun getName(): String = if (isRoot) namespace else NamespacePaths.name(relativePath)

    override fun getFileSystem(): KestraFileSystem = fs

    override fun getPath(): String = "$instance/$namespace" + if (isRoot) "" else relativePath

    override fun getPresentableUrl(): String = "$namespace${if (isRoot) "" else relativePath} on $instance"

    override fun isWritable(): Boolean = !directory

    override fun isDirectory(): Boolean = directory

    override fun isValid(): Boolean = valid

    override fun getParent(): VirtualFile? = parentFile

    override fun getChildren(): Array<VirtualFile> = loadedChildren?.toTypedArray() ?: EMPTY_ARRAY

    override fun contentsToByteArray(): ByteArray {
        if (directory) throw IOException("$presentableUrl is a folder")
        return content ?: fs.read(this).also { content = it }
    }

    override fun getInputStream(): InputStream = contentsToByteArray().inputStream()

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        if (directory) throw IOException("$presentableUrl is a folder")
        return object : ByteArrayOutputStream() {
            override fun close() {
                val bytes = toByteArray()
                content = bytes
                stamp = if (newModificationStamp >= 0) newModificationStamp else LocalTimeCounter.currentTime()
                if (newTimeStamp > 0) modified = newTimeStamp
                NamespaceSaver.instance.save(this@KestraVirtualFile, bytes)
            }
        }
    }

    override fun getModificationStamp(): Long = stamp

    override fun getTimeStamp(): Long = modified

    override fun getLength(): Long = content?.size?.toLong() ?: size

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }

    internal fun touch() {
        stamp = LocalTimeCounter.currentTime()
    }
}
