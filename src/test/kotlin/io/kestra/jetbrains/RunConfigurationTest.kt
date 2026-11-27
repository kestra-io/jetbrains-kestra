package io.kestra.jetbrains

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import io.kestra.jetbrains.run.KestraRunConfiguration
import io.kestra.jetbrains.run.KestraRunView
import io.kestra.jetbrains.run.KestraRunConfigurationProducer
import io.kestra.jetbrains.run.KestraRunConfigurationType

class RunConfigurationTest : KestraTestCase() {
    private val noInputs = FLOW.replace(Regex("inputs:\n(  .*\n)+"), "")

    fun testProducerCreatesConfigurationFromLocalFlowFile() {
        val dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("kestra", null)
        val flowFile = java.io.File(dir, "hello.yml").apply { writeText(noInputs) }
        val notFlow = java.io.File(dir, "compose.yml").apply { writeText("services:\n  web: {}\n") }
        fun contextOf(file: java.io.File): ConfigurationContext {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
            val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(vf)!!
            val data = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.MODULE, module)
                .add(com.intellij.execution.Location.DATA_KEY, com.intellij.execution.PsiLocation(psi.findElementAt(0)!!))
                .build()
            return ConfigurationContext.getFromContext(data, "test")
        }

        val produced = KestraRunConfigurationProducer().createConfigurationFromContext(contextOf(flowFile))!!
        val configuration = produced.configuration as KestraRunConfiguration
        assertEquals("company.team.hello", configuration.name)
        assertEquals(flowFile.canonicalPath, java.io.File(configuration.flowFile).canonicalPath)
        assertNull(KestraRunConfigurationProducer().createConfigurationFromContext(contextOf(notFlow)))
    }

    fun testRunConfigurationStreamsIntoRunConsole() {
        kestra.json("POST", "/flows/validate") { "[{}]" }
        kestra.json("GET", "/flows/company.team/hello") { "{}" }
        kestra.json("PUT", "/flows/company.team/hello") { """{"revision":7}""" }
        kestra.json("POST", "/executions/company.team/hello") { """{"id":"e1"}""" }
        kestra.sse("GET", "/executions/e1/follow") { listOf("progress" to """{"state":{"current":"SUCCESS"},"taskRunList":[{"id":"r","taskId":"greet","state":{"current":"SUCCESS"}}]}""") }
        kestra.sse("GET", "/logs/e1/follow") { listOf("progress" to """{"level":"INFO","message":"done","taskRunId":"r","index":0}""") }

        val file = myFixture.configureByText("flow.yml", noInputs)
        val type = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(KestraRunConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration("company.team.hello", type.factory)
        (settings.configuration as KestraRunConfiguration).flowFile = file.virtualFile.url

        // The platform launcher saves documents first, which the test harness forbids: drive the state directly.
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(project, executor, settings.configuration).build()
        val result = settings.configuration.getState(executor, environment)!!.execute(executor, environment.runner)!!
        val handler = result.processHandler
        val deadline = System.currentTimeMillis() + 20_000
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue(handler.isProcessTerminated)
        assertEquals(0, handler.exitCode)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val view = result.executionConsole as KestraRunView
        view.render()
        assertEquals("Success", view.badgeText())
        val sections = view.sectionTexts()
        assertEquals(sections.toString(), 1, sections.size)
        assertTrue(sections[0], sections[0].startsWith("greet Success"))
        assertTrue(sections[0], sections[0].contains("INFO  done"))
        Disposer.dispose(view)
    }
}
