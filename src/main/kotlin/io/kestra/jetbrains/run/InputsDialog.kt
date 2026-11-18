package io.kestra.jetbrains.run

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.flow.FlowInput
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

// Collects the flow inputs before an execution starts.
class InputsDialog(private val project: Project, flowUid: String, private val inputs: List<FlowInput>) : DialogWrapper(project) {
    // The component shown, and the text it holds as the value of the input.
    private class Field(val component: JComponent, val value: () -> String)

    private val fields: Map<FlowInput, Field> = inputs.associateWith(::fieldFor)

    init {
        title = "Inputs for $flowUid"
        setOKButtonText("Run")
        init()
    }

    private fun fieldFor(input: FlowInput): Field = when {
        input.type == "FILE" -> TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Select File for ${input.id}"))
        }.let { field -> Field(field) { field.text } }
        input.type == "SECRET" -> JBPasswordField().apply { text = input.defaultValue }.let { field -> Field(field) { String(field.password) } }
        // As in the VS Code extension: BOOL is a plain checkbox, always sent, only BOOLEAN can be left to the flow.
        input.type == "BOOL" -> JBCheckBox().apply { isSelected = input.defaultValue.equals("true", ignoreCase = true) }
            .let { box -> Field(box) { box.isSelected.toString() } }
        // Tri-state: left on the first entry, the input is not sent and the flow's own default applies.
        input.type == "BOOLEAN" -> ComboBox(arrayOf("", "true", "false")).apply {
            renderer = SimpleListCellRenderer.create("") { it.ifEmpty { "(flow default)" } }
            selectedItem = input.defaultValue.lowercase().takeIf { it == "true" || it == "false" } ?: ""
        }.let { field -> Field(field) { field.selectedItem?.toString().orEmpty() } }
        input.type in setOf("SELECT", "ENUM") && input.values.isNotEmpty() -> ComboBox(input.values.toTypedArray()).apply {
            selectedItem = input.defaultValue.takeIf { it in input.values } ?: input.values.first()
        }.let { field -> Field(field) { field.selectedItem?.toString().orEmpty() } }
        // Sent as a JSON array, which is how Kestra reads a list input.
        input.type == "MULTISELECT" && input.values.isNotEmpty() -> JBList(input.values).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            visibleRowCount = minOf(input.values.size, 5)
            val defaults = defaultList(input.defaultValue)
            selectedIndices = input.values.indices.filter { input.values[it] in defaults }.toIntArray()
        }.let { list ->
            Field(JBScrollPane(list)) { list.selectedValuesList.takeIf { it.isNotEmpty() }?.let(Gson()::toJson).orEmpty() }
        }
        input.type in MULTILINE -> JBTextArea(input.defaultValue, 4, 40).let { area ->
            Field(JBScrollPane(area)) { area.text }
        }
        else -> JBTextField(input.defaultValue).let { field -> Field(field) { field.text } }
    }

    private fun defaultList(value: String): Set<String> = runCatching {
        JsonParser.parseString(value).asJsonArray.map { it.asString }.toSet()
    }.getOrDefault(emptySet())

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
        for ((input, field) in fields) {
            val label = input.id + (if (input.required) " *" else "")
            form.addLabeledComponent(label, field.component)
            form.addTooltip(listOf(input.type, input.description).filter { it.isNotBlank() }.joinToString(" - "))
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
        val value = field.value()
        when {
            input.required && value.isBlank() -> ValidationInfo("${input.id} is required", field.component)
            input.type == "FILE" && value.isNotBlank() && !Files.isRegularFile(Path.of(value)) -> ValidationInfo("File not found", field.component)
            else -> null
        }
    }

    // Empty optional inputs are left out so the flow's defaults apply. Files are only referenced: they are
    // read when the request is sent, never here, on the UI thread.
    fun parts(): List<FormPart> = fields.mapNotNull { (input, field) ->
        val value = field.value()
        when {
            value.isEmpty() -> null
            input.type == "FILE" -> FormPart.LocalFile(input.id, Path.of(value))
            else -> FormPart.Text(input.id, value)
        }
    }

    private companion object {
        val MULTILINE = setOf("JSON", "YAML", "ARRAY")
    }
}
