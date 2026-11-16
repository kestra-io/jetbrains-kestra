package io.kestra.jetbrains.run

import java.util.Locale

enum class StateBucket { SUCCESS, FAILED, WARNING, RUNNING, INFO, NEUTRAL, PENDING;

    companion object {
        // Same grouping as the Kestra UI and the VS Code run panel.
        fun of(state: String?): StateBucket? = when (state?.uppercase()) {
            null, "" -> null
            "SUCCESS" -> SUCCESS
            "FAILED", "KILLED" -> FAILED
            "WARNING", "PAUSED", "RETRYING" -> WARNING
            "RUNNING", "BREAKPOINT" -> RUNNING
            "CREATED", "RESTARTED", "RETRIED", "SUBMITTED" -> INFO
            "KILLING" -> PENDING
            else -> NEUTRAL
        }
    }
}

val LOG_LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")

// TRACE is 0, ERROR is 4. An unknown level ranks lowest, so it only shows at TRACE.
fun logLevelRank(level: String?): Int = LOG_LEVELS.indexOf(level?.uppercase()).let { if (it < 0) 0 else LOG_LEVELS.size - 1 - it }

fun titleCase(state: String) = state.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }

fun formatDuration(seconds: Double): String {
    if (seconds < 60) return "%.2fs".format(Locale.ROOT, seconds)
    var rest = Math.round(seconds)
    return listOf("h" to 3600L, "m" to 60L, "s" to 1L).mapNotNull { (label, size) ->
        val value = rest / size
        rest -= value * size
        if (value > 0) "$value$label" else null
    }.joinToString(" ")
}

class TaskRunView(val key: String, var taskId: String) {
    var value: String? = null
    var state: String? = null
    var duration: String? = null
    val logs = ArrayList<LogEntry>()

    val label get() = if (value.isNullOrEmpty()) taskId else "$taskId ($value)"
}

// Everything the run view shows, fed from the execution handler's threads. The view reads it on the EDT
// through snapshot(), so every access is synchronized and the version tells the view what changed.
class RunModel(val flow: String) {
    data class Snapshot(
        val version: Long,
        val phase: String,
        val state: String?,
        val executionUrl: String?,
        val errors: List<String>,
        val tasks: List<TaskSnapshot>,
        val truncated: Boolean,
    )

    data class TaskSnapshot(val key: String, val label: String, val state: String?, val duration: String?, val logs: List<LogEntry>)

    private val tasks = LinkedHashMap<String, TaskRunView>()
    private val errors = ArrayList<String>()
    private var phase = ""
    private var state: String? = null
    private var executionUrl: String? = null
    private var logCount = 0
    private var truncated = false
    private var version = 0L
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) = synchronized(this) { listeners += listener }

    fun phase(text: String) = change { phase = text }

    fun error(text: String) = change { errors += text }

    fun execution(id: String, url: String) = change {
        phase = "Execution $id"
        executionUrl = url
    }

    fun state(value: String) = change { state = value }

    fun taskState(taskRunId: String, taskId: String, state: String, duration: String?, value: String?) = change {
        val task = task(taskRunId, taskId)
        task.state = state
        if (duration != null) task.duration = duration
        if (!value.isNullOrEmpty()) task.value = value
    }

    fun logs(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        change {
            for (entry in entries) {
                task(entry.taskRunId.orEmpty(), entry.taskId.orEmpty()).logs += entry
                logCount++
            }
            // Oldest lines drop past the cap so the view stays responsive.
            while (logCount > MAX_LOGS) {
                tasks.values.firstOrNull { it.logs.isNotEmpty() }?.logs?.removeAt(0) ?: break
                logCount--
                truncated = true
            }
        }
    }

    @Synchronized
    fun snapshot() = Snapshot(
        version, phase, state, executionUrl, errors.toList(),
        tasks.values.map { TaskSnapshot(it.key, it.label, it.state, it.duration, it.logs.toList()) }, truncated,
    )

    @Synchronized
    fun copyText(): String = tasks.values.flatMap { it.logs }.joinToString("\n") { it.format() }

    // Sections are keyed by task run so loop iterations stay apart. Flow-level logs have no task run.
    private fun task(taskRunId: String, taskId: String): TaskRunView {
        val key = taskRunId.ifEmpty { taskId.ifEmpty { FLOW_KEY } }
        val task = tasks.getOrPut(key) { TaskRunView(key, "flow") }
        if (taskId.isNotEmpty()) task.taskId = taskId
        return task
    }

    private fun change(block: () -> Unit) {
        val toNotify = synchronized(this) {
            block()
            version++
            listeners.toList()
        }
        toNotify.forEach { it() }
    }

    companion object {
        const val MAX_LOGS = 5000
        private const val FLOW_KEY = "__flow__"
    }
}
