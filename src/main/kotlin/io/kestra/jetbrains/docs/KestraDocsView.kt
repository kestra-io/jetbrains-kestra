package io.kestra.jetbrains.docs

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.kestra.jetbrains.jcef.JcefView
import io.kestra.jetbrains.run.StateBucket
import java.awt.Color
import java.awt.datatransfer.StringSelection

// The documentation page in the embedded browser. The page asks for data through a query, answered in the
// background by DocsApi; links leave for the system browser, the page itself never navigates.
class KestraDocsView : JcefView() {
    init {
        load()
    }

    override fun page(bridge: String) = Companion.page(bridge)

    override fun onMessage(json: JsonObject) {
        val (op, id, args) = message(json) ?: return
        when (op) {
            "ready" -> pageReady("kestraDocs.setTheme(${theme()})", "kestraDocs.home()")
            "open" -> args.text("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "copy" -> CopyPasteManager.getInstance().setContents(StringSelection(args.text("text").orEmpty()))
            else -> if (id != null) ApplicationManager.getApplication().executeOnPooledThread {
                val result = DocsApi.handle(op, args)
                execute("kestraDocs.resolve($id, $result)")
            }
        }
    }

    // Shows the plugin's page, unless it is already shown or unknown.
    fun showPlugin(type: String) = call("kestraDocs.showPlugin(${JsonPrimitive(type)})")

    fun updateTheme() = call("kestraDocs.setTheme(${theme()})")

    // What the page sends: {"op": "...", "id": 1, "args": {...}}. A malformed field is as good as a missing one.
    internal data class Message(val op: String, val id: Int?, val args: JsonObject)

    companion object {
        internal fun parse(message: String): Message? = parseObject(message)?.let(::message)

        private fun message(json: JsonObject): Message? {
            val op = json.text("op") ?: return null
            val id = json.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asInt }.getOrNull() }
            val args = json.get("args")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            return Message(op, id, args)
        }

        fun page(bridge: String): String = assemble(
            "docs", "docs.html", bridge,
            mapOf("STYLE" to "docs.css", "MARKDOWN_IT" to "markdown-it.min.js", "CONTAINER" to "markdown-it-container.min.js", "PURIFY" to "purify.min.js", "DOCS" to "docs.js"),
        )

        // The design tokens of the VS Code panel, taken from the IDE theme.
        fun theme(): JsonObject = JsonObject().apply {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val base = UIUtil.getPanelBackground()
            val foreground = UIUtil.getLabelForeground()
            fun color(name: String, value: Color) = addProperty(name, hex(value))
            fun syntax(name: String, key: com.intellij.openapi.editor.colors.TextAttributesKey, fallback: Color) =
                color(name, scheme.getAttributes(key)?.foregroundColor ?: fallback)
            color("ks-bg-base", base)
            color("ks-bg-surface", ColorUtil.mix(base, foreground, 0.05))
            color("ks-bg-elevated", ColorUtil.mix(base, foreground, 0.05))
            color("ks-bg-hover", ColorUtil.mix(base, foreground, 0.1))
            color("ks-bg-input", scheme.defaultBackground)
            color("ks-text-primary", foreground)
            color("ks-text-muted", UIUtil.getContextHelpForeground())
            color("ks-text-link", JBUI.CurrentTheme.Link.Foreground.ENABLED)
            color("ks-border-default", JBColor.border())
            color("ks-border-focus", JBUI.CurrentTheme.Focus.focusColor())
            color("ks-status-success", StateBucket.SUCCESS.color)
            color("ks-status-warning", StateBucket.WARNING.color)
            color("ks-status-error", StateBucket.FAILED.color)
            color("ks-status-info", StateBucket.INFO.color)
            color("ks-primary", StateBucket.RUNNING.color)
            color("ks-content-code", StateBucket.RUNNING.color)
            syntax("ks-code-key", DefaultLanguageHighlighterColors.KEYWORD, StateBucket.RUNNING.color)
            syntax("ks-code-value", DefaultLanguageHighlighterColors.STRING, StateBucket.SUCCESS.color)
            syntax("ks-code-comment", DefaultLanguageHighlighterColors.LINE_COMMENT, UIUtil.getContextHelpForeground())
            val font = UIUtil.getLabelFont()
            addProperty("ks-font", "\"${font.family}\", system-ui, -apple-system, \"Segoe UI\", sans-serif")
            addProperty("ks-font-mono", "\"${scheme.editorFontName}\", monospace")
            addProperty("ks-font-size-base", "${font.size}px")
            addProperty("ks-font-size-sm", "${font.size - 1}px")
            addProperty("ks-font-size-xs", "${font.size - 2}px")
            addProperty("ks-font-weight-semibold", "600")
            listOf(4, 8, 12, 16).forEachIndexed { index, px -> addProperty("ks-spacing-${index + 1}", "${px}px") }
            addProperty("ks-radius-sm", "4px")
            addProperty("ks-radius-base", "6px")
        }
    }
}
