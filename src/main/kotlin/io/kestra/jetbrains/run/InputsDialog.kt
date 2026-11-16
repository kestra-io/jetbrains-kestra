package io.kestra.jetbrains.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.flow.FlowInput
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

// Collects the flow inputs before an execution starts.
class InputsDialog(private val project: Project, flowUid: String, private val inputs: List<FlowInput>) : DialogWrapper(project) {
    private val fields: Map<FlowInput, JComponent> = inputs.associateWith(::fieldFor)

    init {
        title = "Inputs for $flowUid"
        setOKButtonText("Run")
        init()
    }

    private fun fieldFor(input: FlowInput): JComponent = when {
        input.type == "FILE" -> TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Select File for ${input.id}"))
        }
        input.type == "SECRET" -> JBPasswordField().apply { text = input.defaultValue }
        input.type in setOf("BOOLEAN", "BOOL") -> ComboBox(arrayOf("true", "false")).apply {
            selectedItem = input.defaultValue.lowercase().takeIf { it == "true" || it == "false" } ?: "false"
        }
        input.type in setOf("SELECT", "ENUM") && input.values.isNotEmpty() -> ComboBox(input.values.toTypedArray()).apply {
            selectedItem = input.defaultValue.takeIf { it in input.values } ?: input.values.first()
        }
        else -> JBTextField(input.defaultValue)
    }

    private fun valueOf(component: JComponent): String = when (component) {
        is TextFieldWithBrowseButton -> component.text
        is JBPasswordField -> String(component.password)
        is ComboBox<*> -> component.selectedItem?.toString().orEmpty()
        is JBTextField -> component.text
        else -> ""
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
        for ((input, field) in fields) {
            val label = input.id + (if (input.required) " *" else "")
            form.addLabeledComponent(label, field)
            form.addTooltip(input.type)
        }
        // Keeps the fields at the top when the scroll pane is taller than the form.
        val panel = form.addComponentFillVertically(JPanel(), 0).panel
        panel.border = JBUI.Borders.empty(4)
        return JBScrollPane(panel).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(480, minOf(80 + inputs.size * 64, 520))
        }
    }

    override fun doValidateAll(): List<ValidationInfo> = fields.mapNotNull { (input, field) ->
        val value = valueOf(field)
        when {
            input.required && value.isBlank() -> ValidationInfo("${input.id} is required", field)
            input.type == "FILE" && value.isNotBlank() && !Files.isRegularFile(Path.of(value)) -> ValidationInfo("File not found", field)
            else -> null
        }
    }

    // Empty optional inputs are left out so the flow's defaults apply.
    fun parts(): List<FormPart> = fields.mapNotNull { (input, field) ->
        val value = valueOf(field)
        when {
            value.isEmpty() -> null
            input.type == "FILE" -> Path.of(value).let { FormPart.File(input.id, it.fileName.toString(), Files.readAllBytes(it)) }
            else -> FormPart.Text(input.id, value)
        }
    }
}
