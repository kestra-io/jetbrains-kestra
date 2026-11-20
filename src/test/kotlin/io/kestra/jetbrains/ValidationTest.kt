package io.kestra.jetbrains

import com.intellij.lang.annotation.HighlightSeverity
import io.kestra.jetbrains.validation.KestraValidationAnnotator

class ValidationTest : KestraTestCase() {
    override fun setUp() {
        super.setUp()
        KestraValidationAnnotator.clearCache()
    }

    fun testInstanceErrorsAreAnnotatedOnTheirPath() {
        kestra.json("POST", "/flows/validate") {
            """[{"constraints":"tasks[0].type: Invalid type: io.kestra.plugin.core.log.Log\nnamespace: must match","warnings":["Something to check"],"deprecationPaths":["tasks[0].message"]}]"""
        }
        myFixture.configureByText("flow.yml", FLOW)
        val highlights = myFixture.doHighlighting().filter { it.description != null }

        val typeError = highlights.single { it.description.startsWith("tasks[0].type") }
        assertEquals(HighlightSeverity.ERROR, typeError.severity)
        assertEquals("type: io.kestra.plugin.core.log.Log", myFixture.file.text.substring(typeError.startOffset, typeError.endOffset))

        val deprecated = highlights.single { it.description == "Deprecated: tasks[0].message" }
        assertEquals("message: Hello", myFixture.file.text.substring(deprecated.startOffset, deprecated.endOffset))
        assertNotNull(highlights.singleOrNull { it.description == "Something to check" && it.severity == HighlightSeverity.WARNING })
        val namespace = highlights.single { it.description == "namespace: must match" }
        assertEquals("namespace: company.team", myFixture.file.text.substring(namespace.startOffset, namespace.endOffset))

        val validate = kestra.requests.single { it.path == "/flows/validate" }
        assertEquals("application/x-yaml", validate.headers["content-type"])
        assertEquals(FLOW, validate.body)
    }

    // Formats returned by Kestra 2.0.10.
    fun testKestra2Messages() {
        val source = """
            id: v
            namespace: company.team
            triggers:
              - id: s
                type: io.kestra.plugin.core.trigger.Schedule
            tasks:
              - id: p
                type: io.kestra.plugin.core.flow.Parallel
                tasks:
                  - id: c
                    type: io.kestra.plugin.core.log.Log
              - id: t
                type: io.kestra.plugin.core.log.Log
                message: hi
                unknownProp: 1
        """.trimIndent()
        val constraints = listOf(
            "Validation error: tasks[c].tasks[c].message: must not be null",
            "Validation error: triggers[0].cron: must not be null",
            "Validation error: Unrecognized field \\\"unknownProp\\\" (class io.kestra.plugin.core.log.Log), not marked as ignorable",
        )
        kestra.json("POST", "/flows/validate") { """[{"constraints":"${constraints.joinToString("\\n")}"}]""" }
        myFixture.configureByText("flow.yml", source)
        val highlights = myFixture.doHighlighting().filter { it.description != null }
        fun textOf(prefix: String) = highlights.single { it.description.startsWith(prefix) }.let { myFixture.file.text.substring(it.startOffset, it.endOffset) }

        // The nested task misses `message`: its id line is highlighted.
        assertEquals("id: c", textOf("Validation error: tasks[c]"))
        assertEquals("id: s", textOf("Validation error: triggers[0]"))
        assertEquals("unknownProp:", textOf("Validation error: Unrecognized"))
    }

    // A refused or failed call is not a verdict: the errors found before stay until a call succeeds.
    fun testFailedValidationKeepsTheLastErrors() {
        kestra.json("POST", "/flows/validate") { """[{"constraints":"tasks[0].type: Invalid type: x"}]""" }
        myFixture.configureByText("flow.yml", FLOW)
        assertTrue(myFixture.doHighlighting().any { it.description?.startsWith("tasks[0].type") == true })

        kestra.json("POST", "/flows/validate", status = 500) { """{"message":"down"}""" }
        myFixture.editor.caretModel.moveToOffset(myFixture.file.textLength)
        myFixture.type("# edited\n")
        val highlights = myFixture.doHighlighting()
        assertTrue(kestra.requests.count { it.path == "/flows/validate" } >= 2)
        assertTrue(highlights.any { it.description?.startsWith("tasks[0].type") == true })
    }

    fun testNonFlowYamlIsNotSent() {
        kestra.json("POST", "/flows/validate") { "[]" }
        myFixture.configureByText("compose.yml", "services:\n  web: {}\n")
        myFixture.doHighlighting()
        assertTrue(kestra.requests.none { it.path == "/flows/validate" })
    }
}
