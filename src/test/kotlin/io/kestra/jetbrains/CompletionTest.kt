package io.kestra.jetbrains

import io.kestra.jetbrains.completion.ExpressionService

class CompletionTest : KestraTestCase() {
    override fun setUp() {
        super.setUp()
        ExpressionService.instance.reset()
        kestra.json("GET", "/pebble/functions") { """["now", {"name":"secret","arguments":[{"name":"key","defaultValue":"'KEY'"}]}]""" }
        kestra.json("GET", "/pebble/filters") { """["upper","lower"]""" }
        kestra.json("POST", "/flows/expressions") {
            """{"taskOutputs":["outputs.greet.value"],"inputs":["inputs.user"],"executionContext":["execution.id"],"secrets":["secret('DB_PASSWORD')"],"kvPairs":["kv('token')"]}"""
        }
    }

    private fun complete(text: String): List<String> {
        myFixture.configureByText("flow.yml", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun flowWith(message: String) = FLOW.replace("message: Hello", "message: \"$message\"")

    fun testRootSuggestsVariablesSecretsAndFunctions() {
        val items = complete(flowWith("{{ <caret>"))
        assertContainsElements(items, "outputs", "inputs", "execution", "secret('DB_PASSWORD')", "kv('token')", "now()", "secret(key='KEY')")
    }

    fun testMemberUsesEndpointPaths() {
        val items = complete(flowWith("{{ outputs.<caret>"))
        assertContainsElements(items, "greet")
        assertSameElements(complete(flowWith("{{ outputs.greet.<caret>")), "value")
    }

    fun testFilters() {
        assertSameElements(complete(flowWith("{{ inputs.user | <caret>")), "upper", "lower")
    }

    fun testNothingOutsideExpressions() {
        val items = complete(flowWith("plain <caret>"))
        assertDoesntContain(items, "outputs", "now()")
    }

    // On 1.x the POST would hit "replace every flow in namespace 'expressions'": it must never be sent.
    fun testKestra1NeverCallsExpressionsEndpoint() {
        kestra.json("GET", "/configs") { """{"version":"1.3.47"}""" }
        kestra.json("GET", "/plugins/io.kestra.plugin.core.log.Log") { """{"schema":{"outputs":{"properties":{"value":{}}}}}""" }
        assertContainsElements(complete(flowWith("{{ inputs.<caret>")), "user")
        assertContainsElements(complete(flowWith("{{ outputs.greet.<caret>")), "value")
        assertTrue(kestra.requests.none { it.path == "/flows/expressions" })
    }
}
