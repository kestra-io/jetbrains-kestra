package io.kestra.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.schema.KestraSchemaService
import io.kestra.jetbrains.vfs.UnsavedCopies

// Fetches the instance's flow schema the first time a project opens with an instance configured.
class KestraStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        UnsavedCopies.report(project)
        val schema = KestraSchemaService.instance
        if (KestraApi.instance.isConfigured && !schema.hasSchema()) {
            schema.downloadQuietly()
        }
    }
}
