package io.kestra.jetbrains.schema

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.jsonSchema.extension.JsonSchemaCompletionCustomizer
import com.jetbrains.jsonSchema.impl.JsonSchemaObject
import io.kestra.jetbrains.flow.FlowYaml
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

// The flow schema lists tasks, triggers and plugin sub-objects as unions of every plugin class, each fixing
// its `type`. The IDE's basic completion offers the properties of every branch, so keep only the branches
// matching the `type` already written in the mapping, as the Red Hat YAML server does in VS Code.
class KestraSchemaCompletionCustomizer : JsonSchemaCompletionCustomizer {
    override fun isApplicable(file: PsiFile): Boolean = file is YAMLFile && FlowYaml.isFlow(file)

    override fun acceptsPropertyCompletionItem(propertySchema: JsonSchemaObject, completionElement: PsiElement): Boolean {
        val accepted = propertySchema.getPropertyByName("type")?.enum?.map { it.toString().trim('"') } ?: return true
        val declared = declaredType(completionElement) ?: return true
        return declared in accepted
    }

    // The `type` of the mapping being completed. While a new key is typed it can be parsed as the
    // continuation of the type scalar, so only the first word counts.
    private fun declaredType(element: PsiElement): String? {
        val mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java, false) ?: return null
        val value = mapping.getKeyValueByKey("type")?.valueText ?: return null
        return value.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
    }
}
