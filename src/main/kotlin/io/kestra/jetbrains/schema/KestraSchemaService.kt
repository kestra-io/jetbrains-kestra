package io.kestra.jetbrains.schema

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.DigestUtil
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.settings.KestraSettings
import java.nio.file.Files
import java.nio.file.Path

// The flow schema of the configured instance, so completion matches its installed plugin versions.
// Cached on disk per instance URL.
@Service(Service.Level.APP)
class KestraSchemaService {
    private fun path(apiBase: String): Path =
        PathManager.getSystemDir().resolve("kestra").resolve("flow-schema-${DigestUtil.sha1Hex(apiBase.toByteArray()).take(16)}.json")

    private fun currentPath(): Path? = KestraUrls.apiBase(KestraSettings.instance.url).takeIf { it.isNotEmpty() }?.let(::path)

    fun schemaFile(): VirtualFile? {
        val path = currentPath()?.takeIf { Files.isRegularFile(it) } ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    fun hasSchema(): Boolean = currentPath()?.let { Files.isRegularFile(it) } == true

    // Blocking, call from a background thread. Throws when the download fails.
    fun download() {
        val api = KestraApi.instance
        val path = path(api.apiBase())
        val response = api.flowSchema()
        if (!response.ok) {
            throw KestraApiException("Downloading the flow schema failed (HTTP ${response.status}): ${response.message()}")
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, response.body)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.refresh(false, false)
        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) {
                    JsonSchemaService.Impl.get(project).reset()
                    DaemonCodeAnalyzer.getInstance(project).restart("Kestra flow schema downloaded")
                }
            }
        }
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
