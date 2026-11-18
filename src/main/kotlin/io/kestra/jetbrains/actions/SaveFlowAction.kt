package io.kestra.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.flow.FlowYaml

// Deploys the editor buffer without running it.
class SaveFlowAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = file != null && FlowYaml.isFlow(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        // Namespace and id come from the same text as the body: the PSI may not be committed after an edit of the id.
        val source = editor?.document?.text ?: file.text
        val flow = FlowYaml.parse(file.project, source) ?: return
        object : Task.Backgroundable(project, "Saving ${flow.uid} to Kestra", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val response = KestraApi.instance.upsertFlow(flow.namespace, flow.id, source)
                    if (!response.ok) {
                        KestraNotifier.error(project, "Failed to save ${flow.uid} (HTTP ${response.status}): ${response.message()}")
                        return
                    }
                    val revision = response.json()?.asJsonObject?.get("revision")?.asString
                    KestraNotifier.info(project, "Saved ${flow.uid} to Kestra${revision?.let { " (revision $it)" }.orEmpty()}.")
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
