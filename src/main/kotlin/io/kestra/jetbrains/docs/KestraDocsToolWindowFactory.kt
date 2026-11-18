package io.kestra.jetbrains.docs

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import io.kestra.jetbrains.flow.FlowYaml
import io.kestra.jetbrains.jcef.JcefView
import org.jetbrains.yaml.psi.YAMLFile

// The Kestra Documentation tool window. While it is shown, placing the cursor on a task in a YAML file
// shows that plugin's documentation.
class KestraDocsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (JcefView.showUnavailable(toolWindow, "The documentation")) return
        val view = KestraDocsView()
        val content = ContentFactory.getInstance().createContent(view.component, "", false)
        Disposer.register(content, view)
        toolWindow.contentManager.addContent(content)

        val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, content)
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val editor = event.editor
                if (editor.project != project || !toolWindow.isVisible) return
                val offset = editor.caretModel.offset
                // Caret moves come at typing rate and the lookup walks the PSI.
                alarm.cancelAllRequests()
                alarm.addRequest({
                    val type = ApplicationManager.getApplication().runReadAction<String?> {
                        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? YAMLFile
                        file?.let { FlowYaml.typeAt(it, offset.coerceAtMost(it.textLength)) }
                    }
                    if (type != null) ApplicationManager.getApplication().invokeLater({ view.showPlugin(type) }, project.disposed)
                }, CURSOR_DEBOUNCE_MS)
            }
        }, content)
        ApplicationManager.getApplication().messageBus.connect(content).subscribe(LafManagerListener.TOPIC, LafManagerListener { view.updateTheme() })
    }

    companion object {
        const val ID = "Kestra Documentation"
        private const val CURSOR_DEBOUNCE_MS = 200
    }
}
