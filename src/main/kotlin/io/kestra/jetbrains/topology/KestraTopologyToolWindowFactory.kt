package io.kestra.jetbrains.topology

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.SwingConstants

class KestraTopologyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = KestraTopologyService.getInstance(project)
        // JCEF ships with the JetBrains Runtime, but a custom runtime or a remote client can lack it.
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("The topology preview needs the embedded browser (JCEF), which this IDE runtime does not provide.", SwingConstants.CENTER)
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, "", false))
            return
        }
        val view = JcefTopologyView(service::reveal)
        val content = ContentFactory.getInstance().createContent(view.component, "", false)
        Disposer.register(content, view)
        Disposer.register(content) { service.detach(view) }
        toolWindow.contentManager.addContent(content)

        project.messageBus.connect(content).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = service.setVisible(toolWindow.isVisible)
        })
        service.setVisible(toolWindow.isVisible)
        service.attach(view)
    }

    companion object {
        const val ID = "Kestra Topology"
    }
}
