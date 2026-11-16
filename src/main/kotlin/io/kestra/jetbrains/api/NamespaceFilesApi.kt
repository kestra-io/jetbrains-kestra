package io.kestra.jetbrains.api

import com.google.gson.JsonObject
import java.net.http.HttpRequest.BodyPublishers

data class NamespaceEntry(val name: String, val directory: Boolean, val size: Long, val modified: Long)

// A refused or failed namespace call, with the server's reason.
class NamespaceFilesException(val status: Int, message: String) : KestraApiException(message) {
    val denied: Boolean get() = status == 403
}

// Namespace files and the flows of a namespace. Paths are namespace-relative and start with "/".
// Network calls: background threads only.
class NamespaceFilesApi(private val api: KestraApi = KestraApi.instance) {
    fun namespaces(): List<String> {
        val ids = ArrayList<String>()
        for (page in 1..MAX_PAGES) {
            val json = check(api.get("/namespaces/search?existing=true&size=$PAGE_SIZE&page=$page&sort=id%3Aasc"), "list namespaces")
            val results = json.asJsonObject.getAsJsonArray("results") ?: break
            results.mapNotNullTo(ids) { it.asJsonObject.get("id")?.asString }
            if (results.size() < PAGE_SIZE) break
        }
        return ids.distinct()
    }

    fun list(namespace: String, path: String): List<NamespaceEntry> =
        check(api.get("${files(namespace)}/directory?path=${encodePath(path)}"), "list $namespace$path").asJsonArray
            .map { it.asJsonObject }
            .map { NamespaceEntry(it.string("fileName"), it.string("type") == "Directory", it.long("size"), it.long("lastModifiedTime")) }

    fun read(namespace: String, path: String): ByteArray {
        val (response, bytes) = api.download("${files(namespace)}?path=${encodePath(path)}")
        check(response, "read $namespace$path", parse = false)
        return bytes
    }

    // The file name of the part is fixed: the server unpacks uploads named *.zip.
    fun write(namespace: String, path: String, content: ByteArray) {
        val multipart = Multipart.build(listOf(FormPart.File("fileContent", "content", content)))
        val response = api.request("POST", "${files(namespace)}?path=${encodePath(path)}",
            body = BodyPublishers.ofByteArray(multipart.bytes), contentType = multipart.contentType, timeout = java.time.Duration.ofSeconds(120))
        check(response, "write $namespace$path", parse = false)
    }

    fun createDirectory(namespace: String, path: String) {
        check(api.request("POST", "${files(namespace)}/directory?path=${encodePath(path)}"), "create $namespace$path", parse = false)
    }

    // The server parses from and to as URIs once the query is decoded, so they are encoded twice:
    // a single encoding of "a b" is refused with 422 Invalid argument.
    fun move(namespace: String, from: String, to: String) {
        fun uri(path: String) = KestraUrls.segment(encodePath(path))
        check(api.request("PUT", "${files(namespace)}?from=${uri(from)}&to=${uri(to)}"), "move $namespace$from", parse = false)
    }

    fun delete(namespace: String, path: String) {
        check(api.request("DELETE", "${files(namespace)}?path=${encodePath(path)}"), "delete $namespace$path", parse = false)
    }

    fun flowIds(namespace: String): List<String> =
        check(api.get("/flows/${KestraUrls.segment(namespace)}"), "list the flows of $namespace").asJsonArray
            .mapNotNull { it.asJsonObject.get("id")?.asString }

    fun flowSource(namespace: String, id: String): String =
        check(api.get("/flows/${KestraUrls.segment(namespace)}/${KestraUrls.segment(id)}?source=true"), "read flow $namespace.$id")
            .asJsonObject.string("source")

    // PUT, or POST for a new flow. The server validates the source and reports why it refuses it.
    fun saveFlow(namespace: String, id: String, source: String) {
        check(api.upsertFlow(namespace, id, source), "save flow $namespace.$id", parse = false)
    }

    fun deleteFlow(namespace: String, id: String) {
        check(api.request("DELETE", "/flows/${KestraUrls.segment(namespace)}/${KestraUrls.segment(id)}"), "delete flow $namespace.$id", parse = false)
    }

    private fun files(namespace: String) = "/namespaces/${KestraUrls.segment(namespace)}/files"

    private fun check(response: ApiResponse, action: String, parse: Boolean = true): com.google.gson.JsonElement {
        if (!response.ok) {
            val reason = if (response.status == 403) "permission denied: ${response.message()}" else response.message()
            throw NamespaceFilesException(response.status, "Cannot $action (HTTP ${response.status}): $reason")
        }
        return if (parse) response.json() ?: throw NamespaceFilesException(response.status, "Cannot $action: unexpected response") else JsonObject()
    }

    private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.long(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    companion object {
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 50

        // Each segment encoded, the slashes kept: "/a b/c" -> "/a%20b/c".
        fun encodePath(path: String): String = "/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { KestraUrls.segment(it) }
    }
}
