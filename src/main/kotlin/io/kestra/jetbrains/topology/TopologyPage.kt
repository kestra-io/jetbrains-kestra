package io.kestra.jetbrains.topology

import com.google.gson.JsonObject
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import io.kestra.jetbrains.run.StateBucket
import java.awt.Color

// The self-contained page the topology view loads, and the IDE colors it is drawn with.
object TopologyPage {
    private val CLUSTER = JBColor(0x8405FF, 0xA77BFF)
    private val TRIGGERS = JBColor(0xC2410C, 0xF59E62)

    fun html(bridge: String): String {
        fun resource(name: String) = TopologyPage::class.java.getResource("/topology/$name")!!.readText()
        return resource("topology.html")
            .replace("/*BRIDGE*/", bridge)
            .replace("/*CYTOSCAPE*/", resource("cytoscape.min.js"))
            .replace("/*DAGRE*/", resource("cytoscape-dagre.min.js"))
            .replace("/*TOPOLOGY*/", resource("topology.js"))
    }

    fun theme(): JsonObject = JsonObject().apply {
        addProperty("background", hex(UIUtil.getPanelBackground()))
        addProperty("surface", hex(EditorColorsManager.getInstance().globalScheme.defaultBackground))
        addProperty("foreground", hex(UIUtil.getLabelForeground()))
        addProperty("muted", hex(UIUtil.getContextHelpForeground()))
        addProperty("border", hex(JBColor.border()))
        addProperty("font", UIUtil.getLabelFont().family)
        addProperty("cluster", hex(CLUSTER))
        addProperty("triggers", hex(TRIGGERS))
        StateBucket.entries.forEach { addProperty(it.name.lowercase(), hex(it.color)) }
    }

    private fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)
}
