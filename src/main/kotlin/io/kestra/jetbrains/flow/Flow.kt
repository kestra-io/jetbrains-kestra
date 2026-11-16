package io.kestra.jetbrains.flow

import com.google.gson.Gson

data class FlowInput(
    val id: String,
    val type: String,
    val required: Boolean,
    val defaultValue: String,
    val values: List<String>,
)

// A flow parsed into plain values, see FlowYaml.toValue.
class Flow(val root: Map<String, Any?>) {
    val id: String get() = root["id"] as? String ?: ""
    val namespace: String get() = root["namespace"] as? String ?: ""
    val uid: String get() = "$namespace.$id"

    // FORM groups expand to their children keyed by dotted id, the shape the executions API expects.
    fun inputs(): List<FlowInput> = maps(root["inputs"]).flatMap { input ->
        val id = input["id"] as? String ?: return@flatMap emptyList()
        if ((input["type"] as? String)?.uppercase() == "FORM") {
            maps(input["inputs"]).mapNotNull { child -> toInput(child, "$id.") }
        } else {
            listOfNotNull(toInput(input, ""))
        }
    }

    fun inputIds(): List<String> = maps(root["inputs"]).mapNotNull { it["id"] as? String }

    fun taskIds(): List<String> = tasks().mapNotNull { it["id"] as? String }

    fun taskType(taskId: String): String? = tasks().firstOrNull { it["id"] == taskId }?.get("type") as? String

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
        val id = input["id"] as? String ?: return null
        val fallback = input["prefill"] ?: input["defaults"]
        return FlowInput(
            id = prefix + id,
            type = (input["type"] as? String)?.uppercase() ?: "STRING",
            // Kestra inputs are required unless explicitly marked optional.
            required = (input["required"] as? String)?.lowercase() != "false",
            defaultValue = when (fallback) {
                null -> ""
                is String -> fallback
                else -> Gson().toJson(fallback)
            },
            values = (input["values"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
        )
    }

    companion object {
        private fun maps(value: Any?): List<Map<*, *>> = (value as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    }
}
