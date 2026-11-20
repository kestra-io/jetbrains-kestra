package io.kestra.jetbrains.validation

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.ValidateResult
import io.kestra.jetbrains.flow.FlowYaml
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import java.time.Duration
import java.util.Collections

// What the annotator remembers between passes. A service, so the settings subscription ends with the plugin.
@Service(Service.Level.APP)
internal class ValidationState : Disposable {
    @Volatile var retryAfter = 0L

    // The daemon re-runs annotators on every highlighting pass, not only on edits.
    val cache: MutableMap<String, List<FlowIssue>> = lruMap()

    // The latest result per file.
    val last: MutableMap<String, List<FlowIssue>> = lruMap()

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(KestraSettings.TOPIC, KestraInstanceListener { clear() })
    }

    fun clear() {
        cache.clear()
        last.clear()
        retryAfter = 0
    }

    override fun dispose() = Unit

    private fun lruMap(): MutableMap<String, List<FlowIssue>> =
        Collections.synchronizedMap(object : LinkedHashMap<String, List<FlowIssue>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FlowIssue>>?) = size > 20
        })

    companion object {
        val instance: ValidationState get() = service()
    }
}

class FlowSource(val text: String, val instance: String, val file: String)

data class FlowIssue(val severity: HighlightSeverity, val message: String, val path: List<Any>?, val search: String?, val deprecated: Boolean = false)

// Runs the instance's validate endpoint on the buffer, the same check as `flow validate`.
class KestraValidationAnnotator : ExternalAnnotator<FlowSource, List<FlowIssue>>() {
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): FlowSource? = collectInformation(file)

    override fun collectInformation(file: PsiFile): FlowSource? {
        if (!KestraApi.instance.isConfigured || !FlowYaml.isFlow(file)) {
            return null
        }
        return FlowSource(file.text, KestraSettings.instance.url + "|" + KestraSettings.instance.tenant, file.virtualFile?.url ?: file.name)
    }

    override fun doAnnotate(source: FlowSource): List<FlowIssue>? {
        val state = ValidationState.instance
        val key = source.instance + "\n" + source.text
        state.cache[key]?.let { return it }
        // Skipped or failed, the last result of the file stays: an empty one would clear every error on a hiccup.
        if (System.currentTimeMillis() < state.retryAfter) {
            return state.last[source.file]
        }
        val results = try {
            validate(source.text)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            // Unreachable or signed out: don't retry on every highlighting pass.
            state.retryAfter = System.currentTimeMillis() + RETRY_MS
            LOG.debug("Kestra validation skipped: ${e.message}")
            null
        } ?: return state.last[source.file]
        return toIssues(results).also {
            state.cache[key] = it
            state.last[source.file] = it
        }
    }

    // The request waits on another thread, so a pass the daemon cancels (the user typed on) stops waiting
    // at once instead of piling up behind a slow instance for up to the timeout.
    private fun validate(text: String): List<ValidateResult>? {
        val indicator = ProgressManager.getInstance().progressIndicator
        if (indicator == null) return KestraApi.instance.validate(text, TIMEOUT)
        val future = ApplicationManager.getApplication().executeOnPooledThread<List<ValidateResult>?> { KestraApi.instance.validate(text, TIMEOUT) }
        return ApplicationUtil.runWithCheckCanceled(future, indicator)
    }

    override fun apply(file: PsiFile, issues: List<FlowIssue>?, holder: AnnotationHolder) {
        for (issue in issues.orEmpty()) {
            val builder = holder.newAnnotation(issue.severity, issue.message).range(rangeFor(file, issue))
            if (issue.deprecated) {
                builder.highlightType(ProblemHighlightType.LIKE_DEPRECATED)
            }
            builder.create()
        }
    }

    private fun rangeFor(file: PsiFile, issue: FlowIssue): TextRange {
        issue.path?.let { path -> FlowYaml.find(file, path)?.let { return it.textRange } }
        issue.search?.let { needle ->
            val index = file.text.indexOf(needle)
            if (index >= 0) {
                return TextRange(index, index + needle.length)
            }
        }
        val firstLine = file.text.indexOf('\n').let { if (it < 0) file.textLength else it }
        return TextRange(0, maxOf(1, firstLine).coerceAtMost(file.textLength))
    }

    companion object {
        private val LOG = logger<KestraValidationAnnotator>()
        private const val RETRY_MS = 30_000L
        private val TIMEOUT: Duration = Duration.ofSeconds(15)

        // Kestra 2.0 prefixes constraints with "Validation error:" and addresses tasks by id: tasks[my-task].message.
        private val PATH_PREFIX = Regex("""^(?:Validation error:\s*)?([A-Za-z_]\w*(?:\[[^\]\s]+])?(?:\.[A-Za-z_]\w*(?:\[[^\]\s]+])?)*):\s""")
        private val INVALID_TYPE = Regex("""Invalid type:\s*([\w.$]+)""")
        private val UNRECOGNIZED_FIELD = Regex("""Unrecognized field "([^"]+)"""")

        fun toIssues(results: List<ValidateResult>): List<FlowIssue> = results.flatMap { result ->
            result.constraints.map { issueFor(HighlightSeverity.ERROR, it) } +
                result.deprecationPaths.map { FlowIssue(HighlightSeverity.WARNING, "Deprecated: $it", FlowYaml.parsePath(it), null, deprecated = true) } +
                result.warnings.map { issueFor(HighlightSeverity.WARNING, it) }
        }

        private fun issueFor(severity: HighlightSeverity, message: String): FlowIssue {
            val path = PATH_PREFIX.find(message)?.groupValues?.get(1)?.let(FlowYaml::parsePath)
            val search = INVALID_TYPE.find(message)?.groupValues?.get(1)
                ?: UNRECOGNIZED_FIELD.find(message)?.groupValues?.get(1)?.let { "$it:" }
            return FlowIssue(severity, message, path, search)
        }

        fun clearCache() = ValidationState.instance.clear()
    }
}
