package io.kestra.jetbrains.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFileFactory
import com.intellij.util.concurrency.AppExecutorUtil
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.api.SseFrame
import io.kestra.jetbrains.api.SseStream
import io.kestra.jetbrains.flow.Flow
import io.kestra.jetbrains.flow.FlowYaml
import org.jetbrains.yaml.YAMLLanguage
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

// Validates, deploys and runs a flow, then streams the execution into the run view's model.
// Stop kills the execution on the instance.
class KestraExecutionHandler(
    private val project: Project,
    private val flowFile: String,
    val model: RunModel,
    // Returns the input values, or null when the user cancels. Replaced in tests.
    private val inputsProvider: (Flow) -> List<FormPart>? = { flow -> askInputs(project, flow) },
) : ProcessHandler() {
    private val api get() = KestraApi.instance
    private val streams = CopyOnWriteArrayList<SseStream>()

    @Volatile private var executionId: String? = null
    @Volatile private var stopped = false

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread { runSafely() }
    }

    override fun destroyProcessImpl() {
        stopped = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val id = executionId
            if (id == null) {
                closeStreams()
                return@executeOnPooledThread
            }
            model.phase("Killing execution $id…")
            runCatching { api.killExecution(id) }
                .onSuccess { if (!it.ok) model.error("Kill failed (HTTP ${it.status}): ${it.message()}") }
                .onFailure { model.error("Kill failed: ${it.message}") }
            // The follow stream reports KILLED and closes; don't wait forever if it doesn't.
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ closeStreams() }, 30, TimeUnit.SECONDS)
        }
    }

    override fun detachProcessImpl() {
        stopped = true
        closeStreams()
        notifyProcessDetached()
    }

    override fun detachIsDefault() = false

    override fun getProcessInput(): OutputStream? = null

    private fun runSafely() {
        var exitCode = 1
        try {
            exitCode = run()
        } catch (e: KestraUnauthorizedException) {
            model.error(e.message.orEmpty())
            KestraNotifier.signInRequired(project, "${e.origin} requires authentication. Sign in to run the flow.")
        } catch (e: KestraApiException) {
            model.error(e.message.orEmpty())
        } catch (e: Exception) {
            model.error("Run failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            closeStreams()
            notifyProcessTerminated(exitCode)
        }
    }

    private fun run(): Int {
        val (source, flow) = readFlow() ?: run {
            model.error("$flowFile is not a flow: it must define 'id', 'namespace', and 'tasks' or 'triggers'.")
            return 1
        }
        model.phase("Validating on ${KestraApi.origin(api.apiBase())}…")

        // A failed validation call is not a verdict, the deploy surfaces the real error.
        val constraints = api.validate(source)?.flatMap { it.constraints }.orEmpty()
        if (constraints.isNotEmpty()) {
            model.phase("Validation failed")
            constraints.forEach(model::error)
            return 1
        }
        if (stopped) return 1
        model.phase("Deploying…")

        val deployed = api.upsertFlow(flow.namespace, flow.id, source)
        if (!deployed.ok) {
            model.error("Failed to deploy the flow (HTTP ${deployed.status}): ${deployed.message()}")
            return 1
        }
        val revision = deployed.json()?.asJsonObject?.get("revision")?.asString
        model.phase("Deployed${revision?.let { " revision $it" }.orEmpty()}")

        val inputs = (if (flow.inputs().isEmpty()) emptyList() else inputsProvider(flow)) ?: run {
            model.phase("Run cancelled")
            return 1
        }
        if (stopped) return 1

        val created = api.createExecution(flow.namespace, flow.id, inputs)
        if (!created.ok) {
            model.error("Failed to start the execution (HTTP ${created.status}): ${created.message()}")
            return 1
        }
        val id = created.json()?.asJsonObject?.get("id")?.asString ?: run {
            model.error("The instance did not return an execution id.")
            return 1
        }
        executionId = id
        model.execution(id, api.executionPage(flow.namespace, flow.id, id))

        val state = follow(id)
        model.state(state ?: "UNKNOWN")
        return if (state == "SUCCESS" || state == "WARNING") 0 else 1
    }

    // The editor buffer, saved or not, as the VS Code extension does.
    private fun readFlow(): Pair<String, Flow>? = ApplicationManager.getApplication().runReadAction<Pair<String, Flow>?> {
        val file = (if (flowFile.contains("://")) VirtualFileManager.getInstance().findFileByUrl(flowFile)
        else LocalFileSystem.getInstance().findFileByPath(flowFile)) ?: return@runReadAction null
        val source = FileDocumentManager.getInstance().getDocument(file)?.text ?: return@runReadAction null
        val psi = PsiFileFactory.getInstance(project).createFileFromText("flow.yml", YAMLLanguage.INSTANCE, source)
        FlowYaml.flow(psi)?.let { source to it }
    }

    // Returns the final state. Streams are always closed on exit, an open follow leaks memory on the server.
    // Logs are fetched at TRACE, the view filters them by level.
    private fun follow(id: String): String? {
        val logs = runCatching { api.followLogs(id, "TRACE") }
            .onFailure { model.error("Failed to stream logs: ${it.message}") }.getOrNull()?.also(streams::add)
        val seen = HashSet<String>()
        val logReader = logs?.let {
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { it.forEachFrame { frame -> onLogFrame(frame, seen) } }
            }
        }

        var lastState: String? = null
        val taskStates = HashMap<String, String>()
        try {
            api.followExecution(id).also(streams::add).forEachFrame { frame ->
                val state = onExecutionFrame(frame, taskStates)
                if (state != null && state != lastState) {
                    lastState = state
                    model.state(state)
                }
            }
        } catch (e: Exception) {
            // A dropped follow stream is not a verdict, the final fetch below decides.
            if (!stopped) model.phase("Execution stream disconnected: ${e.message}")
        } finally {
            // The log indexer is asynchronous, trailing lines can land after the execution ends.
            if (!stopped) Thread.sleep(LOG_GRACE.toMillis())
            logs?.close()
            runCatching { logReader?.get(5, TimeUnit.SECONDS) }
        }

        if (lastState !in TERMINAL) {
            lastState = runCatching { api.execution(id).json()?.asJsonObject?.getAsJsonObject("state")?.get("current")?.asString }
                .getOrNull() ?: lastState
        }
        return lastState
    }

    private fun onLogFrame(frame: SseFrame, seen: MutableSet<String>) {
        if (frame.name == "start" || frame.name == "end" || frame.data.isBlank() || frame.data == "{}") {
            return
        }
        val json = runCatching { JsonParser.parseString(frame.data) }.getOrNull() ?: return
        val entries = when {
            json.isJsonArray -> json.asJsonArray.filter { it.isJsonObject }.map { LogEntry.of(it.asJsonObject) }
            json.isJsonObject -> listOf(LogEntry.of(json.asJsonObject))
            else -> emptyList()
        }
        model.logs(entries.filter { seen.add(it.key) })
    }

    // The follow stream re-sends the full task list on every frame, so only report changes.
    private fun onExecutionFrame(frame: SseFrame, taskStates: MutableMap<String, String>): String? {
        if (frame.data.isBlank() || frame.data == "{}") {
            return null
        }
        val execution = runCatching { JsonParser.parseString(frame.data).asJsonObject }.getOrNull() ?: return null
        execution.getAsJsonArray("taskRunList")?.filter { it.isJsonObject }?.map { it.asJsonObject }?.forEach { taskRun ->
            val runId = taskRun.string("id") ?: return@forEach
            val taskId = taskRun.string("taskId") ?: return@forEach
            val state = taskRun.getAsJsonObject("state")
            val current = state?.string("current") ?: return@forEach
            if (taskStates.put(runId, current) != current) {
                model.taskState(runId, taskId, current, if (current in TERMINAL) duration(state) else null, taskRun.string("value"))
            }
        }
        return execution.getAsJsonObject("state")?.string("current")
    }

    private fun duration(state: JsonObject): String? {
        val histories = state.getAsJsonArray("histories")?.mapNotNull { it.asJsonObject.string("date") } ?: return null
        if (histories.size < 2) return null
        val seconds = runCatching { Duration.between(Instant.parse(histories.first()), Instant.parse(histories.last())).toMillis() / 1000.0 }.getOrNull()
            ?: return null
        return formatDuration(seconds)
    }

    private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun closeStreams() {
        streams.forEach(SseStream::close)
    }

    companion object {
        fun askInputs(project: Project, flow: Flow): List<FormPart>? {
            var parts: List<FormPart>? = null
            ApplicationManager.getApplication().invokeAndWait({
                val dialog = InputsDialog(project, flow.uid, flow.inputs())
                if (dialog.showAndGet()) {
                    parts = dialog.parts()
                }
            }, ModalityState.any())
            return parts
        }

        private val LOG_GRACE: Duration = Duration.ofSeconds(2)
        private val TERMINAL = setOf("SUCCESS", "WARNING", "FAILED", "KILLED", "CANCELLED", "SKIPPED")
    }
}
