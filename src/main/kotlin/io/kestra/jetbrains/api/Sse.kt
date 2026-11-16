package io.kestra.jetbrains.api

data class SseFrame(val name: String?, val data: String)

// Line-based SSE parser. Kestra names its frames with "id:", not the standard "event:".
class SseParser {
    private var event: String? = null
    private var id: String? = null
    private val data = mutableListOf<String>()

    // Returns a frame when the line ends one (a blank line), null otherwise.
    fun feed(rawLine: String): SseFrame? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            return flush()
        }
        when {
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("id:") -> id = line.substring(3).trim()
            line.startsWith("data:") -> data.add(line.substring(5).trim())
        }
        return null
    }

    fun flush(): SseFrame? {
        val name = event ?: id
        val frame = if (name == null && data.isEmpty()) null else SseFrame(name, data.joinToString("\n"))
        event = null
        id = null
        data.clear()
        return frame
    }
}
