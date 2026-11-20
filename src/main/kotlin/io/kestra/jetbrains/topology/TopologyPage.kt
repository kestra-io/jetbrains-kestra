package io.kestra.jetbrains.topology

import com.google.gson.JsonObject
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import io.kestra.jetbrains.jcef.JcefView
import io.kestra.jetbrains.run.StateBucket

// The self-contained page the topology view loads, and the IDE colors it is drawn with.
object TopologyPage {
    private val CLUSTER = JBColor(0x8405FF, 0xA77BFF)
    private val TRIGGERS = JBColor(0xC2410C, 0xF59E62)

    fun html(bridge: String): String = JcefView.assemble(
        "topology", "topology.html", bridge,
        mapOf("CYTOSCAPE" to "cytoscape.min.js", "DAGRE" to "cytoscape-dagre.min.js", "TOPOLOGY" to "topology.js"),
    )

    fun theme(): JsonObject = JsonObject().apply {
        addProperty("background", JcefView.hex(UIUtil.getPanelBackground()))
        addProperty("surface", JcefView.hex(EditorColorsManager.getInstance().globalScheme.defaultBackground))
        addProperty("foreground", JcefView.hex(UIUtil.getLabelForeground()))
        addProperty("muted", JcefView.hex(UIUtil.getContextHelpForeground()))
        addProperty("border", JcefView.hex(JBColor.border()))
        addProperty("font", UIUtil.getLabelFont().family)
        addProperty("cluster", JcefView.hex(CLUSTER))
        addProperty("triggers", JcefView.hex(TRIGGERS))
        StateBucket.entries.forEach { addProperty(it.name.lowercase(), JcefView.hex(it.color)) }
    }
}
