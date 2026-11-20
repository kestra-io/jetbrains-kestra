package io.kestra.jetbrains.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import io.kestra.jetbrains.settings.KestraSettings

class KestraRunProfileState(private val project: Project, private val flowFile: String, private val name: String) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val model = RunModel(name)
        val view = KestraRunView(model, KestraSettings.instance.logLevel)
        val handler = KestraExecutionHandler(project, flowFile, model)
        handler.startNotify()
        return DefaultExecutionResult(view, handler)
    }
}
