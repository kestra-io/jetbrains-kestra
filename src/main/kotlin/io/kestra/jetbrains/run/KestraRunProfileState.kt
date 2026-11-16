package io.kestra.jetbrains.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.filters.UrlFilter
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project

class KestraRunProfileState(private val project: Project, private val flowFile: String) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { filters(UrlFilter()) }.console
        val handler = KestraExecutionHandler(project, flowFile)
        console.attachToProcess(handler)
        ProcessTerminatedListener.attach(handler, project, "\nRun finished with exit code \$EXIT_CODE\$\n")
        handler.startNotify()
        return DefaultExecutionResult(console, handler)
    }
}
