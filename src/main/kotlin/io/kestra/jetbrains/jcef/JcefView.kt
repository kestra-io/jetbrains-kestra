package io.kestra.jetbrains.jcef

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Color
import javax.swing.JComponent
import javax.swing.SwingConstants

// What the topology and the documentation views share: a self-contained page in the embedded browser, a
// JavaScript to Kotlin bridge, calls queued until the page is ready, and a guard that keeps the page from being
// navigated away. Only for the code that needs JCEF, which is an optional dependency, see kestra-jcef.xml.
abstract class JcefView : Disposable {
    protected val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val queue = ArrayList<String>()
    private var ready = false

    // Once the page has loaded, nothing may navigate the view: a link, a file or text dropped on it, a form, a
    // script. Until that first load is over every navigation is allowed, not only one: the embedded browser may
    // take several requests to load the page, and cancelling one of them leaves a blank view.
    @Volatile private var pageLoaded = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(this, query)
        // The page's messages are handled on the EDT, parsed first: a malformed one is dropped, not thrown at the
        // browser's thread.
        query.addHandler { message ->
            parseObject(message)?.let { json -> ApplicationManager.getApplication().invokeLater({ onMessage(json) }, { Disposer.isDisposed(this) }) }
            null
        }
        // Registered before the page loads: until it has, navigations go through; afterwards every one is cancelled.
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean) = pageLoaded

            override fun onOpenURLFromTab(cefBrowser: CefBrowser?, frame: CefFrame?, targetUrl: String?, userGesture: Boolean) = true
        }, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(cefBrowser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                if (!isLoading) pageLoaded = true
            }
        }, browser.cefBrowser)
    }

    // The page, given the script that defines window.kestraPost, the way back to onMessage.
    protected abstract fun page(bridge: String): String

    // A message of the page, on the EDT.
    protected abstract fun onMessage(json: JsonObject)

    // Called once by the subclass when it is constructed: the page can post a message from here on.
    protected fun load() = browser.loadHTML(page("window.kestraPost = function (msg) { ${query.inject("msg")} };"))

    // The page said it is ready: runs what the view starts with, then the calls made before. EDT.
    protected fun pageReady(vararg first: String) {
        ready = true
        first.forEach(::execute)
        queue.forEach(::execute)
        queue.clear()
    }

    // Calls made before the page is ready are replayed in order once it is. EDT.
    protected fun call(script: String) {
        if (ready) execute(script) else queue += script
    }

    protected fun execute(script: String) = browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)

    override fun dispose() = Unit

    companion object {
        // What the page sends is its own business: anything that is not a JSON object is no message.
        internal fun parseObject(message: String): JsonObject? =
            runCatching { JsonParser.parseString(message) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

        // A string field, null when it is missing or is an object or an array: asString would throw on those.
        fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

        // The page is a single document: its scripts and styles are inlined at the marker /*NAME*/ they are named for.
        fun assemble(folder: String, template: String, bridge: String, parts: Map<String, String>): String {
            fun resource(name: String) = JcefView::class.java.getResource("/$folder/$name")!!.readText()
            var html = resource(template).replace("/*BRIDGE*/", bridge)
            parts.forEach { (marker, file) -> html = html.replace("/*$marker*/", resource(file)) }
            return html
        }

        fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)

        // A tool window of a runtime without JCEF (a custom runtime, a remote client) says so instead of a view.
        // True when it did, and the caller must not build the view.
        fun showUnavailable(toolWindow: ToolWindow, what: String): Boolean {
            if (JBCefApp.isSupported()) return false
            val label = JBLabel("$what needs the embedded browser (JCEF), which this IDE runtime does not provide.", SwingConstants.CENTER)
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, "", false))
            return true
        }
    }
}
