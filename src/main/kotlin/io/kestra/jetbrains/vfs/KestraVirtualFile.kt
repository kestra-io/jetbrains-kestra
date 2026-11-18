package io.kestra.jetbrains.vfs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.LocalTimeCounter
import com.intellij.util.concurrency.AppExecutorUtil
import io.kestra.jetbrains.namespace.NamespacePaths
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// A file or folder of a namespace on an instance. Its content lives in memory, never on disk: it is
// read from the instance on first access, and a save keeps the new content and uploads it in the background.
// The content is kept until the upload succeeded, see NamespaceSaver.
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
    @Volatile private var loading: CompletableFuture<ByteArray>? = null

    val isRoot: Boolean get() = relativePath == "/"

    val isLoaded: Boolean get() = loadedChildren != null

    // False once the instance reported a newer version: the next read fetches it.
    val hasContent: Boolean get() = content != null

    override fun getName(): String = if (isRoot) namespace else NamespacePaths.name(relativePath)

    override fun getFileSystem(): KestraFileSystem = fs

    override fun getPath(): String = "$instance/$namespace" + if (isRoot) "" else relativePath

    // namespace/path on the instance, for messages and titles.
    val location: String get() = "$namespace${if (isRoot) "" else relativePath} on ${KestraFileSystem.describe(instance)}"

    override fun isWritable(): Boolean = !directory

    override fun isDirectory(): Boolean = directory

    override fun isValid(): Boolean = valid

    override fun getParent(): VirtualFile? = parentFile

    override fun getChildren(): Array<VirtualFile> = loadedChildren?.toTypedArray() ?: EMPTY_ARRAY

    override fun contentsToByteArray(): ByteArray {
        if (directory) throw IOException("$location is a folder")
        content?.let { return it }
        // A tab restored at startup is read by the editor on the EDT or under a read action, where a slow
        // instance must not hold the IDE: wait a short while for the read, then give up. It keeps running, so
        // the file opens from the Namespaces tool window once it landed.
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread && !app.isReadAccessAllowed) return fs.read(this).also { content = it }
        try {
            return prefetch().get(UI_THREAD_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IOException("$location is still loading from the instance. Open it again from the Kestra Namespaces tool window.")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message, e.cause)
        }
    }

    // Starts reading the content in the background, once.
    internal fun prefetch(): CompletableFuture<ByteArray> {
        loading?.let { return it }
        return synchronized(this) {
            loading ?: CompletableFuture.supplyAsync({ fs.read(this) }, AppExecutorUtil.getAppExecutorService()).also { future ->
                loading = future
                future.whenComplete { bytes, _ ->
                    // A save or a newer listing may have set the content meanwhile.
                    if (bytes != null && content == null) content = bytes
                    loading = null
                }
            }
        }
    }

    override fun getInputStream(): InputStream = contentsToByteArray().inputStream()

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        if (directory) throw IOException("$location is a folder")
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

    private companion object {
        const val UI_THREAD_WAIT_SECONDS = 3L
    }
}
