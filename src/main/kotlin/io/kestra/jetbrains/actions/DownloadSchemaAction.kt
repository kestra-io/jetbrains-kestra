package io.kestra.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.schema.KestraSchemaService

// Forces a refresh, for example after installing a plugin on the instance.
class DownloadSchemaAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        object : Task.Backgroundable(project, "Downloading Kestra flow schema", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    KestraSchemaService.instance.download()
                    KestraNotifier.info(project, "Downloaded the Kestra flow schema from ${KestraApi.origin(KestraApi.instance.apiBase())}.")
                } catch (e: KestraUnauthorizedException) {
                    KestraNotifier.signInRequired(project, e.message.orEmpty())
                } catch (e: KestraApiException) {
                    KestraNotifier.error(project, e.message.orEmpty())
                }
            }
        }.queue()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
