package io.kestra.jetbrains.flow

import com.google.gson.Gson

data class FlowInput(
    val id: String,
    val type: String,
    val required: Boolean,
    val defaultValue: String,
    val values: List<String>,
    val description: String = "",
)

// A flow parsed into plain values, see FlowYaml.toValue.
class Flow(val root: Map<String, Any?>) {
    val id: String get() = text(root["id"])
    val namespace: String get() = text(root["namespace"])
    val uid: String get() = "$namespace.$id"

    // FORM groups expand to their children keyed by dotted id, the shape the executions API expects.
    fun inputs(): List<FlowInput> = maps(root["inputs"]).flatMap { input ->
        val id = text(input["id"]).ifEmpty { return@flatMap emptyList() }
        if ((input["type"] as? String)?.uppercase() == "FORM") {
            maps(input["inputs"]).mapNotNull { child -> toInput(child, "$id.") }
        } else {
            listOfNotNull(toInput(input, ""))
        }
    }

    fun inputIds(): List<String> = maps(root["inputs"]).map { text(it["id"]) }.filter { it.isNotEmpty() }

    fun taskIds(): List<String> = tasks().map { text(it["id"]) }.filter { it.isNotEmpty() }

    fun taskType(taskId: String): String? = tasks().firstOrNull { text(it["id"]) == taskId }?.get("type") as? String

    fun taskTypes(): List<String> = buildList {
        fun walk(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    (value["type"] as? String)?.let(::add)
                    value.values.forEach(::walk)
                }
                is List<*> -> value.forEach(::walk)
            }
        }
        listOf("tasks", "triggers", "errors", "finally").forEach { walk(root[it]) }
    }

    fun sectionKeys(section: String): List<String> = (root[section] as? Map<*, *>)?.keys?.map { it.toString() }.orEmpty()

    // Tasks at any depth, including flowable children and error/finally branches.
    private fun tasks(): List<Map<*, *>> = buildList {
        fun walk(value: Any?) {
            for (task in maps(value)) {
                add(task)
                walk(task["tasks"])
                walk(task["errors"])
                walk(task["finally"])
            }
        }
        walk(root["tasks"])
        walk(root["errors"])
        walk(root["finally"])
    }

    private fun toInput(input: Map<*, *>, prefix: String): FlowInput? {
        val id = text(input["id"]).ifEmpty { return null }
        val fallback = input["prefill"] ?: input["defaults"]
        return FlowInput(
            id = prefix + id,
            type = (input["type"] as? String)?.uppercase() ?: "STRING",
            // Kestra inputs are required unless explicitly marked optional.
            required = FlowYaml.yaml11(text(input["required"])) != false,
            defaultValue = when (fallback) {
                null -> ""
                is String -> fallback
                else -> Gson().toJson(fallback)
            },
            values = (input["values"] as? List<*>)?.map(::text)?.filter { it.isNotEmpty() }.orEmpty(),
            description = input["description"] as? String ?: "",
        )
    }

    companion object {
        // A scalar as its text: an id such as 42 is a number in the YAML, but still an id.
        private fun text(value: Any?): String = if (value is Map<*, *> || value is List<*>) "" else value?.toString().orEmpty()

        private fun maps(value: Any?): List<Map<*, *>> = (value as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    }
}
