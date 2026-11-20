package io.kestra.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import io.kestra.jetbrains.flow.FlowYaml
import io.kestra.jetbrains.topology.KestraTopologyService
import io.kestra.jetbrains.topology.KestraTopologyToolWindowFactory

class ShowTopologyAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Shown beside the editor without taking the focus, as VS Code opens its panel.
        ToolWindowManager.getInstance(project).getToolWindow(KestraTopologyToolWindowFactory.ID)?.activate(null, false)
        KestraTopologyService.getInstance(project).scheduleRefresh(0)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = file != null && FlowYaml.isFlow(file)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
