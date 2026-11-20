package io.kestra.jetbrains.topology

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

// Turns the instance's /flows/graph answer into Cytoscape elements: clusters become compound parents so
// branch boxes nest, nodes without an id (cluster entries and exits) become invisible waypoints.
object FlowGraph {
    data class Elements(val json: JsonArray, val pluginTypes: Set<String>)

    fun elements(graph: JsonElement?, icons: Map<String, String>): Elements? {
        val obj = graph as? JsonObject ?: return null
        val nodes = obj.array("nodes") ?: return null
        val elements = JsonArray()
        val types = LinkedHashSet<String>()

        val parentOf = HashMap<String, String>()
        val clusters = obj.array("clusters").objects()
        for (cluster in clusters) {
            val uid = cluster.obj("cluster")?.string("uid") ?: continue
            cluster.array("nodes").strings().forEach { parentOf[it] = uid }
        }
        for (cluster in clusters) {
            val info = cluster.obj("cluster") ?: continue
            val uid = info.string("uid") ?: continue
            // The triggers cluster has no task node, the API identifies it by its fixed uid.
            val triggers = uid.endsWith(".Triggers")
            val label = if (triggers) "Triggers" else info.obj("taskNode")?.obj("task")?.string("id").orEmpty()
            elements.add(element(uid, label, parentOf[uid], if (triggers) "cluster triggers" else "cluster"))
        }

        for (node in nodes.objects()) {
            val uid = node.string("uid") ?: continue
            val declared = node.obj("task") ?: node.obj("triggerDeclaration")
            val id = declared?.string("id").orEmpty()
            val type = declared?.string("type")
            type?.let(types::add)
            elements.add(element(uid, id, parentOf[uid], if (id.isEmpty()) "boundary" else "task").also {
                val data = it.getAsJsonObject("data")
                data.addProperty("taskId", id)
                data.addProperty("icon", type?.let(icons::get) ?: "none")
            })
        }

        for (edge in obj.array("edges").objects()) {
            val source = edge.string("source") ?: continue
            val target = edge.string("target") ?: continue
            elements.add(JsonObject().apply {
                add("data", JsonObject().apply {
                    addProperty("id", "$source->$target")
                    addProperty("source", source)
                    addProperty("target", target)
                })
            })
        }
        return Elements(elements, types)
    }

    private fun element(id: String, label: String, parent: String?, classes: String) = JsonObject().apply {
        add("data", JsonObject().apply {
            addProperty("id", id)
            addProperty("label", label)
            parent?.let { addProperty("parent", it) }
        })
        addProperty("classes", classes)
    }

    private fun JsonObject.array(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.obj(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonArray?.objects() = this?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()

    private fun JsonArray?.strings() = this?.filter { it.isJsonPrimitive }?.map { it.asString }.orEmpty()
}
