package io.kestra.jetbrains.topology

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.kestra.jetbrains.api.ApiResponse
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Plugin and plugin group icons as data URIs, cached per instance. 2.0+ serves one icon per class or group,
// 1.x only the full indexes with the icon bytes inline. Network calls: background threads only.
@Service
class PluginIcons : Disposable {
    private val cache = ConcurrentHashMap<String, Optional<String>>()
    // When the last fetch of a type failed: not tried again for a while, so a slow or broken instance is not
    // asked for the same icons on every keystroke of the editor.
    private val failures = ConcurrentHashMap<String, Long>()
    // The fetches under way, one per type, shared by every caller that wants it.
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Void>>()
    private val pool = AppExecutorUtil.createBoundedApplicationPoolExecutor("Kestra plugin icons", PARALLELISM)
    @Volatile private var index: JsonObject? = null
    // When the last build of the 1.x index failed. One cooldown for the index, not one per type: every type
    // waiting for it would otherwise build it again in turn, two requests of up to 60s each.
    @Volatile private var indexFailedAt: Long? = null
    // The time the cooldowns are measured in; tests move it forward.
    @Volatile internal var clock: () -> Long = System::nanoTime
    // Bumped when the instance changes, so a fetch started for the old one does not fill the cache of the new one.
    @Volatile private var generation = 0

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(KestraSettings.TOPIC, KestraInstanceListener { clear() })
    }

    override fun dispose() = pool.shutdown()

    // Fetches the icons not cached yet, a few at a time: one request per plugin type, in sequence, would hold
    // the first render for as long as the instance takes to answer them all. A type being fetched is waited for,
    // not fetched again. One that failed is left out for FAILURE_COOLDOWN, then tried again.
    fun icons(types: Collection<String>): Map<String, String> {
        val fetches = types.distinct().filter(::wanted).map { type ->
            val fetch = inFlight.computeIfAbsent(type) { CompletableFuture.runAsync({ load(type) }, pool) }
            // Outside computeIfAbsent: a fetch that is already done would run this inside the mapping function.
            fetch.whenComplete { _, _ -> inFlight.remove(type, fetch) }
            fetch
        }
        fetches.forEach { runCatching { it.join() } }
        return known(types)
    }

    // True when icons() would call the instance, or wait for a call that is under way.
    fun hasMissing(types: Collection<String>) = types.any(::wanted)

    // What is cached, without any request.
    fun cached(types: Collection<String>) = known(types)

    private fun wanted(type: String) = !cache.containsKey(type) && !recentlyFailed(type)

    private fun recentlyFailed(type: String) = failures[type].coolingDown()

    private fun Long?.coolingDown() = this != null && clock() - this < FAILURE_COOLDOWN_NANOS

    private fun load(type: String) {
        val started = generation
        val base64 = try {
            fetch(type)
        } catch (_: Exception) {
            if (started == generation) failures[type] = clock()
            return
        }
        if (started != generation) return
        failures.remove(type)
        cache[type] = Optional.ofNullable(base64?.let { "data:image/svg+xml;base64,$it" })
    }

    private fun known(types: Collection<String>) = types.mapNotNull { type -> cache[type]?.orElse(null)?.let { type to it } }.toMap()

    private fun fetch(type: String): String? {
        val api = KestraApi.instance
        if ((api.majorVersion() ?: 2) >= 2) {
            val response = api.pluginIcon(type)
            // A server error says nothing about the icon: it fails, and is tried again later. A 404 is "no icon".
            check(response.status < 500) { "HTTP ${response.status}" }
            return if (response.ok) response.json().icon(type, nested = true) else null
        }
        // Plugin types and plugin groups have separate indexes on 1.x.
        val started = generation
        val all = index ?: synchronized(this) {
            index ?: run {
                check(!indexFailedAt.coolingDown()) { "the icon index failed to build a moment ago" }
                JsonObject().also { merged ->
                    try {
                        listOf(api.pluginIcons(), api.get("/plugins/icons/groups", tenantScoped = false, timeout = java.time.Duration.ofSeconds(60)))
                            .mapNotNull(::indexOf)
                            .forEach { json -> json.entrySet().forEach { (key, value) -> merged.add(key, value) } }
                    } catch (e: Exception) {
                        if (started == generation) indexFailedAt = clock()
                        throw e
                    }
                    // Not kept when the instance changed meanwhile: it is the old instance's index.
                    if (started == generation) index = merged
                }
            }
        }
        return all.icon(type, nested = false)
    }

    // As on 2.x, an error says nothing about the icons: it fails, and the index is built again later instead of
    // staying empty. A 404 is an instance without that index.
    private fun indexOf(response: ApiResponse): JsonObject? {
        check(response.ok || response.status == 404) { "HTTP ${response.status}" }
        return response.takeIf { it.ok }?.json()?.takeIf { it.isJsonObject }?.asJsonObject
    }

    // 2.0+ answers {"icon": {"icon": "<base64>"}}, the 1.x index {"<type>": {"icon": "<base64>"}}.
    private fun com.google.gson.JsonElement?.icon(type: String, nested: Boolean): String? {
        val obj = this?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val entry = (if (nested) obj.get("icon") else obj.get(type))?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return entry.get("icon")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
    }

    fun clear() {
        generation++
        cache.clear()
        failures.clear()
        index = null
        indexFailedAt = null
    }

    companion object {
        private const val PARALLELISM = 6
        private val FAILURE_COOLDOWN_NANOS = TimeUnit.MINUTES.toNanos(1)

        val instance: PluginIcons get() = service()
    }
}
