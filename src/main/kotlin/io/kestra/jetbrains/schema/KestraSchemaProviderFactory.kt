package io.kestra.jetbrains.schema

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import io.kestra.jetbrains.flow.FlowYaml
import org.jetbrains.yaml.YAMLFileType

class KestraSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(KestraFlowSchemaProvider(project))
}

class KestraFlowSchemaProvider(private val project: Project) : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean {
        if (file.fileType != YAMLFileType.YML || !KestraSchemaService.instance.hasSchema()) {
            return false
        }
        if (file.path.contains("/_flows/")) {
            return true
        }
        return ApplicationManager.getApplication().runReadAction<Boolean> {
            !project.isDisposed && PsiManager.getInstance(project).findFile(file)?.let(FlowYaml::isFlow) == true
        }
    }

    override fun getName(): String = "Kestra flow"

    override fun getSchemaFile(): VirtualFile? = KestraSchemaService.instance.schemaFile()

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
