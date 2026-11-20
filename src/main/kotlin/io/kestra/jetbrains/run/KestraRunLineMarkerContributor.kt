package io.kestra.jetbrains.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import io.kestra.jetbrains.flow.FlowYaml
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue

// ▶ next to the flow's top-level `id:`.
class KestraRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != YAMLTokenTypes.SCALAR_KEY || element.text != "id") {
            return null
        }
        val keyValue = element.parent as? YAMLKeyValue ?: return null
        val file = element.containingFile ?: return null
        if (FlowYaml.root(file)?.getKeyValueByKey("id") != keyValue || !FlowYaml.isFlow(file)) {
            return null
        }
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
    }
}
