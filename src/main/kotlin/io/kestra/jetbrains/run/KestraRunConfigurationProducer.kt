package io.kestra.jetbrains.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import io.kestra.jetbrains.flow.FlowYaml

// Makes Run (Ctrl+Shift+F10 / ^⇧R) and the gutter icon work on any flow file, without creating a configuration first.
class KestraRunConfigurationProducer : LazyRunConfigurationProducer<KestraRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(KestraRunConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(
        configuration: KestraRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.psiLocation?.containingFile ?: return false
        val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path ?: return false
        val flow = FlowYaml.flow(file) ?: return false
        configuration.flowFile = path
        configuration.name = flow.uid
        return true
    }

    override fun isConfigurationFromContext(configuration: KestraRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.psiLocation?.containingFile ?: return false
        return file.virtualFile?.path == configuration.flowFile && FlowYaml.isFlow(file)
    }
}
