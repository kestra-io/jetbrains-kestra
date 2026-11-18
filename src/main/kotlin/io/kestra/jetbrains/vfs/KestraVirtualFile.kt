package io.kestra.jetbrains.vfs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
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

    // True from the moment the platform asked for the content and got an error until the content reached the
    // editor. LoadTextUtil.loadText swallows that error (it logs at debug level) and hands the editor an empty
    // text, so the Document is blank and cached: it never asks again. While this is set the file refuses to
    // be written, a save would replace the instance's file with that blank text.
    @Volatile internal var unread = false

    private val gone = Condition<Any?> { !valid }

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
        try {
            return read()
        } catch (e: IOException) {
            markUnread()
            throw e
        }
    }

    // EditorsSplitters restores a tab by calling this on a background coroutine, outside any read action, to
    // detect the charset, and logs any exception other than FileTooBigException/cancellation as a plugin error.
    // Its result is discarded and the editor reads the content again afterwards (through the no-argument
    // method, where a failure is swallowed and handled by markUnread), so a failure here is not reported.
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
    // editor is reloaded once it landed, see loaded.
    private fun read(): ByteArray {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread && !app.isReadAccessAllowed) return loaded(fs.read(this))
        try {
            return loaded(prefetch().get(UI_THREAD_WAIT_SECONDS, TimeUnit.SECONDS))
        } catch (e: TimeoutException) {
            throw IOException("$location is still loading from the instance.")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message, e.cause)
        }
    }

    // The editor is about to show a blank text for this file: keep it from being typed into, as the text could
    // only be saved over the real content.
    private fun markUnread() {
        unread = true
        // Not guarded by the flag: it may have been set by a read that no document was waiting for.
        ApplicationManager.getApplication().invokeLater({
            val documents = FileDocumentManager.getInstance()
            val document = documents.getCachedDocument(this) ?: return@invokeLater
            if (!unread || !document.isWritable || documents.isDocumentUnsaved(document)) return@invokeLater
            document.setReadOnly(true)
            if (fs.isForeign(this)) {
                KestraNotifier.info(null, "$location is shown empty and read-only: the IDE is connected to another instance. Open the file again from the Kestra Namespaces tool window.")
            } else {
                KestraNotifier.warn(null, "$location could not be read from the instance yet: it stays read-only and opens by itself once it is read.")
            }
        }, gone)
        // The read may have landed between the failure and now, in which case loaded did not see the flag.
        if (content != null) ApplicationManager.getApplication().invokeLater(::reviveDocument, gone)
    }

    // The content came in. A save or a newer listing may have set it meanwhile, which then wins.
    private fun loaded(bytes: ByteArray): ByteArray {
        val current = content ?: bytes.also { content = it }
        if (unread) ApplicationManager.getApplication().invokeLater(::reviveDocument, gone)
        return current
    }

    // EDT. Replaces the blank text of a document created while the content was not readable.
    private fun reviveDocument() {
        if (!unread) return
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(this)
        when {
            document == null -> unread = false
            !documents.isDocumentUnsaved(document) -> {
                unread = false
                reload(document)
            }
            // Typed into the blank text before it was locked: keep that text, it is the user's call.
            else -> NamespaceSaver.instance.offerReload(this)
        }
    }

    // EDT. Replaces the editor's text with the instance's content, dropping what was typed.
    internal fun reloadDocument() {
        unread = false
        FileDocumentManager.getInstance().getCachedDocument(this)?.let(::reload)
    }

    // FileDocumentManagerImpl.reloadFromDisk leaves the document as writable as it found it, so the lock set by
    // markUnread has to come off first.
    private fun reload(document: com.intellij.openapi.editor.Document) {
        document.setReadOnly(false)
        FileDocumentManager.getInstance().reloadFromDisk(document)
    }

    // Forgets the local content and reads the instance's again, then shows it in the editor.
    internal fun reloadFromInstance() {
        content = null
        prefetch().whenComplete { bytes, _ ->
            if (bytes != null) ApplicationManager.getApplication().invokeLater(::reloadDocument, gone)
        }
    }

    // Starts reading the content in the background, once.
    internal fun prefetch(): CompletableFuture<ByteArray> {
        loading?.let { return it }
        return synchronized(this) {
            loading ?: CompletableFuture.supplyAsync({ fs.read(this) }, AppExecutorUtil.getAppExecutorService()).also { future ->
                loading = future
                future.whenComplete { bytes, _ ->
                    if (bytes != null) loaded(bytes)
                    loading = null
                }
            }
        }
    }

    override fun getInputStream(): InputStream = contentsToByteArray().inputStream()

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        if (directory) throw IOException("$location is a folder")
        if (unread) {
            throw IOException("$location was not read from the instance before it was opened, so the editor holds a blank text and saving it would overwrite the file on the instance. Reload it from the instance first.")
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
        private val LOG = Logger.getInstance(KestraVirtualFile::class.java)
    }
}
