package io.kestra.jetbrains

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import io.kestra.jetbrains.actions.SaveFlowAction
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.run.KestraExecutionHandler
import io.kestra.jetbrains.run.RunModel
import io.kestra.jetbrains.run.KestraRunLineMarkerContributor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunTest : KestraTestCase() {
    private fun execution(state: String, taskState: String) =
        """{"id":"exec1","state":{"current":"$state"},"taskRunList":[{"id":"tr1","taskId":"greet","state":{"current":"$taskState","histories":[{"date":"2026-11-16T10:00:00Z"},{"date":"2026-11-16T10:00:01.5Z"}]}}]}"""

    fun testRunValidatesDeploysExecutesAndStreams() {
        kestra.json("POST", "/flows/validate") { "[{}]" }
        kestra.json("POST", "/flows") { """{"revision":1}""" }
        kestra.json("POST", "/executions/company.team/hello") { """{"id":"exec1"}""" }
        kestra.sse("GET", "/executions/exec1/follow") {
            listOf("start" to "{}", "progress" to execution("RUNNING", "RUNNING"), "progress" to execution("SUCCESS", "SUCCESS"), "end" to "{}")
        }
        kestra.sse("GET", "/logs/exec1/follow") {
            val log = """{"timestamp":"2026-11-16T10:00:01Z","level":"INFO","taskId":"greet","message":"Hello Ana","taskRunId":"tr1","attemptNumber":0,"index":0}"""
            listOf("start" to "{}", "progress" to log, "progress" to log)
        }

        val file = myFixture.configureByText("flow.yml", FLOW)
        val (output, exitCode) = run(file.virtualFile.url) { listOf(FormPart.Text("user", "Ana")) }

        assertEquals(output, 0, exitCode)
        assertTrue(output, output.contains("Deployed revision 1"))
        // A PUT to a missing flow can hang Kestra 1.3 (kestra-io/kestra#17620), so a new flow is only POSTed.
        assertTrue(kestra.requests.none { it.method == "PUT" })
        assertTrue(output, output.contains("Execution exec1"))
        assertTrue(output, output.contains("${kestra.url}/ui/main/executions/company.team/hello/exec1"))
        assertTrue(output, output.contains("[greet] SUCCESS 1.50s"))
        assertEquals(output, 1, Regex("Hello Ana").findAll(output).count())
        assertTrue(output, output.contains("State SUCCESS"))

        val start = kestra.requests.single { it.path == "/executions/company.team/hello" }
        assertTrue(start.headers["content-type"]!!.startsWith("multipart/form-data"))
        assertTrue(start.body.contains("name=\"user\"\r\n\r\nAna"))
        assertTrue(kestra.requests.any { it.path == "/logs/exec1/follow" })
    }

    fun testValidationErrorsStopTheRun() {
        kestra.json("POST", "/flows/validate") { """[{"constraints":"tasks[0].type: Invalid type: nope"}]""" }
        val file = myFixture.configureByText("flow.yml", FLOW)
        val (output, exitCode) = run(file.virtualFile.url) { emptyList() }
        assertEquals(1, exitCode)
        assertTrue(output, output.contains("Invalid type: nope"))
        assertTrue(kestra.requests.none { it.method == "PUT" || it.path.startsWith("/executions") })
    }

    fun testCancelledInputsDoNotStartAnExecution() {
        kestra.json("POST", "/flows/validate") { "[{}]" }
        kestra.json("GET", "/flows/company.team/hello") { "{}" }
        kestra.json("PUT", "/flows/company.team/hello") { """{"revision":2}""" }
        val file = myFixture.configureByText("flow.yml", FLOW)
        val (output, exitCode) = run(file.virtualFile.url) { null }
        assertEquals(1, exitCode)
        assertTrue(output, output.contains("Run cancelled"))
        assertTrue(kestra.requests.none { it.path.startsWith("/executions") })
    }

    fun testRetriedTaskGetsADuration() {
        kestra.json("POST", "/flows/validate") { "[{}]" }
        kestra.json("GET", "/flows/company.team/hello") { "{}" }
        kestra.json("PUT", "/flows/company.team/hello") { """{"revision":1}""" }
        kestra.json("POST", "/executions/company.team/hello") { """{"id":"exec1"}""" }
        kestra.sse("GET", "/executions/exec1/follow") {
            listOf("progress" to execution("RUNNING", "RETRIED"), "progress" to execution("SUCCESS", "RETRIED"))
        }
        kestra.sse("GET", "/logs/exec1/follow") { listOf("start" to "{}") }

        val file = myFixture.configureByText("flow.yml", FLOW)
        val (output, _) = run(file.virtualFile.url) { emptyList() }
        assertTrue(output, output.contains("[greet] RETRIED 1.50s"))
    }

    // The creation request is held until the test releases it, so Stop is pressed while it is in flight.
    private fun holdCreation(release: CountDownLatch) {
        kestra.json("POST", "/flows/validate") { "[{}]" }
        kestra.json("GET", "/flows/company.team/hello") { "{}" }
        kestra.json("PUT", "/flows/company.team/hello") { """{"revision":1}""" }
        kestra.handle("POST", "/executions/company.team/hello") { exchange, _ ->
            release.await(30, TimeUnit.SECONDS)
            // Gone when Stop abandoned the request: nobody listens, which is the point.
            runCatching { FakeKestra.respond(exchange, 200, """{"id":"exec1"}""") }
        }
    }

    private fun startAndStopDuringCreation(release: CountDownLatch, stopWait: java.time.Duration = java.time.Duration.ofSeconds(30)): Pair<KestraExecutionHandler, RunRecorder> {
        holdCreation(release)
        val file = myFixture.configureByText("flow.yml", FLOW)
        val model = RunModel("company.team.hello")
        val output = RunRecorder(model)
        val handler = KestraExecutionHandler(project, file.virtualFile.url, model, stopWait) { emptyList() }
        handler.startNotify()
        waitUntil { kestra.requests.any { it.method == "POST" && it.path == "/executions/company.team/hello" } }
        handler.destroyProcess()
        // The stop path saw no execution id yet: only then does the run thread own the kill. Not a sleep.
        waitUntil { output.text().contains("Stopping: waiting for the instance to create the execution") }
        return handler to output
    }

    fun testStopWhileTheExecutionIsBeingCreatedKillsItOnce() {
        val created = CountDownLatch(1)
        kestra.json("DELETE", "/executions/exec1/actions/kill") { "{}" }
        val (handler, output) = startAndStopDuringCreation(created)
        created.countDown()
        waitUntil { handler.isProcessTerminated }

        assertEquals(output.text(), 1, kestra.requests.count { it.method == "DELETE" })
        assertEquals("/executions/exec1/actions/kill", kestra.requests.single { it.method == "DELETE" }.path)
        assertTrue(output.text(), kestra.requests.none { it.path.endsWith("/follow") })
        // The run ends with a state, not just silence.
        assertTrue(output.text(), output.text().contains("State KILLED"))
    }

    fun testStopAbandonsACreationRequestThatNeverAnswers() {
        val never = CountDownLatch(1)
        try {
            val (handler, output) = startAndStopDuringCreation(never, java.time.Duration.ofMillis(100))
            // Terminates while the instance still holds the request: the handler does not wait for it.
            waitUntil { handler.isProcessTerminated }
            assertTrue(output.text(), output.text().contains("Stopped without an answer from the instance"))
            // The phase left "Stopping: waiting…", which would otherwise stay on screen next to the final state.
            assertEquals("Stopped: the instance did not answer", output.text().lines().last { it.startsWith("Stopp") })
            assertTrue(output.text(), output.text().contains("State UNKNOWN"))
            assertTrue(kestra.requests.none { it.method == "DELETE" })
        } finally {
            never.countDown()
        }
    }

    fun testStopBeforeTheExecutionShowsWhatHappened() {
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        kestra.handle("POST", "/flows/validate") { exchange, _ ->
            validating.countDown()
            release.await(30, TimeUnit.SECONDS)
            runCatching { FakeKestra.respond(exchange, 200, "[{}]") }
        }
        kestra.json("GET", "/flows/company.team/hello") { "{}" }
        kestra.json("PUT", "/flows/company.team/hello") { """{"revision":1}""" }
        val file = myFixture.configureByText("flow.yml", FLOW)
        val model = RunModel("company.team.hello")
        val output = RunRecorder(model)
        val handler = KestraExecutionHandler(project, file.virtualFile.url, model) { emptyList() }
        handler.startNotify()
        assertTrue(validating.await(30, TimeUnit.SECONDS))
        handler.destroyProcess()
        waitUntil { output.text().contains("Stopping…") }
        release.countDown()
        waitUntil { handler.isProcessTerminated }

        assertTrue(output.text(), output.text().contains("Run stopped before the execution started"))
        assertTrue(kestra.requests.none { it.path.startsWith("/executions") })
    }

    fun testOnlyFilesGetTheLongUploadTimeout() {
        val text = listOf(FormPart.Text("user", "Ana"))
        assertEquals(java.time.Duration.ofSeconds(30), KestraApi.instance.uploadTimeout(text))
        assertEquals(java.time.Duration.ofMinutes(10), KestraApi.instance.uploadTimeout(text + FormPart.LocalFile("doc", java.nio.file.Path.of("x"))))
    }

    fun testKillUsesTheRouteOfTheInstanceVersion() {
        kestra.json("DELETE", "/executions/exec1/actions/kill") { "{}" }
        kestra.json("DELETE", "/executions/exec1/kill") { "{}" }

        assertTrue(KestraApi.instance.killExecution("exec1").ok)
        assertEquals("/executions/exec1/actions/kill", kestra.requests.last { it.method == "DELETE" }.path)
    }

    fun testKillOnKestra1UsesTheLegacyRoute() {
        kestraVersion = "1.3.47"
        kestra.json("DELETE", "/executions/exec1/kill") { "{}" }

        assertTrue(KestraApi.instance.killExecution("exec1").ok)
        assertEquals(listOf("/executions/exec1/kill"), kestra.requests.filter { it.method == "DELETE" }.map { it.path })
    }

    // /configs failed: the 2.x route is tried first and a 404 falls back to the 1.x one.
    fun testKillWithUnknownVersionFallsBackOnA404() {
        kestraVersion = null
        kestra.json("DELETE", "/executions/exec1/kill") { "{}" }

        assertTrue(KestraApi.instance.killExecution("exec1").ok)
        assertEquals(
            listOf("/executions/exec1/actions/kill", "/executions/exec1/kill"),
            kestra.requests.filter { it.method == "DELETE" }.map { it.path },
        )
    }

    fun testFileInputsAreStreamedFromDisk() {
        kestra.json("POST", "/executions/company.team/hello") { """{"id":"exec1"}""" }
        val file = java.nio.file.Files.createTempFile("kestra-input", ".txt").also { java.nio.file.Files.writeString(it, "file-content") }
        try {
            assertTrue(KestraApi.instance.createExecution("company.team", "hello", listOf(FormPart.Text("user", "Ana"), FormPart.LocalFile("doc", file))).ok)
        } finally {
            java.nio.file.Files.delete(file)
        }
        val request = kestra.requests.single { it.path == "/executions/company.team/hello" }
        assertTrue(request.headers["content-type"]!!.startsWith("multipart/form-data"))
        assertTrue(request.body, request.body.contains("name=\"doc\"; filename=\"${file.fileName}\""))
        assertTrue(request.body, request.body.contains("\r\n\r\nfile-content\r\n--"))
        assertTrue(request.body, request.body.contains("name=\"user\"\r\n\r\nAna\r\n"))
    }

    fun testSaveFlowUsesTheIdOfTheEditedText() {
        kestra.json("GET", "/flows/company.team/renamed") { "{}" }
        kestra.json("PUT", "/flows/company.team/renamed") { """{"revision":3}""" }
        val file = myFixture.configureByText("flow.yml", FLOW)
        // The document changes, the PSI is not committed: it still says "hello".
        WriteAction.run<RuntimeException> { myFixture.editor.document.setText(FLOW.replace("id: hello", "id: renamed")) }
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PSI_FILE, file)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PROJECT, project)
            .build()
        val action = SaveFlowAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))

        waitUntil { kestra.requests.any { it.method == "PUT" } }
        val put = kestra.requests.single { it.method == "PUT" }
        assertEquals("/flows/company.team/renamed", put.path)
        assertTrue(put.body.contains("id: renamed"))
    }

    fun testGutterIconOnTopLevelIdOnly() {
        val file = myFixture.configureByText("flow.yml", FLOW)
        val contributor = KestraRunLineMarkerContributor()
        val markers = PsiTreeUtil.collectElements(file) { contributor.getInfo(it) != null }
        assertEquals(1, markers.size)
        assertEquals(0, markers[0].textOffset)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue("condition not met in time", condition())
    }

    private fun run(path: String, inputs: (io.kestra.jetbrains.flow.Flow) -> List<FormPart>?): Pair<String, Int> {
        var exitCode = -1
        val model = RunModel("company.team.hello")
        val output = RunRecorder(model)
        val handler = KestraExecutionHandler(project, path, model, inputsProvider = inputs)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                exitCode = event.exitCode
            }
        })
        handler.startNotify()
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue("run did not finish:\n${output.text()}", handler.isProcessTerminated)
        return output.text() to exitCode
    }
}
