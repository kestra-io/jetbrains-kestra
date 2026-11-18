package io.kestra.jetbrains.docs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUrls
import io.kestra.jetbrains.topology.PluginIcons
import java.io.IOException
import java.net.URLEncoder

// The data behind the documentation view. Doc pages and search come from Kestra's public docs service,
// versioned to the connected instance; plugin pages and the plugin list come from the instance.
// Every call answers JSON for the page, JsonNull when the data is not available. Background threads only.
object DocsApi {
    private const val PUBLIC = "https://api.kestra.io/v1"

    // Docs content is versioned: this one still resolves when the instance version is unknown.
    private const val FALLBACK_VERSION = "1.3.0"

    private val api get() = KestraApi.instance

    fun handle(op: String, args: JsonObject): JsonElement = try {
        when (op) {
            "home" -> com.google.gson.JsonPrimitive(home())
            "doc" -> doc(args.get("path").asString)
            "search" -> search(args.get("q").asString)
            "definition" -> definition(args.get("type").asString)
            "subgroups" -> instance("/plugins/groups/subgroups")
            "icons" -> JsonObject().apply {
                PluginIcons.instance.icons(iconKeys(args)).forEach { (key, uri) -> addProperty(key, uri) }
            }
            else -> JsonNull.INSTANCE
        }
    } catch (e: Exception) {
        if (e !is IOException && e !is KestraApiException) logger<DocsApi>().warn("Documentation request $op failed", e)
        JsonNull.INSTANCE
    }

    // Each key is a request to the instance and a permanent cache entry: bounded, and only what looks like a
    // plugin type or group, whatever the page asks for.
    internal fun iconKeys(args: JsonObject): List<String> = args.get("keys")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        .mapNotNull { it.takeIf { key -> key.isJsonPrimitive && key.asJsonPrimitive.isString }?.asString?.takeIf(ICON_KEY::matches) }
        .distinct().take(MAX_ICON_KEYS)

    // Copy of the docs landing page of the Kestra editor.
    fun home(): String = DocsApi::class.java.getResource("/docs/basic.md")!!.readText()

    private fun version(): String = runCatching { api.takeIf { it.isConfigured }?.version() }.getOrNull() ?: FALLBACK_VERSION

    // The page's title and canonical path come in a header next to the raw markdown.
    private fun doc(path: String): JsonElement = HttpRequests.request("$PUBLIC/docs/${encodePath(path)}/versions/${version()}")
        .connectTimeout(TIMEOUT).readTimeout(TIMEOUT)
        .connect { request ->
            val markdown = request.readString()
            val metadata = request.connection.getHeaderField("x-kestra-metadata")
                ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() } ?: JsonObject()
            JsonObject().apply {
                addProperty("markdown", markdown)
                // The header replaces non-ASCII characters with "?", the frontmatter has the real title.
                addProperty("title", frontmatterTitle(markdown) ?: metadata.get("title")?.asString ?: "Documentation")
                addProperty("path", metadata.get("parsedUrl")?.asString ?: path)
                metadata.get("isIndex")?.takeIf { it.isJsonPrimitive }?.let { add("isIndex", it) }
            }
        }

    fun frontmatterTitle(markdown: String): String? {
        val frontmatter = Regex("""\A---\r?\n([\s\S]*?)\r?\n---""").find(markdown)?.groupValues?.get(1) ?: return null
        val title = Regex("""(?m)^title:\s*(.+)$""").find(frontmatter)?.groupValues?.get(1)?.trim() ?: return null
        return title.removeSurrounding("\"").removeSurrounding("'").ifEmpty { null }
    }

    private fun search(q: String): JsonElement {
        val body = HttpRequests.request("$PUBLIC/search/versions/${version()}?q=${URLEncoder.encode(q, Charsets.UTF_8)}&type=DOCS")
            .connectTimeout(TIMEOUT).readTimeout(TIMEOUT).readString()
        val results = JsonParser.parseString(body).asJsonObject.getAsJsonArray("results") ?: JsonArray()
        return JsonArray().apply {
            results.map { it.asJsonObject }.forEach { result ->
                add(JsonObject().apply {
                    addProperty("title", result.get("title")?.asString.orEmpty())
                    addProperty("path", result.get("url")?.asString.orEmpty())
                })
            }
        }
    }

    // The instance documents the plugin versions it runs; the public registry covers the no-instance case.
    private fun definition(type: String): JsonElement {
        val local = instance("/plugins/${KestraUrls.segment(type)}")
        if (!local.isJsonNull) return local
        return runCatching {
            JsonParser.parseString(HttpRequests.request("$PUBLIC/plugins/definitions/${KestraUrls.segment(type)}")
                .connectTimeout(TIMEOUT).readTimeout(TIMEOUT).readString())
        }.getOrDefault(JsonNull.INSTANCE)
    }

    // Plugin endpoints are not tenant-scoped.
    private fun instance(path: String): JsonElement {
        if (!api.isConfigured) return JsonNull.INSTANCE
        val response = runCatching { api.get(path, tenantScoped = false, timeout = java.time.Duration.ofSeconds(60)) }.getOrNull()
        return response?.takeIf { it.ok }?.json() ?: JsonNull.INSTANCE
    }

    private fun encodePath(path: String) = path.trim('/').split('/').joinToString("/") { KestraUrls.segment(it) }

    private const val MAX_ICON_KEYS = 50
    private val ICON_KEY = Regex("[\\w.$-]{1,200}")

    private const val TIMEOUT = 15_000
}
