package io.kestra.jetbrains.vfs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.LocalTimeCounter
import com.intellij.util.concurrency.AppExecutorUtil
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.namespace.NamespacePaths
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// A file or folder of a namespace on an instance. Its content lives in memory, never on disk: it is
// read from the instance on first access, and a save keeps the new content and uploads it in the background.
// The content is kept until the upload succeeded, see NamespaceSaver.
//
// The one rule that keeps a blank or stale text from reaching the instance: a file is writable only while it
// holds content. A document made from a file without content is read-only (FileDocumentManagerBase.getDocument
// asks isWritable), so nothing can be typed into it and saved over the instance's file; when the content lands the
// document is unlocked and shows it, see contentLanded.
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
    private val retrying = AtomicBoolean()
    // The reason the document is blank was told to the user, once until the content lands.
    @Volatile private var explained = false

    private val gone = Condition<Any?> { !valid }

    val isRoot: Boolean get() = relativePath == "/"

    val isLoaded: Boolean get() = loadedChildren != null

    // False until the content was read, and again once the instance reported a newer version: the next read fetches it.
    val hasContent: Boolean get() = content != null

    // Typed and not saved yet, or saved and not on the instance (yet, or ever): text that only this IDE has.
    internal val hasLocalChanges: Boolean
        get() {
            val documents = FileDocumentManager.getInstance()
            return documents.getCachedDocument(this)?.let(documents::isDocumentUnsaved) == true || NamespaceSaver.instance.hasUnsyncedChanges(this)
        }

    override fun getName(): String = if (isRoot) namespace else NamespacePaths.name(relativePath)

    override fun getFileSystem(): KestraFileSystem = fs

    override fun getPath(): String = "$instance/$namespace" + if (isRoot) "" else relativePath

    // namespace/path on the instance, for messages and titles.
    val location: String get() = "$namespace${if (isRoot) "" else relativePath} on ${KestraFileSystem.describe(instance)}"

    override fun isWritable(): Boolean = !directory && content != null

    override fun isDirectory(): Boolean = directory

    override fun isValid(): Boolean = valid

    override fun getParent(): VirtualFile? = parentFile

    override fun getChildren(): Array<VirtualFile> = loadedChildren?.toTypedArray() ?: EMPTY_ARRAY

    override fun contentsToByteArray(): ByteArray {
        if (directory) throw IOException("$location is a folder")
        content?.let { return it }
        try {
            return read()
        } catch (e: IOException) {
            explainBlankDocument()
            throw e
        }
    }

    // EditorsSplitters restores a tab by calling this on a background coroutine, outside any read action, to
    // detect the charset, and logs any exception other than FileTooBigException/cancellation as a plugin error.
    // Its result is discarded and the editor reads the content again afterwards (through the no-argument
    // method, where a failure is swallowed and explained), so a failure here is not reported.
    // The empty array is only ever that discarded result: every other caller of this class uses the
    // no-argument method and still gets the IOException.
    override fun contentsToByteArray(cacheContent: Boolean): ByteArray {
        if (!cacheContent || directory) return contentsToByteArray()
        content?.let { return it }
        return try {
            read()
        } catch (e: IOException) {
            LOG.info("$location could not be read while its tab was restored: ${e.message}")
            ByteArray(0)
        }
    }

    // A tab restored at startup is read by the editor on the EDT or under a read action, where a slow
    // instance must not hold the IDE: wait a short while for the read, then give up. It keeps running, and the
    // document is unlocked and filled once it landed, see contentLanded.
    private fun read(): ByteArray {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread && !app.isReadAccessAllowed) return fetchAndRetry()
        try {
            return loaded(prefetch().get(UI_THREAD_WAIT_SECONDS, TimeUnit.SECONDS))
        } catch (e: TimeoutException) {
            throw IOException("$location is still loading from the instance.")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message, e.cause)
        }
    }

    // The content, read from the instance when it is not here. Background threads only, it waits for the network:
    // the way to ask for a read that reports its failure.
    internal fun load(): ByteArray {
        content?.let { return it }
        try {
            return loaded(prefetch().get())
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message, e.cause)
        }
    }

    private fun fetch(): ByteArray = loaded(fs.read(this))

    // A read that failed starts to be tried again, see retryLater.
    private fun fetchAndRetry(): ByteArray = try {
        fetch()
    } catch (e: IOException) {
        retryLater()
        throw e
    }

    // The content came in. A save or a newer listing may have set it meanwhile, which then wins.
    private fun loaded(bytes: ByteArray): ByteArray {
        val current = content ?: bytes.also { content = it }
        if (current === bytes) {
            explained = false
            ApplicationManager.getApplication().invokeLater(::contentLanded, gone)
        }
        return current
    }

    // EDT. Unlocks the document made while the content was missing and shows the content in it, unless that
    // document holds typed text. The VFS "writable" event cannot do this: DeprecatedVirtualFileSystem.fire*
    // only reaches this file system's own listeners, and FileDocumentManagerImpl hears property changes of the
    // VFS_CHANGES events alone.
    private fun contentLanded() {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(this) ?: return
        val bytes = content ?: return
        if (!document.isWritable) document.setReadOnly(false)
        if (documents.isDocumentUnsaved(document)) return
        // Not when it is the same text: a flow is read again at every refresh, and a reload moves the caret.
        if (!Comparing.equal(document.charsSequence, LoadTextUtil.getTextByBinaryPresentation(bytes, this))) documents.reloadFromDisk(document)
    }

    // The instance has a newer version: forget the content, so the next read fetches it, and lock the open
    // document until it does. Unless this IDE holds text the instance does not have. Decided on the EDT, where
    // typing happens: an edit made a moment ago is seen, and the document never ends up edited and unlocked
    // without content.
    internal fun invalidate() {
        if (directory) return
        onEdt {
            if (hasLocalChanges) return@onEdt
            content = null
            FileDocumentManager.getInstance().getCachedDocument(this)?.setReadOnly(true)
        }
    }

    // EDT. The instance's version replaces whatever the editor holds, typed text included: the user's discard.
    internal fun adopt(bytes: ByteArray) {
        content = bytes
        explained = false
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(this)?.let {
            it.setReadOnly(false)
            documents.reloadFromDisk(it)
        }
    }

    // The editor shows a blank text for a file that could not be read (LoadTextUtil.loadText swallows the error
    // and logs it at debug level): it is locked, say why.
    private fun explainBlankDocument() {
        val foreign = fs.isForeign(this)
        ApplicationManager.getApplication().invokeLater({
            // Once, and only for a file an editor shows: the platform may read a file that has no document.
            if (explained || content != null || FileDocumentManager.getInstance().getCachedDocument(this) == null) return@invokeLater
            explained = true
            if (foreign) {
                KestraNotifier.info(null, "$location is shown empty and read-only: the IDE is connected to another instance. Open the file again from the Kestra Namespaces tool window.")
            } else {
                KestraNotifier.warn(null, "$location could not be read from the instance yet: it stays read-only, and is read again until it opens.")
            }
        }, gone)
    }

    // A failed read is tried again by itself, so that a file that opened blank does not stay locked until the user
    // thinks of Refresh: a few seconds later, then less often. It stops when the content landed, the file left, nothing
    // shows it any more (the next document asks again), or it can never succeed (another instance).
    private fun retryLater(attempt: Int = 0) {
        if (attempt == 0 && (fs.isForeign(this) || !retrying.compareAndSet(false, true))) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().executeOnPooledThread {
                val wanted = content == null && valid && !fs.isForeign(this) && FileDocumentManager.getInstance().getCachedDocument(this) != null
                if (!wanted) {
                    retrying.set(false)
                    return@executeOnPooledThread
                }
                try {
                    fetch()
                    retrying.set(false)
                } catch (e: Exception) {
                    retryLater(attempt + 1)
                }
            }
        }, RETRY_SECONDS[minOf(attempt, RETRY_SECONDS.size - 1)], TimeUnit.SECONDS)
    }

    private fun onEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block, gone)
    }

    // Starts reading the content in the background, once.
    internal fun prefetch(): CompletableFuture<ByteArray> {
        loading?.let { return it }
        return synchronized(this) {
            loading ?: CompletableFuture.supplyAsync({ fetchAndRetry() }, AppExecutorUtil.getAppExecutorService()).also { future ->
                loading = future
                future.whenComplete { _, _ -> loading = null }
            }
        }
    }

    override fun getInputStream(): InputStream = contentsToByteArray().inputStream()

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        if (directory) throw IOException("$location is a folder")
        // A document is read-only without content, see isWritable. This is for a save that is forced some other way.
        if (content == null) {
            throw IOException("$location was not read from the instance, so saving would overwrite its file there with a text that is not its content.")
        }
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
        val RETRY_SECONDS = longArrayOf(2, 5, 15, 30, 60)
        private val LOG = Logger.getInstance(KestraVirtualFile::class.java)
    }
}
