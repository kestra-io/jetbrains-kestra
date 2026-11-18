package io.kestra.jetbrains.topology

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

// Plugin and plugin group icons as data URIs, cached per instance. 2.0+ serves one icon per class or group,
// 1.x only the full indexes with the icon bytes inline. Network calls: background threads only.
@Service
class PluginIcons : Disposable {
    private val cache = ConcurrentHashMap<String, Optional<String>>()
    @Volatile private var index: JsonObject? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(KestraSettings.TOPIC, KestraInstanceListener { clear() })
    }

    override fun dispose() = Unit

    // Fetches the icons not cached yet, a few at a time: one request per plugin type, in sequence, would hold
    // the first render for as long as the instance takes to answer them all. An unreachable instance
    // leaves them uncached, the next call tries again.
    fun icons(types: Collection<String>): Map<String, String> {
        val missing = types.distinct().filter { !cache.containsKey(it) }
        if (missing.isNotEmpty()) {
            val pool = AppExecutorUtil.createBoundedApplicationPoolExecutor("Kestra plugin icons", PARALLELISM)
            try {
                missing.map { type -> CompletableFuture.runAsync({ load(type) }, pool) }.forEach { runCatching { it.join() } }
            } finally {
                pool.shutdown()
            }
        }
        return known(types)
    }

    // True when icons() would call the instance.
    fun hasMissing(types: Collection<String>) = types.any { !cache.containsKey(it) }

    // What is cached, without any request.
    fun cached(types: Collection<String>) = known(types)

    private fun load(type: String) {
        val base64 = runCatching { fetch(type) }.getOrElse { return }
        cache[type] = Optional.ofNullable(base64?.let { "data:image/svg+xml;base64,$it" })
    }

    private fun known(types: Collection<String>) = types.mapNotNull { type -> cache[type]?.orElse(null)?.let { type to it } }.toMap()

    private fun fetch(type: String): String? {
        val api = KestraApi.instance
        if ((api.majorVersion() ?: 2) >= 2) {
            val response = api.pluginIcon(type)
            return if (response.ok) response.json().icon(type, nested = true) else null
        }
        // Plugin types and plugin groups have separate indexes on 1.x.
        val all = index ?: synchronized(this) {
            index ?: JsonObject().also { merged ->
                listOf(api.pluginIcons(), api.get("/plugins/icons/groups", tenantScoped = false, timeout = java.time.Duration.ofSeconds(60)))
                    .mapNotNull { response -> response.takeIf { it.ok }?.json()?.takeIf { it.isJsonObject }?.asJsonObject }
                    .forEach { json -> json.entrySet().forEach { (key, value) -> merged.add(key, value) } }
                index = merged
            }
        }
        return all.icon(type, nested = false)
    }

    // 2.0+ answers {"icon": {"icon": "<base64>"}}, the 1.x index {"<type>": {"icon": "<base64>"}}.
    private fun com.google.gson.JsonElement?.icon(type: String, nested: Boolean): String? {
        val obj = this?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val entry = (if (nested) obj.get("icon") else obj.get(type))?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return entry.get("icon")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
    }

    fun clear() {
        cache.clear()
        index = null
    }

    companion object {
        private const val PARALLELISM = 6

        val instance: PluginIcons get() = service()
    }
}
