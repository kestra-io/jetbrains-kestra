package io.kestra.jetbrains.topology

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.kestra.jetbrains.jcef.JcefView

// What the topology service draws on. The JCEF view in the IDE, a recorder in tests.
interface TopologyRenderer {
    fun render(elements: String)

    // The same graph with more icons, drawn without a new layout.
    fun updateIcons(elements: String)
    fun notice(text: String)
    fun setTaskState(taskId: String, bucket: String?)
    fun resetStates()
    fun setTheme(theme: String)
}

// Renders the graph in the embedded browser with the same Cytoscape and dagre layout as the VS Code extension.
class JcefTopologyView(private val onReveal: (String) -> Unit) : JcefView(), TopologyRenderer {
    init {
        load()
    }

    override fun page(bridge: String) = TopologyPage.html(bridge)

    // What the page sends: {"type": "ready"} or {"type": "reveal", "taskId": "..."}.
    override fun onMessage(json: JsonObject) {
        when (json.text("type")) {
            "ready" -> pageReady("kestraTopology.setTheme(${TopologyPage.theme()})")
            "reveal" -> json.text("taskId")?.let(onReveal)
        }
    }

    override fun render(elements: String) = call("kestraTopology.render($elements)")

    override fun updateIcons(elements: String) = call("kestraTopology.updateIcons($elements)")

    override fun notice(text: String) = call("kestraTopology.notice(${JsonPrimitive(text)})")

    override fun setTaskState(taskId: String, bucket: String?) =
        call("kestraTopology.setTaskState(${JsonPrimitive(taskId)}, ${bucket?.let(::JsonPrimitive) ?: "null"})")

    override fun resetStates() = call("kestraTopology.resetStates()")

    override fun setTheme(theme: String) = call("kestraTopology.setTheme($theme)")
}
