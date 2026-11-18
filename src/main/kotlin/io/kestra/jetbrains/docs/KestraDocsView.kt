package io.kestra.jetbrains.docs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.kestra.jetbrains.run.StateBucket
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Color
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

// The documentation page in the embedded browser. The page asks for data through a query, answered in the
// background by DocsApi; links leave for the system browser, the page itself never navigates.
class KestraDocsView : Disposable {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val queue = ArrayList<String>()
    private var ready = false

    // Once the page is shown, nothing may navigate the view: a link the sanitizer let through, a form, a script.
    // Links are opened by the page itself through the system browser.
    @Volatile private var pageLoaded = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { message ->
            handle(message)
            null
        }
        // Registered before the page loads: the handler lets that one navigation through and cancels every other.
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean) = pageLoaded

            override fun onOpenURLFromTab(cefBrowser: CefBrowser?, frame: CefFrame?, targetUrl: String?, userGesture: Boolean) = true
        }, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(cefBrowser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                if (!isLoading) pageLoaded = true
            }
        }, browser.cefBrowser)
        browser.loadHTML(page("window.kestraPost = function (msg) { ${query.inject("msg")} };"))
    }

    private fun handle(message: String) {
        val json = runCatching { JsonParser.parseString(message).asJsonObject }.getOrNull() ?: return
        val args = json.getAsJsonObject("args") ?: JsonObject()
        val id = json.get("id")?.asInt
        when (val op = json.get("op")?.asString) {
            "ready" -> ApplicationManager.getApplication().invokeLater {
                ready = true
                execute("kestraDocs.setTheme(${theme()})")
                execute("kestraDocs.home()")
                queue.forEach(::execute)
                queue.clear()
            }
            "open" -> args.get("url")?.asString?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "copy" -> ApplicationManager.getApplication().invokeLater {
                CopyPasteManager.getInstance().setContents(StringSelection(args.get("text")?.asString.orEmpty()))
            }
            null -> Unit
            else -> if (id != null) ApplicationManager.getApplication().executeOnPooledThread {
                val result = DocsApi.handle(op, args)
                execute("kestraDocs.resolve($id, $result)")
            }
        }
    }

    // Shows the plugin's page, unless it is already shown or unknown.
    fun showPlugin(type: String) = call("kestraDocs.showPlugin(${JsonPrimitive(type)})")

    fun updateTheme() = call("kestraDocs.setTheme(${theme()})")

    private fun call(script: String) {
        if (ready) execute(script) else queue += script
    }

    private fun execute(script: String) = browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)

    override fun dispose() = Unit

    companion object {
        fun page(bridge: String): String {
            fun resource(name: String) = KestraDocsView::class.java.getResource("/docs/$name")!!.readText()
            return resource("docs.html")
                .replace("/*STYLE*/", resource("docs.css"))
                .replace("/*BRIDGE*/", bridge)
                .replace("/*MARKDOWN_IT*/", resource("markdown-it.min.js"))
                .replace("/*CONTAINER*/", resource("markdown-it-container.min.js"))
                .replace("/*PURIFY*/", resource("purify.min.js"))
                .replace("/*DOCS*/", resource("docs.js"))
        }

        // The design tokens of the VS Code panel, taken from the IDE theme.
        fun theme(): JsonObject = JsonObject().apply {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val base = UIUtil.getPanelBackground()
            val foreground = UIUtil.getLabelForeground()
            fun color(name: String, value: Color) = addProperty(name, "#%02x%02x%02x".format(value.red, value.green, value.blue))
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
