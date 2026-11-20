package io.kestra.jetbrains.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.flow.Flow
import io.kestra.jetbrains.flow.FlowYaml
import org.jetbrains.yaml.psi.YAMLFile

// Completion inside {{ }}: variables, task outputs, inputs, secrets, KV pairs, namespace files, functions, filters.
class PebbleCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(YAMLFile::class.java)), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val query = queryAt(parameters) ?: return
            val file = parameters.originalFile
            val flow = FlowYaml.flow(file) ?: return
            val source = file.text
            val fileKey = file.virtualFile?.url ?: file.name
            val items = if (KestraApi.instance.isConfigured) {
                // Network calls run off the read action's thread, cancelled with the completion.
                val future = ApplicationManager.getApplication().executeOnPooledThread<List<LookupElement>> { items(query, flow, source, fileKey) }
                ApplicationUtil.runWithCheckCanceled(future, ProgressManager.getInstance().progressIndicator)
            } else {
                items(query, flow, null, fileKey)
            }
            val scoped = result.withPrefixMatcher(query.prefix)
            scoped.addAllElements(items)
            // Schema completions do not apply inside an expression.
            result.stopHere()
        }

        private fun items(query: ExpressionQuery, flow: Flow, source: String?, fileKey: String): List<LookupElement> {
            val service = ExpressionService.instance
            val online = source != null
            return when (query) {
                is ExpressionQuery.Filter -> if (online) service.filters().map { item(it, "Pebble filter", AllIcons.Nodes.Function) } else emptyList()
                is ExpressionQuery.Member -> {
                    val context = if (online) service.context(fileKey, source, flow) else null
                    val reported = context?.membersOf(query.base, flow.taskIds()).orEmpty()
                    val members = reported.ifEmpty { fallbackMembers(query.base, flow, online) }
                    members.map { item(it, null, AllIcons.Nodes.Field) }
                }
                is ExpressionQuery.Root -> {
                    val context = if (online) service.context(fileKey, source, flow) else null
                    val variables = ((context?.rootNames() ?: emptyList()) + VARIABLES).distinct().map { item(it, "Kestra variable", AllIcons.Nodes.Variable) }
                    val calls = context?.let {
                        it.secrets.map { call -> item(call, "Kestra secret", AllIcons.Nodes.Constant) } +
                            it.kvPairs.map { call -> item(call, "KV pair", AllIcons.Nodes.Constant) } +
                            it.namespaceFiles.map { call -> item(call, "Namespace file", AllIcons.Nodes.Constant) }
                    }.orEmpty()
                    val functions = if (online) service.functions().map(::functionItem) else emptyList()
                    variables + calls + functions
                }
            }
        }

        // 1.x, or before the flow first parses: resolve from the document, one level deep.
        private fun fallbackMembers(base: String, flow: Flow, online: Boolean): List<String> {
            val segments = base.split('.')
            if (segments.size == 2 && segments[0] == "outputs") {
                val type = flow.taskType(segments[1]) ?: return emptyList()
                return if (online) ExpressionService.instance.outputsOf(type).orEmpty() else emptyList()
            }
            if (segments.size != 1) {
                return emptyList()
            }
            return when (base) {
                "inputs" -> flow.inputIds()
                "outputs" -> flow.taskIds()
                "labels" -> flow.sectionKeys("labels")
                "vars" -> flow.sectionKeys("variables")
                else -> NESTED_FIELDS[base].orEmpty()
            }
        }

        private fun item(text: String, type: String?, icon: javax.swing.Icon) =
            LookupElementBuilder.create(text).withIcon(icon).withTypeText(type, true)

        private fun functionItem(function: PebbleFunction): LookupElement =
            LookupElementBuilder.create(function.insertText())
                .withPresentableText(function.name)
                .withTailText(function.insertText().removePrefix(function.name), true)
                .withLookupString(function.name)
                .withIcon(AllIcons.Nodes.Function)
                .withTypeText("Kestra function", true)
                .withInsertHandler { ctx, _ ->
                    // Put the caret inside the parentheses.
                    val text = ctx.document.getText(TextRange(ctx.startOffset, ctx.tailOffset))
                    val open = text.indexOf('(')
                    if (open >= 0) {
                        ctx.editor.caretModel.moveToOffset(ctx.startOffset + open + 1)
                    }
                }
    }

    companion object {
        // Used on 1.x, and on 2.0+ until the flow first parses.
        val VARIABLES = listOf(
            "outputs", "inputs", "vars", "flow", "execution", "trigger", "task", "taskrun",
            "labels", "envs", "globals", "parent", "parents", "error", "kestra",
        )

        private val NESTED_FIELDS = mapOf(
            "flow" to listOf("id", "namespace", "revision", "tenantId"),
            "execution" to listOf("id", "startDate", "state", "originalId", "outputs"),
            "task" to listOf("id", "type"),
            "taskrun" to listOf("id", "startDate", "attemptsCount", "parentId", "value", "iteration"),
            "error" to listOf("taskId", "message", "stackTrace"),
            "kestra" to listOf("environment", "url"),
        )

        fun queryAt(parameters: CompletionParameters): ExpressionQuery? {
            val document = parameters.editor.document
            val offset = parameters.offset
            val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
            return ExpressionQuery.of(document.getText(TextRange(lineStart, offset)))
        }
    }
}
