package io.kestra.jetbrains

import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.api.KestraVersion
import io.kestra.jetbrains.api.Multipart
import io.kestra.jetbrains.api.SseFrame
import io.kestra.jetbrains.api.SseParser
import io.kestra.jetbrains.completion.ExpressionContext
import io.kestra.jetbrains.completion.ExpressionQuery
import io.kestra.jetbrains.completion.PebbleFunction
import io.kestra.jetbrains.run.LogEntry
import io.kestra.jetbrains.run.RunModel
import io.kestra.jetbrains.run.StateBucket
import io.kestra.jetbrains.run.formatDuration
import io.kestra.jetbrains.run.logLevelRank
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {
    @Test
    fun apiBaseAcceptsUiAndApiRoots() {
        assertEquals("http://localhost:8080/api/v1", KestraUrls.apiBase("http://localhost:8080/"))
        assertEquals("http://localhost:8080/api/v1", KestraUrls.apiBase("http://localhost:8080/api/v1/"))
        assertEquals("", KestraUrls.apiBase("  "))
    }

    @Test
    fun tenantIsInsertedAfterApiPrefix() {
        assertEquals("http://h/api/v1/main", KestraUrls.withTenant("http://h/api/v1", "main"))
        assertEquals("http://h/api/v1", KestraUrls.withTenant("http://h/api/v1", ""))
        assertEquals("http://h/ui/main/executions/ns/f/e", KestraUrls.executionPage("http://h/api/v1", "", "ns", "f", "e"))
    }

    @Test
    fun segmentEncodesUnsafeCharacters() {
        assertEquals("company.team", KestraUrls.segment("company.team"))
        assertEquals("a%20b%2Fc", KestraUrls.segment("a b/c"))
        assertEquals("%C3%A9", KestraUrls.segment("é"))
    }

    @Test
    fun sseParserUsesIdAsFrameName() {
        val parser = SseParser()
        val frames = listOf("id:start", "data:{}", "", ": keep-alive", "", "id:progress", "data:{\"a\":1}", "\r").mapNotNull(parser::feed)
        assertEquals(listOf(SseFrame("start", "{}"), SseFrame("progress", "{\"a\":1}")), frames)
    }

    @Test
    fun multipartContainsTextAndFileParts() {
        val body = Multipart.build(listOf(FormPart.Text("user", "Ana"), FormPart.File("doc", "a.txt", "hi".toByteArray())), "B")
        assertEquals("multipart/form-data; boundary=B", body.contentType)
        val text = body.bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("Content-Disposition: form-data; name=\"user\"\r\n\r\nAna\r\n"))
        assertTrue(text.contains("name=\"doc\"; filename=\"a.txt\""))
        assertTrue(text.endsWith("--B--\r\n"))
    }

    @Test
    fun majorVersion() {
        assertEquals(2, KestraVersion.major("2.0.10"))
        assertEquals(1, KestraVersion.major("1.3.47-SNAPSHOT"))
        assertNull(KestraVersion.major(null))
    }

    @Test
    fun expressionQueryDetectsContext() {
        assertNull(ExpressionQuery.of("message: hello"))
        assertNull(ExpressionQuery.of("message: {{ inputs.user }} "))
        assertEquals(ExpressionQuery.Root("inp"), ExpressionQuery.of("message: {{ inp"))
        assertEquals(ExpressionQuery.Member("outputs.greet", "va"), ExpressionQuery.of("message: \"{{ outputs.greet.va"))
        assertEquals(ExpressionQuery.Filter("up"), ExpressionQuery.of("message: {{ inputs.user | up"))
    }

    @Test
    fun expressionContextMembers() {
        val context = ExpressionContext.parse(JsonParser.parseString(
            """{"taskOutputs":["outputs.fetch.uri","outputs['my-task'].value"],"inputs":["inputs.user"],"secrets":["secret('A')"]}"""
        ))!!
        assertEquals(listOf("outputs", "inputs"), context.rootNames())
        assertEquals(listOf("fetch", "greet"), context.membersOf("outputs", listOf("greet")))
        assertEquals(listOf("uri"), context.childrenOf("outputs.fetch"))
        assertEquals(listOf("secret('A')"), context.secrets)
    }

    @Test
    fun functionInsertTextPrefillsDefaults() {
        val functions = PebbleFunction.parse(JsonParser.parseString(
            """["now", {"name":"secret","arguments":[{"name":"key","defaultValue":"'KEY'"},{"name":"namespace","defaultValue":null}]}]"""
        ))
        assertEquals("now()", functions[0].insertText())
        assertEquals("secret(key='KEY')", functions[1].insertText())
    }

    @Test
    fun logEntryFormatAndKey() {
        val entry = LogEntry.of(JsonParser.parseString(
            """{"timestamp":"2026-11-16T10:11:12.345678Z","level":"WARN","taskId":"greet","message":"Hi","taskRunId":"r","attemptNumber":0,"index":3}"""
        ).asJsonObject)
        assertEquals("10:11:12.345  [WARN]  [greet] Hi", entry.format())
        assertEquals("r-0-3", entry.key)
    }

    @Test
    fun runModelGroupsLogsByTaskRun() {
        fun log(run: String, task: String, index: Int, level: String = "INFO") =
            LogEntry("2026-11-16T10:00:0${index}Z", level, task, "line $index", run, "0", "$index")
        val model = RunModel("company.team.hello")
        model.logs(listOf(log("r1", "each", 0), log("", "", 1)))
        model.taskState("r1", "each", "RUNNING", null, "a")
        model.taskState("r2", "each", "FAILED", "1.00s", "b")
        model.logs(listOf(log("r1", "each", 2, "ERROR")))

        val tasks = model.snapshot().tasks
        assertEquals(listOf("each (a)", "flow", "each (b)"), tasks.map { it.label })
        assertEquals(listOf("line 0", "line 2"), tasks[0].logs.map { it.message })
        assertEquals("1.00s", tasks[2].duration)
        assertEquals(3, model.copyText().lines().size)
    }

    @Test
    fun runModelDropsOldestLogsPastTheCap() {
        val model = RunModel("f")
        model.logs((0..RunModel.MAX_LOGS).map { LogEntry(null, "INFO", "t", "$it", "r", "0", "$it") })
        val snapshot = model.snapshot()
        assertTrue(snapshot.truncated)
        assertEquals(RunModel.MAX_LOGS, snapshot.tasks.single().logs.size)
        assertEquals("1", snapshot.tasks.single().logs.first().message)
    }

    @Test
    fun statesLevelsAndDurations() {
        assertEquals(StateBucket.FAILED, StateBucket.of("KILLED"))
        assertEquals(StateBucket.NEUTRAL, StateBucket.of("SOMETHING_NEW"))
        assertNull(StateBucket.of(""))
        assertTrue(logLevelRank("ERROR") > logLevelRank("WARN"))
        assertEquals(0, logLevelRank("TRACE"))
        assertEquals("0.11s", formatDuration(0.106))
        assertEquals("1h 1m 5s", formatDuration(3665.0))
    }
}
