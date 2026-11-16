package io.kestra.jetbrains

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kestra.jetbrains.api.FormPart
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.completion.ExpressionService
import io.kestra.jetbrains.flow.FlowYaml
import io.kestra.jetbrains.run.KestraExecutionHandler
import io.kestra.jetbrains.run.RunModel
import io.kestra.jetbrains.schema.KestraSchemaService
import io.kestra.jetbrains.settings.AuthKind
import io.kestra.jetbrains.settings.KestraCredential
import io.kestra.jetbrains.settings.KestraCredentials
import io.kestra.jetbrains.settings.KestraSettings
import java.util.concurrent.TimeUnit

// Runs against a real instance: KESTRA_TEST_URL, plus KESTRA_TEST_USERNAME / KESTRA_TEST_PASSWORD for basic auth.
// Skipped when KESTRA_TEST_URL is not set. Creates flows in the jetbrains.test namespace and deletes them.
class LiveKestraTest : BasePlatformTestCase() {
    private val url: String? = System.getenv("KESTRA_TEST_URL")
    private val api get() = KestraApi.instance
    private val created = mutableListOf<String>()

    override fun runTestRunnable(testRunnable: com.intellij.util.ThrowableRunnable<Throwable>) {
        if (url.isNullOrBlank()) {
            println("LiveKestraTest skipped: KESTRA_TEST_URL is not set")
            return
        }
        super.runTestRunnable(testRunnable)
    }

    override fun setUp() {
        super.setUp()
        val url = url ?: return
        KestraSettings.instance.update(url, "", "INFO")
        System.getenv("KESTRA_TEST_USERNAME")?.let { user ->
            KestraCredentials.set(url, KestraCredential(AuthKind.BASIC, user, System.getenv("KESTRA_TEST_PASSWORD").orEmpty()))
        }
        ExpressionService.instance.reset()
    }

    override fun tearDown() {
        try {
            created.forEach { runCatching { api.request("DELETE", "/flows/jetbrains.test/${KestraUrls.segment(it)}") } }
            url?.let(KestraCredentials::clear)
            KestraSettings.instance.update("", "", "INFO")
        } finally {
            super.tearDown()
        }
    }

    // Generating the schema is heavy for the instance: download it once per run.
    private fun schema() {
        if (!schemaDownloaded) {
            KestraSchemaService.instance.download()
            schemaDownloaded = true
        }
        com.jetbrains.jsonSchema.ide.JsonSchemaService.Impl.get(project).reset()
    }

    private fun flow(id: String, body: String) = "id: $id\nnamespace: jetbrains.test\n$body".also { created.add(id) }

    fun testSignInAndSchema() {
        assertTrue(api.verify().ok)
        val user = System.getenv("KESTRA_TEST_USERNAME")
        if (user != null) {
            KestraCredentials.set(url!!, KestraCredential(AuthKind.BASIC, user, "wrong-password"))
            assertThrows(io.kestra.jetbrains.api.KestraUnauthorizedException::class.java) { api.verify() }
            KestraCredentials.clear(url)
            assertThrows(io.kestra.jetbrains.api.KestraUnauthorizedException::class.java) { api.verify() }
            KestraCredentials.set(url, KestraCredential(AuthKind.BASIC, user, System.getenv("KESTRA_TEST_PASSWORD").orEmpty()))
        }
        println("Live instance version: ${api.version()}")
        schema()
        val schema = KestraSchemaService.instance.schemaFile()!!
        println("Schema size: ${schema.length / 1024} KiB")
        assertTrue(String(schema.inputStream.readAllBytes()).contains("io.kestra.plugin.core.log.Log"))
    }

    // The instance schema is tens of MB: check the IDE uses it, narrowed to the declared task type.
    fun testSchemaDrivesCompletion() {
        schema()
        val source = flow("schema", "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.Log\n    log<caret>\n")
        myFixture.configureByText("schema.yml", source)
        val vf = myFixture.file.virtualFile
        val schemas = com.jetbrains.jsonSchema.ide.JsonSchemaService.Impl.get(project).getSchemaFilesForFile(vf)
        println("Schemas for flow: $schemas")
        val started = System.currentTimeMillis()
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        println("Property completion in ${System.currentTimeMillis() - started} ms: $items")
        // Narrowed to the Log task, not the union of every plugin ("log" is a word from the file).
        assertSameElements(items.filter { it != "log" }, "logLevel", "logToFile")

        // Nested unions narrow too: a provider of the AI agent declares its own type.
        myFixture.configureByText("ai.yml", flow("ai", "tasks:\n  - id: a\n    type: io.kestra.plugin.ai.agent.AIAgent\n    provider:\n      type: io.kestra.plugin.ai.provider.OpenAI\n      <caret>\n"))
        myFixture.completeBasic()
        val provider = myFixture.lookupElementStrings.orEmpty()
        println("Provider completion: $provider")
        assertContainsElements(provider, "apiKey", "modelName")
        assertDoesntContain(provider, "region", "accessKeyId")
    }

    fun testNamespaceFilesRoundTrip() {
        val files = io.kestra.jetbrains.api.NamespaceFilesApi()
        val ns = "jetbrains.test"
        try {
            files.write(ns, "/jb dir/a b.txt", "héllo".toByteArray())
            files.write(ns, "/jb dir/archive.zip", byteArrayOf(80, 75, 3, 4))
            assertEquals(setOf("a b.txt" to false, "archive.zip" to false), files.list(ns, "/jb dir").map { it.name to it.directory }.toSet())
            assertEquals("héllo", String(files.read(ns, "/jb dir/a b.txt")))
            files.move(ns, "/jb dir/a b.txt", "/jb dir/renamed.txt")
            files.createDirectory(ns, "/jb dir/sub")
            assertEquals(setOf("renamed.txt", "archive.zip", "sub"), files.list(ns, "/jb dir").map { it.name }.toSet())
            // The instance lists the namespaces that have flows.
            assertFalse(files.namespaces().isEmpty())
        } finally {
            runCatching { files.delete(ns, "/jb dir") }
        }
        val gone = runCatching { files.list(ns, "/jb dir") }.exceptionOrNull() as io.kestra.jetbrains.api.NamespaceFilesException
        assertEquals(404, gone.status)
    }

    fun testNamespaceBrowserSavesThroughTheEditor() {
        val files = io.kestra.jetbrains.api.NamespaceFilesApi()
        val fs = io.kestra.jetbrains.vfs.KestraFileSystem.instance
        val ns = "jetbrains.test"
        try {
            files.write(ns, "/jb browse/notes.md", "v1".toByteArray())
            val root = fs.root(io.kestra.jetbrains.vfs.KestraFileSystem.currentInstance()!!, ns)
            val dir = fs.list(root).single { it.name == "jb browse" }
            val notes = fs.list(dir).single { it.name == "notes.md" }
            assertEquals("v1", String(notes.contentsToByteArray()))
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(notes)!!
            com.intellij.openapi.application.WriteAction.run<RuntimeException> { document.setText("v2 é") }
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(document)
            val deadline = System.currentTimeMillis() + 10_000
            while (String(files.read(ns, "/jb browse/notes.md")) != "v2 é" && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("v2 é", String(files.read(ns, "/jb browse/notes.md")))
        } finally {
            runCatching { files.delete(ns, "/jb browse") }
        }
    }

    fun testValidateReportsConstraints() {
        val results = api.validate(flow("invalid", "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.Nope\n"))!!
        val constraints = results.flatMap { it.constraints }
        println("Constraints: $constraints")
        assertTrue(constraints.isNotEmpty())
        assertTrue(api.validate(flow("valid", "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.Log\n    message: hi\n"))!!.all { it.constraints.isEmpty() })
    }

    fun testExpressionContextOnlyOn2x() {
        val source = flow("expr", "inputs:\n  - id: user\n    type: STRING\ntasks:\n  - id: hello\n    type: io.kestra.plugin.core.debug.Return\n    format: hi\n")
        val psi = myFixture.configureByText("expr.yml", source)
        val context = ExpressionService.instance.context("live", source, FlowYaml.flow(psi)!!)
        if ((api.majorVersion() ?: 0) >= 2) {
            println("Expression roots: ${context!!.rootNames()}, outputs: ${context.childrenOf("outputs.hello")}")
            assertContainsElements(context.rootNames(), "inputs", "outputs")
            assertContainsElements(context.childrenOf("outputs.hello"), "value")
        } else {
            assertNull(context)
        }
        assertFalse(ExpressionService.instance.functions().isEmpty())
        assertContainsElements(ExpressionService.instance.filters(), "upper")
    }

    fun testRunWithInputsStreamsLogsToSuccess() {
        val source = flow("run", """
            inputs:
              - id: user
                type: STRING
              - id: doc
                type: FILE
            tasks:
              - id: greet
                type: io.kestra.plugin.core.log.Log
                message: "Hello {{ inputs.user }}"
              - id: size
                type: io.kestra.plugin.core.log.Log
                message: "File {{ read(inputs.doc) }}"
        """.trimIndent() + "\n")
        val (output, exitCode) = run(myFixture.configureByText("run.yml", source).virtualFile.url, stopAfter = null) {
            listOf(FormPart.Text("user", "JetBrains"), FormPart.File("doc", "doc.txt", "file-content".toByteArray()))
        }
        println(output)
        assertEquals(output, 0, exitCode)
        assertTrue(output.contains("Hello JetBrains"))
        assertTrue(output.contains("File file-content"))
        assertTrue(output.contains("State SUCCESS"))
    }

    fun testStopKillsTheExecution() {
        val source = flow("sleep", "tasks:\n  - id: wait\n    type: io.kestra.plugin.core.flow.Sleep\n    duration: PT2M\n")
        val (output, exitCode) = run(myFixture.configureByText("sleep.yml", source).virtualFile.url, stopAfter = "[wait] RUNNING") { emptyList() }
        println(output)
        assertEquals(1, exitCode)
        assertTrue(output, output.contains("Killing execution"))
        assertTrue(output, output.contains("State KILLED"))
    }

    fun testSchemaCompletesTaskTypes() {
        schema()
        myFixture.configureByText("types.yml", flow("types", "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.<caret>\n"))
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        println("Type completion: ${items.take(10)}")
        assertContainsElements(items, "io.kestra.plugin.core.log.Log")
    }

    fun testSchemaFlagsUnknownType() {
        schema()
        myFixture.enableInspections(com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection::class.java, org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection::class.java)
        myFixture.configureByText("bad.yml", flow("bad", "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.Nope\n"))
        val problems = myFixture.doHighlighting().mapNotNull { it.description }
        println("Schema problems: $problems")
        assertTrue(problems.any { it.contains("Value should be one of") || it.contains("io.kestra.plugin.core.log.Log") })
    }

    fun testZzHighlightingPerformance() {
        schema()
        myFixture.enableInspections(com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection::class.java, org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection::class.java)
        val cases = mapOf(
            "valid" to "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.Log\n    message: hi\n  - id: r\n    type: io.kestra.plugin.core.http.Request\n    uri: https://kestra.io\n",
            "missing-required" to "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.Log\n",
            "unknown-type" to "tasks:\n  - id: t\n    type: io.kestra.plugin.core.log.Nope\n",
            "no-type" to "tasks:\n  - id: t\n",
        )
        for ((name, body) in cases) {
            myFixture.configureByText("perf-$name.yml", flow("perf", body))
            val started = System.currentTimeMillis()
            val problems = myFixture.doHighlighting().mapNotNull { it.description }.map { it.take(80) }
            println("PERF $name: ${System.currentTimeMillis() - started} ms, problems=$problems")
        }
    }

    private fun run(path: String, stopAfter: String?, inputs: (io.kestra.jetbrains.flow.Flow) -> List<FormPart>?): Pair<String, Int> {
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
        var stopped = false
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(120)
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            if (stopAfter != null && !stopped && output.text().contains(stopAfter)) {
                handler.destroyProcess()
                stopped = true
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        assertTrue("run did not finish:\n${output.text()}", handler.isProcessTerminated)
        return output.text() to exitCode
    }

    companion object {
        private var schemaDownloaded = false
    }
}
