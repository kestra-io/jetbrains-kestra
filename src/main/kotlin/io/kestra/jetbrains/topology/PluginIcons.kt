package io.kestra.jetbrains.topology

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

// Plugin icons as data URIs, cached per instance. 2.0+ serves one icon per class, 1.x only the full index
// with the icon bytes inline. Network calls: background threads only.
@Service
class PluginIcons {
    private val cache = ConcurrentHashMap<String, Optional<String>>()
    @Volatile private var index: JsonObject? = null

    init {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(KestraSettings.TOPIC, KestraInstanceListener { clear() })
    }

    fun icons(types: Collection<String>): Map<String, String> {
        for (type in types) {
            if (cache.containsKey(type)) continue
            val base64 = runCatching { fetch(type) }.getOrElse { return known(types) }
            cache[type] = Optional.ofNullable(base64?.let { "data:image/svg+xml;base64,$it" })
        }
        return known(types)
    }

    private fun known(types: Collection<String>) = types.mapNotNull { type -> cache[type]?.orElse(null)?.let { type to it } }.toMap()

    private fun fetch(type: String): String? {
        val api = KestraApi.instance
        if ((api.majorVersion() ?: 2) >= 2) {
            val response = api.pluginIcon(type)
            return if (response.ok) response.json().icon(type, nested = true) else null
        }
        val all = index ?: api.pluginIcons().takeIf { it.ok }?.json()?.takeIf { it.isJsonObject }?.asJsonObject?.also { index = it }
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
        val instance: PluginIcons get() = service()
    }
}
