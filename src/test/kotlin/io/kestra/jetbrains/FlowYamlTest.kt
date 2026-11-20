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

    // Kestra keeps ids and values as the text they were written as: 007 is not 7, 1.10 is not 1.1.
    fun testScalarsOutsideTypedDefaultsKeepTheirText() {
        val flow = FlowYaml.flow(myFixture.configureByText("raw.yml", """
            id: 007
            namespace: 1.10
            inputs:
              - id: 1e3
                type: SELECT
                values: [1.50, 0123, yes]
                defaults: 1.50
              - id: octal
                type: STRING
                defaults: 0123
              - id: nothing
                type: STRING
                defaults: null
              - id: tilde
                type: STRING
                defaults: ~
              - id: quotedNull
                type: STRING
                defaults: "null"
              - id: no
                type: STRING
                required: no
              - id: yes
                type: STRING
                required: yes
            tasks:
              - id: 7
                type: io.kestra.plugin.core.log.Log
        """.trimIndent()))!!
        assertEquals("1.10.007", flow.uid)
        assertEquals(listOf("1e3", "octal", "nothing", "tilde", "quotedNull", "no", "yes"), flow.inputIds())
        assertEquals(listOf("1.50", "0123", "yes"), flow.inputs()[0].values)
        assertEquals(listOf("1.50", "0123", "", "", "null"), flow.inputs().take(5).map { it.defaultValue })
        assertEquals(listOf(true, true, true, true, true, false, true), flow.inputs().map { it.required })
        assertEquals(listOf("7"), flow.taskIds())
    }

    // Inside a map or list a plain scalar takes the type Kestra's YAML 1.1 parser gives it, and what has no JSON form stays text.
    fun testTypedDefaultsFollowYaml11() {
        val flow = FlowYaml.flow(myFixture.configureByText("typed11.yml", """
            id: t
            namespace: n
            inputs:
              - id: payload
                type: JSON
                defaults:
                  on: on
                  off: No
                  nothing: ~
                  octal: 0123
                  hex: 0x1F
                  binary: 0b101
                  grouped: 1_000
                  minutes: 1:30
                  exponent: 1e3
                  float: 1.50
                  huge: 1e999
                  infinite: .inf
                  quoted: "yes"
                  text: 08
                  list: [007, 1.5, true, "x", ~]
                  big: 123456789012345678901234567890
            tasks:
              - id: t
                type: io.kestra.plugin.core.log.Log
        """.trimIndent()))!!
        val json = com.google.gson.JsonParser.parseString(flow.inputs().single().defaultValue).asJsonObject
        fun field(name: String) = json.get(name)
        assertEquals(true, field("on").asBoolean)
        assertEquals(false, field("off").asBoolean)
        assertNull(field("nothing"))
        assertEquals(83, field("octal").asInt)
        assertEquals(31, field("hex").asInt)
        assertEquals(5, field("binary").asInt)
        assertEquals(1000, field("grouped").asInt)
        assertEquals(90, field("minutes").asInt)
        assertEquals(1000.0, field("exponent").asDouble)
        assertEquals(1.5, field("float").asDouble)
        assertEquals("1e999", field("huge").asString)
        assertEquals(".inf", field("infinite").asString)
        assertEquals("yes", field("quoted").asString)
        assertEquals("08", field("text").asString)
        assertEquals("""[7,1.5,true,"x",null]""", field("list").toString())
        assertEquals("123456789012345678901234567890", field("big").asBigInteger.toString())
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
