package io.kestra.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import io.kestra.jetbrains.namespace.KestraNamespacesPanel
import io.kestra.jetbrains.namespace.KestraNamespacesToolWindowFactory

// Shows the Kestra Namespaces tool window and asks which namespace to open in it.
class OpenNamespaceAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow(KestraNamespacesToolWindowFactory.ID) ?: return
        window.activate {
            (window.contentManager.contents.firstOrNull()?.component as? KestraNamespacesPanel)?.openNamespace()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
