package io.kestra.jetbrains

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.flow.FlowInput
import io.kestra.jetbrains.run.InputsDialog

class InputsDialogTest : BasePlatformTestCase() {
    private fun parts(vararg inputs: FlowInput): List<FormPart> {
        val dialog = InputsDialog(project, "company.team.hello", inputs.toList())
        try {
            return dialog.parts()
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testBooleanWithoutDefaultIsNotSentSoTheFlowDefaultApplies() {
        assertEquals(emptyList<FormPart>(), parts(FlowInput("flag", "BOOLEAN", required = false, defaultValue = "", values = emptyList())))
    }

    fun testBooleanKeepsItsDefault() {
        assertEquals(
            listOf<FormPart>(FormPart.Text("flag", "true")),
            parts(FlowInput("flag", "BOOLEAN", required = false, defaultValue = "true", values = emptyList(), description = "Enable it")),
        )
    }

    // VS Code renders BOOL as a checkbox, which always has a value: only BOOLEAN is tri-state.
    fun testBoolIsACheckboxThatIsAlwaysSent() {
        assertEquals(
            listOf<FormPart>(FormPart.Text("flag", "false")),
            parts(FlowInput("flag", "BOOL", required = false, defaultValue = "", values = emptyList())),
        )
        assertEquals(
            listOf<FormPart>(FormPart.Text("flag", "true")),
            parts(FlowInput("flag", "BOOL", required = false, defaultValue = "true", values = emptyList())),
        )
    }

    fun testMultiselectIsSentAsAJsonArrayOfTheValues() {
        assertEquals(
            listOf<FormPart>(FormPart.Text("colors", """["red","blue"]""")),
            parts(FlowInput("colors", "MULTISELECT", required = false, defaultValue = """["red","blue"]""", values = listOf("red", "green", "blue"))),
        )
    }

    fun testStructuredInputsKeepTheirLines() {
        assertEquals(
            listOf<FormPart>(FormPart.Text("payload", "{\n  \"a\": 1\n}")),
            parts(FlowInput("payload", "JSON", required = true, defaultValue = "{\n  \"a\": 1\n}", values = emptyList())),
        )
    }
}
