package io.kestra.jetbrains.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.FormBuilder
import io.kestra.jetbrains.KestraIcons
import io.kestra.jetbrains.api.KestraApi
import javax.swing.JComponent

class KestraRunConfigurationType : ConfigurationTypeBase(
    ID, "Kestra flow", "Deploy and run a Kestra flow on the connected instance", KestraIcons.Kestra,
) {
    init {
        addFactory(KestraRunConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "KestraFlowRunConfiguration"
    }
}

class KestraRunConfigurationFactory(type: KestraRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = KestraRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration = KestraRunConfiguration(project, this, "Kestra flow")

    override fun getOptionsClass(): Class<out BaseState> = KestraRunConfigurationOptions::class.java
}

class KestraRunConfigurationOptions : RunConfigurationOptions() {
    var flowFile by string()
}

class KestraRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<KestraRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): KestraRunConfigurationOptions = super.getOptions() as KestraRunConfigurationOptions

    var flowFile: String
        get() = options.flowFile.orEmpty()
        set(value) {
            options.flowFile = value
        }

    override fun checkConfiguration() {
        if (flowFile.isBlank()) {
            throw RuntimeConfigurationError("Select the flow file to run.")
        }
        if (LocalFileSystem.getInstance().findFileByPath(flowFile) == null) {
            throw RuntimeConfigurationError("Flow file not found: $flowFile")
        }
        if (!KestraApi.instance.isConfigured) {
            throw RuntimeConfigurationError("Set the Kestra instance URL in Settings | Tools | Kestra.")
        }
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = Editor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = KestraRunProfileState(project, flowFile, name)

    private class Editor(project: Project) : SettingsEditor<KestraRunConfiguration>() {
        private val file = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Select Flow File"))
        }

        override fun resetEditorFrom(configuration: KestraRunConfiguration) {
            file.text = configuration.flowFile
        }

        override fun applyEditorTo(configuration: KestraRunConfiguration) {
            configuration.flowFile = file.text.trim()
        }

        override fun createEditor(): JComponent = FormBuilder.createFormBuilder().addLabeledComponent("Flow file:", file).panel
    }
}
