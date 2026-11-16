package io.kestra.jetbrains.run

import com.google.gson.JsonObject

data class LogEntry(
    val timestamp: String?,
    val level: String?,
    val taskId: String?,
    val message: String?,
    val taskRunId: String?,
    val attemptNumber: String?,
    val index: String?,
) {
    // The follow stream can re-emit a line where its backlog and live tail overlap. Mirrors the core UI's key.
    val key: String
        get() = if (index != null) "$taskRunId-$attemptNumber-$index" else "$taskRunId-$attemptNumber-$timestamp-$message"

    fun format(): String {
        val time = timestamp.orEmpty().substringAfter('T', "").removeSuffix("Z").take(12).padEnd(12)
        val levelText = "[${(level ?: "INFO").uppercase()}]".padEnd(8)
        val task = taskId?.let { "[$it] " }.orEmpty()
        return "$time  $levelText$task${message.orEmpty()}"
    }

    companion object {
        fun of(obj: JsonObject): LogEntry {
            fun text(name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            return LogEntry(text("timestamp"), text("level"), text("taskId"), text("message"), text("taskRunId"), text("attemptNumber"), text("index"))
        }
    }
}
