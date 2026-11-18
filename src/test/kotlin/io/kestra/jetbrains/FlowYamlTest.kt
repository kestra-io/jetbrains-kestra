package io.kestra.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kestra.jetbrains.flow.FlowYaml
import org.jetbrains.yaml.psi.YAMLKeyValue

class FlowYamlTest : BasePlatformTestCase() {
    fun testDetectsFlows() {
        assertTrue(FlowYaml.isFlow(myFixture.configureByText("a.yml", FLOW)))
        assertTrue(FlowYaml.isFlow(myFixture.configureByText("b.yml", "id: t\nnamespace: n\ntriggers:\n  - id: s\n")))
        assertFalse(FlowYaml.isFlow(myFixture.configureByText("c.yml", "id: t\nnamespace: n\n")))
        assertFalse(FlowYaml.isFlow(myFixture.configureByText("d.yml", "id:\nnamespace: n\ntasks: []\n")))
        assertFalse(FlowYaml.isFlow(myFixture.configureByText("e.yml", "services:\n  web: {}\n")))
    }

    fun testFlowModel() {
        val file = myFixture.configureByText("flow.yml", """
            id: hello
            namespace: company.team
            inputs:
              - id: user
                type: STRING
                defaults: Kestra
              - id: optional
                type: INT
                required: false
              - id: form
                type: FORM
                inputs:
                  - id: name
                    type: STRING
              - id: payload
                type: JSON
                defaults:
                  a: 1
            tasks:
              - id: parallel
                type: io.kestra.plugin.core.flow.Parallel
                tasks:
                  - id: child
                    type: io.kestra.plugin.core.log.Log
            errors:
              - id: alert
                type: io.kestra.plugin.core.log.Log
        """.trimIndent())
        val flow = FlowYaml.flow(file)!!
        assertEquals("company.team.hello", flow.uid)
        assertEquals(listOf("user", "optional", "form.name", "payload"), flow.inputs().map { it.id })
        assertEquals("Kestra", flow.inputs()[0].defaultValue)
        assertFalse(flow.inputs()[1].required)
        assertEquals("""{"a":1}""", flow.inputs()[3].defaultValue)
        assertEquals(listOf("parallel", "child", "alert"), flow.taskIds())
        assertEquals("io.kestra.plugin.core.log.Log", flow.taskType("child"))
    }

    // A JSON default keeps its types: it is sent as it is and replaces the flow's own default on the instance.
    fun testYamlScalarsKeepTheirTypes() {
        val flow = FlowYaml.flow(myFixture.configureByText("typed.yml", """
            id: 42
            namespace: company.team
            inputs:
              - id: payload
                type: JSON
                defaults:
                  retries: 3
                  ratio: 1.5
                  enabled: true
                  quoted: "3"
                  nothing:
                  name: Ana
              - id: flag
                type: BOOLEAN
                required: false
                defaults: false
            tasks:
              - id: t
                type: io.kestra.plugin.core.log.Log
        """.trimIndent()))!!
        assertEquals("""{"retries":3,"ratio":1.5,"enabled":true,"quoted":"3","name":"Ana"}""", flow.inputs()[0].defaultValue)
        assertEquals("false", flow.inputs()[1].defaultValue)
        assertFalse(flow.inputs()[1].required)
        assertEquals("company.team.42", flow.uid)
    }

    fun testFindsValidationPaths() {
        val file = myFixture.configureByText("flow.yml", FLOW)
        val element = FlowYaml.find(file, FlowYaml.parsePath("tasks[0].type")) as YAMLKeyValue
        assertEquals("type", element.keyText)
        assertEquals("tasks", (FlowYaml.find(file, FlowYaml.parsePath("tasks[3].type")) as YAMLKeyValue).keyText)
        assertEquals(listOf("tasks", 0, "type"), FlowYaml.parsePath("tasks[0].type"))
        assertEquals(listOf("tasks", FlowYaml.ById("my-task"), "message"), FlowYaml.parsePath("tasks[my-task].message"))
        val byId = FlowYaml.find(file, FlowYaml.parsePath("tasks[greet].message")) as YAMLKeyValue
        assertEquals("message: Hello", byId.text)
    }
}
