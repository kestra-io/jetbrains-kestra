package io.kestra.jetbrains.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import io.kestra.jetbrains.flow.FlowYaml
import io.kestra.jetbrains.vfs.KestraVirtualFile

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
        // A flow of a namespace opened from the instance is run from its kestra:// URL.
        val path = file.virtualFile?.let { if (it.isInLocalFileSystem) it.path else it.url.takeIf { _ -> it is KestraVirtualFile } } ?: return false
        val flow = FlowYaml.flow(file) ?: return false
        configuration.flowFile = path
        configuration.name = flow.uid
        return true
    }

    override fun isConfigurationFromContext(configuration: KestraRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.psiLocation?.containingFile ?: return false
        val virtualFile = file.virtualFile ?: return false
        return (virtualFile.path == configuration.flowFile || virtualFile.url == configuration.flowFile) && FlowYaml.isFlow(file)
    }
}
