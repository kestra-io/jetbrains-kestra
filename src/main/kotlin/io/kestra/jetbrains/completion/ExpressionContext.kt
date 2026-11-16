package io.kestra.jetbrains.completion

import com.google.gson.JsonElement

// POST /flows/expressions response (Kestra 2.0+). Values carry no {{ }} delimiters.
data class ExpressionContext(
    val taskOutputs: List<String> = emptyList(),
    val executionContext: List<String> = emptyList(),
    val inputs: List<String> = emptyList(),
    val variables: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val kvPairs: List<String> = emptyList(),
    val namespaceFiles: List<String> = emptyList(),
) {
    // Dotted paths; secrets, KV pairs and namespace files are complete calls, completed separately.
    private val paths: List<String> get() = taskOutputs + executionContext + inputs + variables

    fun rootNames(): List<String> = paths.map(::head).filter { it.isNotEmpty() }.distinct()

    fun childrenOf(base: String): List<String> {
        val prefix = "$base."
        return paths.filter { it.startsWith(prefix) }.map { head(it.removePrefix(prefix)) }.filter { it.isNotEmpty() }.distinct()
    }

    // The endpoint only reports tasks that declare outputs, so task ids are merged back in.
    fun membersOf(base: String, taskIds: List<String>): List<String> {
        val fields = childrenOf(base)
        return if (base == "outputs") (fields + taskIds).distinct() else fields
    }

    companion object {
        // Leading segment, stopping at a dot or bracket so outputs['my-task'].uri yields "outputs".
        private fun head(path: String) = path.split('.', '[').first()

        fun parse(json: JsonElement?): ExpressionContext? {
            val obj = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            fun list(name: String) = obj.get(name)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            return ExpressionContext(
                list("taskOutputs"), list("executionContext"), list("inputs"), list("variables"),
                list("secrets"), list("kvPairs"), list("namespaceFiles"),
            )
        }
    }
}

data class PebbleFunction(val name: String, val arguments: List<Pair<String, String?>>) {
    // Arguments with a default are pre-filled, as the Kestra UI does.
    fun insertText(): String {
        val args = arguments.filter { it.second != null }
        return if (args.isEmpty()) "$name()" else "$name(${args.joinToString(", ") { "${it.first}=${it.second}" }})"
    }

    companion object {
        fun parse(json: JsonElement?): List<PebbleFunction> {
            val array = json?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                when {
                    element.isJsonPrimitive -> PebbleFunction(element.asString, emptyList())
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                        val args = obj.get("arguments")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { arg ->
                            val a = arg.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                            val argName = a.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                            argName to a.get("defaultValue")?.takeIf { it.isJsonPrimitive }?.asString
                        }.orEmpty()
                        PebbleFunction(name, args)
                    }
                    else -> null
                }
            }
        }
    }
}

// What the text before the caret, inside an open {{, asks for.
sealed interface ExpressionQuery {
    val prefix: String

    data class Filter(override val prefix: String) : ExpressionQuery
    data class Member(val base: String, override val prefix: String) : ExpressionQuery
    data class Root(override val prefix: String) : ExpressionQuery

    companion object {
        private val FILTER = Regex("""\|\s*(\w*)$""")
        private val MEMBER = Regex("""([\w.]+)\.(\w*)$""")
        private val WORD = Regex("""(\w*)$""")

        // Null when the caret is not inside an open {{ on this line.
        fun of(lineBeforeCaret: String): ExpressionQuery? {
            val open = lineBeforeCaret.lastIndexOf("{{")
            if (open == -1 || open < lineBeforeCaret.lastIndexOf("}}")) {
                return null
            }
            val expression = lineBeforeCaret.substring(open + 2)
            FILTER.find(expression)?.let { return Filter(it.groupValues[1]) }
            MEMBER.find(expression)?.let { return Member(it.groupValues[1], it.groupValues[2]) }
            return Root(WORD.find(expression)?.groupValues?.get(1).orEmpty())
        }
    }
}
