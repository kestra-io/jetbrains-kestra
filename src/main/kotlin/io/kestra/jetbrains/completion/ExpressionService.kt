package io.kestra.jetbrains.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.flow.Flow
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

// Instance data behind {{ }} completion, cached so typing does not hit the instance on every key.
@Service(Service.Level.APP)
class ExpressionService {
    private class CachedContext(val key: String, val structure: String, val at: Long, val context: ExpressionContext)

    @Volatile private var filters: List<String>? = null
    @Volatile private var functions: List<PebbleFunction>? = null
    private val outputs = ConcurrentHashMap<String, List<String>>()
    @Volatile private var context: CachedContext? = null
    @Volatile private var unsupportedUntil = 0L

    init {
        ApplicationManager.getApplication().messageBus.connect().subscribe(KestraSettings.TOPIC, KestraInstanceListener { reset() })
    }

    fun reset() {
        filters = null
        functions = null
        outputs.clear()
        context = null
        unsupportedUntil = 0
    }

    private val api get() = KestraApi.instance

    fun filters(): List<String> = filters ?: runCatching {
        api.get("/pebble/filters", tenantScoped = false, timeout = TIMEOUT).takeIf { it.ok }?.json()
            ?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    }.getOrNull()?.also { filters = it }.orEmpty()

    fun functions(): List<PebbleFunction> = functions ?: runCatching {
        api.get("/pebble/functions", tenantScoped = false, timeout = TIMEOUT).takeIf { it.ok }?.json()?.let(PebbleFunction::parse)
    }.getOrNull()?.also { functions = it }.orEmpty()

    // Output property names for a task type, as the Kestra UI completes `outputs.<taskId>.`.
    fun outputsOf(type: String): List<String>? = outputs[type] ?: runCatching {
        api.get("/plugins/${KestraUrls.segment(type)}", tenantScoped = false, timeout = TIMEOUT).takeIf { it.ok }?.json()
            ?.asJsonObject?.getAsJsonObject("schema")?.getAsJsonObject("outputs")?.getAsJsonObject("properties")?.keySet()?.toList()
    }.getOrNull()?.also { outputs[type] = it }

    // Null on 1.x, or while the source does not parse, so the caller falls back to static lists.
    fun context(fileKey: String, source: String, flow: Flow): ExpressionContext? {
        if (System.currentTimeMillis() < unsupportedUntil) {
            return null
        }
        // 1.x has no such route: the POST would match "replace every flow in namespace 'expressions'".
        val major = api.majorVersion()
        if (major == null || major < 2) {
            unsupportedUntil = System.currentTimeMillis() + RETRY_MS
            return null
        }
        val structure = structureKey(flow)
        context?.takeIf { it.key == fileKey && it.structure == structure && System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.context }

        val response = runCatching { api.postYaml("/flows/expressions", source, TIMEOUT) }.getOrNull()
        if (response?.status == 404 || response?.status == 405) {
            unsupportedUntil = System.currentTimeMillis() + RETRY_MS
            return null
        }
        val parsed = response?.takeIf { it.ok }?.json()?.let(ExpressionContext::parse)
        if (parsed != null) {
            context = CachedContext(fileKey, structure, System.currentTimeMillis(), parsed)
            return parsed
        }
        // Keep the last good context rather than blanking completion mid-edit.
        return context?.takeIf { it.key == fileKey }?.context
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val TTL_MS = 30_000L
        private const val RETRY_MS = 30_000L

        val instance: ExpressionService get() = service()

        // Only what the endpoint derives expressions from: typing inside a string reuses the cached
        // context, adding an input or renaming a task refetches.
        fun structureKey(flow: Flow): String = listOf(
            flow.namespace, flow.taskIds(), flow.taskTypes(), flow.inputIds(), flow.sectionKeys("variables"), flow.sectionKeys("labels"),
        ).toString()
    }
}
