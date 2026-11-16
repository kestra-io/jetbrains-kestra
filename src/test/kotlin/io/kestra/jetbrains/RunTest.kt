package io.kestra.jetbrains

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.run.KestraExecutionHandler
import io.kestra.jetbrains.run.KestraRunLineMarkerContributor
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
        assertTrue(output, output.contains("[greet] SUCCESS in 1.50s"))
        assertEquals(output, 1, Regex("Hello Ana").findAll(output).count())
        assertTrue(output, output.contains("Execution SUCCESS"))

        val start = kestra.requests.single { it.path == "/executions/company.team/hello" }
        assertTrue(start.headers["content-type"]!!.startsWith("multipart/form-data"))
        assertTrue(start.body.contains("name=\"user\"\r\n\r\nAna"))
        assertTrue(kestra.requests.any { it.path == "/logs/exec1/follow?minLevel=INFO" })
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
        assertTrue(output, output.contains("Run cancelled."))
        assertTrue(kestra.requests.none { it.path.startsWith("/executions") })
    }

    fun testGutterIconOnTopLevelIdOnly() {
        val file = myFixture.configureByText("flow.yml", FLOW)
        val contributor = KestraRunLineMarkerContributor()
        val markers = PsiTreeUtil.collectElements(file) { contributor.getInfo(it) != null }
        assertEquals(1, markers.size)
        assertEquals(0, markers[0].textOffset)
    }

    private fun run(path: String, inputs: (io.kestra.jetbrains.flow.Flow) -> List<FormPart>?): Pair<String, Int> {
        val output = StringBuilder()
        var exitCode = -1
        val handler = KestraExecutionHandler(project, path, inputs)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                synchronized(output) { output.append(event.text) }
            }

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
        assertTrue("run did not finish:\n$output", handler.isProcessTerminated)
        return synchronized(output) { output.toString() } to exitCode
    }
}
