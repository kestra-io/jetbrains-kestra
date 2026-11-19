package io.kestra.jetbrains.schema

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.util.io.DigestUtil
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.settings.KestraSettings
import java.io.StringReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// The flow schema of the configured instance, so completion matches its installed plugin versions.
// Cached on disk, one file for the configured instance URL: the schema of an instance used before is deleted.
@Service(Service.Level.APP)
class KestraSchemaService {
    private val directory: Path get() = PathManager.getSystemDir().resolve("kestra")

    private fun path(apiBase: String): Path = directory.resolve("flow-schema-${DigestUtil.sha1Hex(apiBase.toByteArray()).take(16)}.json")

    private fun currentPath(): Path? = KestraUrls.apiBase(KestraSettings.instance.url).takeIf { it.isNotEmpty() }?.let(::path)

    fun schemaFile(): VirtualFile? {
        val path = currentPath()?.takeIf { Files.isRegularFile(it) } ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    fun hasSchema(): Boolean = currentPath()?.let { Files.isRegularFile(it) } == true

    // Blocking, call from a background thread. Throws when the download fails, and then keeps the schema already
    // there: a file that exists is never a partial or foreign one, as hasSchema() trusts it and nothing downloads it
    // again on startup. One download at a time: they share the temporary files.
    @Synchronized
    fun download() {
        val api = KestraApi.instance
        val path = path(api.apiBase())
        val response = api.flowSchema()
        if (!response.ok) {
            throw KestraApiException("Downloading the flow schema failed (HTTP ${response.status}): ${response.message()}")
        }
        // A proxy's login page or a cut-off body would otherwise replace a working schema.
        if (!isJsonObject(response.body)) {
            throw KestraApiException("Downloading the flow schema failed: ${api.apiBase()} did not answer with a JSON schema.")
        }
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "flow-schema-", ".tmp")
        try {
            Files.writeString(temp, response.body)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        deleteOthers(path)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)?.refresh(false, true)
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                // reset() also restarts the highlighting of the project's open files.
                if (!project.isDisposed) JsonSchemaService.Impl.get(project).reset()
            }
        }
    }

    // The schemas of other instances (about 20 MB each) and the temporary files of an interrupted download.
    private fun deleteOthers(kept: Path) {
        Files.newDirectoryStream(directory, "flow-schema-*").use { files ->
            files.filter { it != kept }.forEach { runCatching { Files.deleteIfExists(it) }.onFailure { e -> LOG.info("Could not delete $it: ${e.message}") } }
        }
    }

    // Streams through the body without building it in memory: the schema is tens of megabytes.
    private fun isJsonObject(body: String): Boolean = try {
        JsonReader(StringReader(body)).use { reader ->
            reader.peek() == JsonToken.BEGIN_OBJECT && run {
                reader.skipValue()
                reader.peek() == JsonToken.END_DOCUMENT
            }
        }
    } catch (_: Exception) {
        false
    }

    fun downloadQuietly() {
        if (!KestraApi.instance.isConfigured) {
            return
        }
        try {
            download()
        } catch (e: Exception) {
            LOG.info("Kestra flow schema not downloaded: ${e.message}")
        }
    }

    companion object {
        private val LOG = logger<KestraSchemaService>()

        val instance: KestraSchemaService get() = service()
    }
}
