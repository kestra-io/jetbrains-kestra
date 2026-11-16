package io.kestra.jetbrains.namespace

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.api.NamespaceFilesApi
import javax.swing.JComponent

// Asks for a namespace (listed from the instance, or typed) and, when given, a target path.
class NamespaceTargetDialog(
    project: Project?,
    title: String,
    private val namespaces: List<String>,
    initialPath: String?,
    private val requireFileName: Boolean,
    private val note: String?,
) : DialogWrapper(project) {
    private val namespace = ComboBox(CollectionComboBoxModel(namespaces)).apply {
        isEditable = true
        selectedItem = PropertiesComponent.getInstance().getValue(LAST_NAMESPACE)?.takeIf { it in namespaces } ?: namespaces.firstOrNull().orEmpty()
    }
    private val path = initialPath?.let(::JBTextField)

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Namespace:", namespace)
        .apply { path?.let { addLabeledComponent(if (requireFileName) "Target path:" else "Base path:", it) } }
        .apply { note?.let { addComponent(JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) } }
        .panel

    override fun getPreferredFocusedComponent(): JComponent = namespace

    override fun doValidate(): ValidationInfo? {
        if (selectedNamespace().isEmpty()) return ValidationInfo("Enter a namespace", namespace)
        val field = path ?: return null
        return NamespacePaths.validate(field.text, requireFileName)?.let { ValidationInfo(it, field) }
    }

    fun selectedNamespace(): String = (namespace.editor.item as? String ?: namespace.selectedItem as? String).orEmpty().trim()

    fun targetPath(): String = path?.text?.trim().orEmpty()

    // The instance lists the namespaces that have flows: a typo would silently create a new one.
    fun confirmNew(project: Project?): Boolean {
        val chosen = selectedNamespace()
        PropertiesComponent.getInstance().setValue(LAST_NAMESPACE, chosen)
        return chosen in namespaces || Messages.showOkCancelDialog(
            project, "No flow uses namespace \"$chosen\", it may not exist yet. Use it anyway?", "Kestra Namespace", "Use It", Messages.getCancelButton(), null,
        ) == Messages.OK
    }

    companion object {
        private const val LAST_NAMESPACE = "kestra.namespace.last"

        // Lists the namespaces under a modal progress. Null when the user must sign in first; an
        // empty list when the instance could not list them, the user can still type one.
        fun loadNamespaces(project: Project?): List<String>? = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, Exception>(
                { NamespaceFilesApi().namespaces() }, "Listing Kestra namespaces", true, project,
            )
        } catch (e: KestraUnauthorizedException) {
            KestraNotifier.signInRequired(project, "${e.origin} requires authentication. Sign in to reach its namespaces.")
            null
        } catch (e: KestraApiException) {
            KestraNotifier.warn(project, e.message.orEmpty())
            emptyList()
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            null
        }
    }
}
