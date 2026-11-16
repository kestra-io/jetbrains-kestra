package io.kestra.jetbrains.topology

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import javax.swing.JComponent

// What the topology service draws on. The JCEF view in the IDE, a recorder in tests.
interface TopologyRenderer {
    fun render(elements: String)
    fun notice(text: String)
    fun setTaskState(taskId: String, bucket: String?)
    fun resetStates()
    fun setTheme(theme: String)
}

// Renders the graph in the embedded browser with the same Cytoscape and dagre layout as the VS Code extension.
class JcefTopologyView(private val onReveal: (String) -> Unit) : TopologyRenderer, Disposable {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val queue = ArrayList<String>()
    private var ready = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { message ->
            ApplicationManager.getApplication().invokeLater { handle(message) }
            null
        }
        browser.loadHTML(TopologyPage.html("window.kestraPost = function (msg) { ${query.inject("msg")} };"))
    }

    private fun handle(message: String) {
        val json = runCatching { JsonParser.parseString(message).asJsonObject }.getOrNull() ?: return
        when (json.get("type")?.asString) {
            "ready" -> {
                ready = true
                execute("kestraTopology.setTheme(${TopologyPage.theme()})")
                queue.forEach(::execute)
                queue.clear()
            }
            "reveal" -> json.get("taskId")?.asString?.let(onReveal)
        }
    }

    // Calls made before the page is ready are replayed in order once it is.
    private fun call(script: String) {
        if (ready) execute(script) else queue += script
    }

    private fun execute(script: String) = browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)

    override fun render(elements: String) = call("kestraTopology.render($elements)")

    override fun notice(text: String) = call("kestraTopology.notice(${JsonPrimitive(text)})")

    override fun setTaskState(taskId: String, bucket: String?) =
        call("kestraTopology.setTaskState(${JsonPrimitive(taskId)}, ${bucket?.let(::JsonPrimitive) ?: "null"})")

    override fun resetStates() = call("kestraTopology.resetStates()")

    override fun setTheme(theme: String) = call("kestraTopology.setTheme($theme)")

    override fun dispose() = Unit
}
