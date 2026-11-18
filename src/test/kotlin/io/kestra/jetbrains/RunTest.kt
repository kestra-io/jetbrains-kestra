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
        kestra.json("PUT", "/flows/company.team/hello", status = 404) { """{"message":"not found"}""" }
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
        assertTrue(output, output.contains("Execution exec1"))
        assertTrue(output, output.contains("${kestra.url}/ui/main/executions/company.team/hello/exec1"))
        assertTrue(output, output.contains("[greet] SUCCESS 1.50s"))
        assertEquals(output, 1, Regex("Hello Ana").findAll(output).count())
        assertTrue(output, output.contains("State SUCCESS"))

        val start = kestra.requests.single { it.path == "/executions/company.team/hello" }
        assertTrue(start.headers["content-type"]!!.startsWith("multipart/form-data"))
        assertTrue(start.body.contains("name=\"user\"\r\n\r\nAna"))
        assertTrue(kestra.requests.any { it.path == "/logs/exec1/follow?minLevel=TRACE" })
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
        kestra.json("PUT", "/flows/company.team/hello") { """{"revision":2}""" }
        val file = myFixture.configureByText("flow.yml", FLOW)
        val (output, exitCode) = run(file.virtualFile.url) { null }
        assertEquals(1, exitCode)
        assertTrue(output, output.contains("Run cancelled"))
        assertTrue(kestra.requests.none { it.path.startsWith("/executions") })
    }

    fun testRetriedTaskGetsADuration() {
        kestra.json("POST", "/flows/validate") { "[{}]" }
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

    fun testStopWhileTheExecutionIsBeingCreatedKillsIt() {
        val created = CountDownLatch(1)
        kestra.json("POST", "/flows/validate") { "[{}]" }
        kestra.json("PUT", "/flows/company.team/hello") { """{"revision":1}""" }
        kestra.handle("POST", "/executions/company.team/hello") { exchange, _ ->
            created.await(10, TimeUnit.SECONDS)
            FakeKestra.respond(exchange, 200, """{"id":"exec1"}""")
        }
        kestra.json("DELETE", "/executions/exec1/actions/kill") { "{}" }

        val file = myFixture.configureByText("flow.yml", FLOW)
        val model = RunModel("company.team.hello")
        val output = RunRecorder(model)
        val handler = KestraExecutionHandler(project, file.virtualFile.url, model) { emptyList() }
        handler.startNotify()
        waitUntil { kestra.requests.any { it.method == "POST" && it.path == "/executions/company.team/hello" } }
        // Stop pressed while the POST is in flight: no execution id to kill yet.
        handler.destroyProcess()
        Thread.sleep(200)
        created.countDown()
        waitUntil { handler.isProcessTerminated }

        assertTrue(output.text(), kestra.requests.any { it.method == "DELETE" && it.path == "/executions/exec1/actions/kill" })
        assertTrue(output.text(), kestra.requests.none { it.path.endsWith("/follow") })
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
        val handler = KestraExecutionHandler(project, path, model, inputs)
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
