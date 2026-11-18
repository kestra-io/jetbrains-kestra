package io.kestra.jetbrains.topology

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.Alarm
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraNotConfiguredException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.api.KestraUnreachableException
import io.kestra.jetbrains.flow.FlowYaml
import io.kestra.jetbrains.run.StateBucket
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

// Keeps the topology tool window in sync with the flow in the editor, and colors it during a run.
// All state is confined to the EDT, only the graph and icon requests run in the background.
@Service(Service.Level.PROJECT)
class KestraTopologyService(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val seq = AtomicInteger()
    private var renderer: TopologyRenderer? = null
    private var visible = false
    private var pending = false
    private var lastKey: String? = null
    private var lastFile: String? = null
    private var lastGraph: String? = null
    private var shownFlow: String? = null
    private var runFlow: String? = null
    private val runStates = LinkedHashMap<String, String>()

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh(0)
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.document == selectedEditor()?.document) scheduleRefresh()
            }
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this).apply {
            subscribe(KestraSettings.TOPIC, KestraInstanceListener {
                ApplicationManager.getApplication().invokeLater({ lastKey = null; scheduleRefresh(0) }, project.disposed)
            })
            subscribe(LafManagerListener.TOPIC, LafManagerListener { renderer?.setTheme(TopologyPage.theme().toString()) })
        }
    }

    fun attach(renderer: TopologyRenderer) {
        this.renderer = renderer
        lastKey = null
        lastGraph = null
        scheduleRefresh(0)
    }

    fun detach(renderer: TopologyRenderer) {
        if (this.renderer === renderer) this.renderer = null
    }

    // A hidden tool window skips the fetch and layout, the pending refresh runs when it is shown.
    fun setVisible(value: Boolean) {
        visible = value
        if (value && pending) scheduleRefresh(0)
    }

    fun scheduleRefresh(delay: Int = REFRESH_DEBOUNCE_MS) {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, delay)
    }

    // Completes once the graph, and its icons when some were missing, reached the renderer or were dropped as stale.
    fun refresh(): CompletableFuture<Unit> {
        val done = CompletableFuture.completedFuture(Unit)
        val renderer = renderer ?: return done
        if (!visible) {
            pending = true
            return done
        }
        pending = false
        val editor = selectedEditor() ?: return done.also { notice("Open a flow to preview its topology.") }
        val (url, flowUid) = ApplicationManager.getApplication().runReadAction<Pair<String, String>?> {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@runReadAction null
            val flow = FlowYaml.flow(file) ?: return@runReadAction null
            (file.virtualFile?.url ?: return@runReadAction null) to flow.uid
        } ?: return done.also { notice("Open a flow to preview its topology.") }
        // Switching tabs re-triggers a refresh without a text change.
        val key = "$url:${editor.document.modificationStamp}"
        if (key == lastKey && lastGraph != null) return done

        val source = editor.document.text
        val current = seq.incrementAndGet()
        val graphShown = CompletableFuture<Unit>()
        val iconsShown = CompletableFuture<Unit>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = load(source)
                ApplicationManager.getApplication().invokeLater({
                    try {
                        if (current != seq.get() || this.renderer !== renderer) return@invokeLater
                        when (result) {
                            is Load.Graph -> show(key, url, flowUid, result.elements)
                            // Keep the last good graph of this file through invalid intermediate edits.
                            is Load.Failed -> if (lastFile != url || lastGraph == null || result.always) notice(result.message)
                        }
                    } finally {
                        graphShown.complete(Unit)
                    }
                }, project.disposed)
                // The graph is drawn with the icons already known; the others follow once fetched.
                val withIcons = (result as? Load.Graph)?.withIcons?.invoke()
                if (withIcons == null) {
                    iconsShown.complete(Unit)
                    return@executeOnPooledThread
                }
                ApplicationManager.getApplication().invokeLater({
                    try {
                        if (current == seq.get() && this.renderer === renderer) showIcons(withIcons)
                    } finally {
                        iconsShown.complete(Unit)
                    }
                }, project.disposed)
            } catch (e: Throwable) {
                graphShown.completeExceptionally(e)
                iconsShown.completeExceptionally(e)
                throw e
            }
        }
        return graphShown.thenCombine(iconsShown) { _, _ -> }.also { lastRefresh = it }
    }

    // The latest refresh that loaded a graph. For tests, which wait on it instead of on the clock.
    @Volatile internal var lastRefresh: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        private set

    private sealed interface Load {
        // withIcons fetches the missing icons (network) and returns the graph again, null when none is missing.
        class Graph(val elements: String, val withIcons: (() -> String?)? = null) : Load
        class Failed(val message: String, val always: Boolean = false) : Load
    }

    private fun load(source: String): Load = try {
        val response = KestraApi.instance.flowGraph(source)
        val graph = response.json()
        val types = FlowGraph.elements(graph, emptyMap())?.pluginTypes
        when {
            !response.ok || types == null -> Load.Failed("The topology could not be generated: ${response.message()}")
            else -> {
                val icons = PluginIcons.instance
                Load.Graph(FlowGraph.elements(graph, icons.cached(types))!!.json.toString(), if (icons.hasMissing(types)) {
                    { FlowGraph.elements(graph, icons.icons(types))?.json?.toString() }
                } else null)
            }
        }
    } catch (_: KestraNotConfiguredException) {
        Load.Failed("Set the Kestra instance URL in Settings | Tools | Kestra to preview the topology.", always = true)
    } catch (e: KestraUnauthorizedException) {
        Load.Failed("${e.origin} requires authentication: sign in with Tools | Kestra | Sign In….", always = true)
    } catch (e: KestraUnreachableException) {
        Load.Failed(e.message.orEmpty())
    }

    private fun show(key: String, url: String, flowUid: String, elements: String) {
        lastKey = key
        if (elements != lastGraph || url != lastFile) {
            renderer?.render(elements)
            if (flowUid == runFlow) runStates.forEach { (task, state) -> renderer?.setTaskState(task, bucket(state)) }
        }
        lastFile = url
        lastGraph = elements
        shownFlow = flowUid
    }

    // Same graph, more icons: the nodes keep their place and the user's pan and zoom, nothing is laid out again.
    private fun showIcons(elements: String) {
        lastGraph = elements
        renderer?.updateIcons(elements)
    }

    private fun notice(text: String) {
        // A graph still being loaded or waiting for its icons must not be drawn over the notice.
        seq.incrementAndGet()
        lastKey = null
        lastGraph = null
        shownFlow = null
        renderer?.notice(text)
    }

    // Called by the run from its threads. The graph has one node per task, so it colors by task id.
    fun runStarted(flowUid: String) = onEdt {
        runFlow = flowUid
        runStates.clear()
        if (flowUid == shownFlow) renderer?.resetStates()
    }

    fun taskState(flowUid: String, taskId: String, state: String) = onEdt {
        if (flowUid != runFlow) return@onEdt
        runStates[taskId] = state
        if (flowUid == shownFlow) renderer?.setTaskState(taskId, bucket(state))
    }

    private fun bucket(state: String) = StateBucket.of(state)?.name?.lowercase()

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, project.disposed)

    // Moves the caret to the clicked task's `id:` line.
    fun reveal(taskId: String) {
        val editor = selectedEditor() ?: return
        val offset = ApplicationManager.getApplication().runReadAction<Int?> {
            PsiDocumentManager.getInstance(project).getPsiFile(editor.document)?.let { findTask(it, taskId) }?.textOffset
        } ?: return
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    // Inputs and outputs also declare ids, so they are not tasks to reveal.
    private fun findTask(file: PsiFile, taskId: String): YAMLKeyValue? {
        val root = FlowYaml.root(file) ?: return null
        return root.keyValues.filter { it.keyText !in NOT_TASKS }.firstNotNullOfOrNull { section ->
            FlowYaml.findItemById(section, taskId)?.let { (it.value as? YAMLMapping)?.getKeyValueByKey("id") }
        }
    }

    private fun selectedEditor(): Editor? = FileEditorManager.getInstance(project).selectedTextEditor

    override fun dispose() = Unit

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 600
        private val NOT_TASKS = setOf("inputs", "outputs", "labels", "variables")

        fun getInstance(project: Project): KestraTopologyService = project.service()
    }
}
