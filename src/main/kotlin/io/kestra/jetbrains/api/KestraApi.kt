package io.kestra.jetbrains.api

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import io.kestra.jetbrains.settings.KestraCredentials
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.stream.Stream

open class KestraApiException(message: String) : IOException(message)

class KestraNotConfiguredException : KestraApiException("No Kestra instance configured. Set its URL in Settings | Tools | Kestra.")

class KestraUnauthorizedException(val origin: String) : KestraApiException("$origin requires authentication.")

class KestraUnreachableException(val origin: String, cause: Throwable?) :
    KestraApiException("Cannot reach Kestra at $origin${cause?.message?.let { ": $it" } ?: ""}. Check that the instance is running and that the URL is correct.")

class ApiResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299

    fun json(): JsonElement? = runCatching { JsonParser.parseString(body) }.getOrNull()

    // The server's error body carries the reason: "message" on 1.x, a problem+json "detail" on 2.x.
    fun message(): String {
        val obj = json()?.takeIf { it.isJsonObject }?.asJsonObject
        return listOf("message", "detail", "title").firstNotNullOfOrNull { key -> obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            ?: body.take(500).ifBlank { "HTTP $status" }
    }
}

// A Server-Sent Events stream. close() ends the read loop from another thread.
class SseStream(private val lines: Stream<String>) : AutoCloseable {
    fun forEachFrame(onFrame: (SseFrame) -> Unit) {
        val parser = SseParser()
        lines.use { stream ->
            for (line in stream.iterator()) {
                parser.feed(line)?.let(onFrame)
            }
        }
        parser.flush()?.let(onFrame)
    }

    override fun close() {
        runCatching { lines.close() }
    }
}

const val YAML_CONTENT_TYPE = "application/x-yaml"

@Service(Service.Level.APP)
class KestraApi : Disposable {
    @Volatile
    private var cachedVersion: Pair<String, String?>? = null

    // Built lazily so the IDE proxy and certificate settings are in place; trusts the IDE's certificates,
    // so instances behind an internal CA work once the certificate is accepted in the IDE.
    private val client: HttpClient by lazy {
        val proxy = JdkProxyProvider.getInstance()
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(proxy.proxySelector)
            .authenticator(proxy.authenticator)
            .sslContext(CertificateManager.getInstance().sslContext)
            .build()
    }

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(KestraSettings.TOPIC, KestraInstanceListener { cachedVersion = null })
    }

    override fun dispose() = Unit

    val settings: KestraSettings get() = KestraSettings.instance

    val isConfigured: Boolean get() = settings.url.isNotBlank()

    fun apiBase(): String = KestraUrls.apiBase(settings.url).ifEmpty { throw KestraNotConfiguredException() }

    fun tenantBase(): String = KestraUrls.withTenant(apiBase(), settings.tenant)

    fun executionPage(namespace: String, flowId: String, executionId: String): String =
        KestraUrls.executionPage(apiBase(), settings.tenant, namespace, flowId, executionId)

    fun request(
        method: String,
        path: String,
        tenantScoped: Boolean = true,
        body: BodyPublisher = BodyPublishers.noBody(),
        contentType: String? = null,
        timeout: Duration = Duration.ofSeconds(30),
    ): ApiResponse {
        val response = send(method, path, tenantScoped, body, contentType, timeout, BodyHandlers.ofString())
        return ApiResponse(response.statusCode(), response.body())
    }

    // For binary content. A failed response still carries the server's reason as text.
    fun download(path: String, timeout: Duration = Duration.ofSeconds(60)): Pair<ApiResponse, ByteArray> {
        val response = send("GET", path, true, BodyPublishers.noBody(), null, timeout, BodyHandlers.ofByteArray())
        val bytes = response.body()
        return ApiResponse(response.statusCode(), if (response.statusCode() in 200..299) "" else String(bytes, Charsets.UTF_8)) to bytes
    }

    private fun <T> send(
        method: String,
        path: String,
        tenantScoped: Boolean,
        body: BodyPublisher,
        contentType: String?,
        timeout: Duration,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val url = (if (tenantScoped) tenantBase() else apiBase()) + path
        val request = builder(url, timeout).method(method, body).apply {
            contentType?.let { header("Content-Type", it) }
        }.build()
        val response = try {
            client.send(request, handler)
        } catch (e: IOException) {
            throw failure(url, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KestraUnreachableException(origin(url), e)
        }
        if (response.statusCode() == 401) {
            throw KestraUnauthorizedException(origin(url))
        }
        return response
    }

    fun get(path: String, tenantScoped: Boolean = true, timeout: Duration = Duration.ofSeconds(30)) =
        request("GET", path, tenantScoped, timeout = timeout)

    fun postYaml(path: String, source: String, timeout: Duration = Duration.ofSeconds(30)) =
        request("POST", path, body = BodyPublishers.ofString(source), contentType = YAML_CONTENT_TYPE, timeout = timeout)

    fun stream(path: String): SseStream {
        val url = tenantBase() + path
        val request = builder(url, null).header("Accept", "text/event-stream").GET().build()
        val response = try {
            client.send(request, BodyHandlers.ofLines())
        } catch (e: IOException) {
            throw failure(url, e)
        }
        if (response.statusCode() != 200) {
            response.body().close()
            if (response.statusCode() == 401) {
                throw KestraUnauthorizedException(origin(url))
            }
            throw KestraApiException("Stream $path failed with HTTP ${response.statusCode()}")
        }
        return SseStream(response.body())
    }

    // The client carries the IDE's authenticator for proxies, and with one set the JDK turns an unanswered
    // 401 challenge into an IOException instead of returning the response.
    private fun failure(url: String, e: IOException): KestraApiException =
        if (e.message == "No credentials provided" || e.message.orEmpty().startsWith("WWW-Authenticate header missing")) {
            KestraUnauthorizedException(origin(url))
        } else {
            KestraUnreachableException(origin(url), e)
        }

    // The instance version, or null if it can't be read.
    fun version(): String? {
        val url = settings.url
        cachedVersion?.takeIf { it.first == url }?.let { return it.second }
        val version = runCatching {
            get("/configs", tenantScoped = false, timeout = Duration.ofSeconds(10))
                .takeIf { it.ok }?.json()?.asJsonObject?.get("version")?.asString
        }.getOrNull()
        if (version != null) {
            cachedVersion = url to version
        }
        return version
    }

    fun majorVersion(): Int? = KestraVersion.major(version())

    // Throws KestraUnauthorizedException on bad credentials. Not /configs: Kestra 1.x serves it without
    // authentication, so it would accept any password.
    fun verify(): ApiResponse = get("/flows/search?size=1", timeout = Duration.ofSeconds(10))

    // The ids of the tenants the signed-in user belongs to, from the Enterprise Edition's /me. Null on
    // instances without it (OSS, 1.x), which have no tenant to pick. Throws KestraUnauthorizedException.
    fun tenants(): List<String>? {
        val response = get("/me", tenantScoped = false, timeout = Duration.ofSeconds(10))
        if (!response.ok) {
            return null
        }
        val tenants = response.json()?.takeIf { it.isJsonObject }?.asJsonObject?.get("tenants")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return tenants.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.get("id")?.takeIf { id -> id.isJsonPrimitive }?.asString }
    }

    fun validate(source: String, timeout: Duration = Duration.ofSeconds(30)): List<ValidateResult>? {
        val response = postYaml("/flows/validate", source, timeout)
        return if (response.ok) ValidateResult.parse(response.json()) else null
    }

    // Update first, a 404 means the flow does not exist yet, so create it.
    fun upsertFlow(namespace: String, id: String, source: String): ApiResponse {
        val updated = request(
            "PUT", "/flows/${KestraUrls.segment(namespace)}/${KestraUrls.segment(id)}",
            body = BodyPublishers.ofString(source), contentType = YAML_CONTENT_TYPE,
        )
        if (updated.status != 404) {
            return updated
        }
        return postYaml("/flows", source)
    }

    fun createExecution(namespace: String, id: String, inputs: List<FormPart>): ApiResponse {
        val path = "/executions/${KestraUrls.segment(namespace)}/${KestraUrls.segment(id)}"
        if (inputs.isEmpty()) {
            return request("POST", path)
        }
        // FILE inputs are streamed from disk, never held in memory.
        val multipart = Multipart.stream(inputs)
        return request("POST", path, body = multipart.body, contentType = multipart.contentType, timeout = Duration.ofMinutes(10))
    }

    fun execution(executionId: String): ApiResponse = get("/executions/${KestraUrls.segment(executionId)}")

    // The kill route moved under /actions in Kestra 2.0. With the version unknown (/configs failed), try the
    // 2.x route and fall back to the 1.x one on a 404.
    fun killExecution(executionId: String): ApiResponse {
        val routes = when (val major = majorVersion()) {
            null -> listOf("actions/kill", "kill")
            else -> listOf(if (major >= 2) "actions/kill" else "kill")
        }
        var response = request("DELETE", "/executions/${KestraUrls.segment(executionId)}/${routes.first()}")
        for (route in routes.drop(1)) {
            if (response.status != 404) break
            response = request("DELETE", "/executions/${KestraUrls.segment(executionId)}/$route")
        }
        return response
    }

    fun followExecution(executionId: String) = stream("/executions/${KestraUrls.segment(executionId)}/follow")

    // Everything is requested and the view filters by level. The parameter is only needed up to 2.0, newer
    // servers ignore it.
    fun followLogs(executionId: String) = stream("/logs/${KestraUrls.segment(executionId)}/follow?minLevel=TRACE")

    // The flow's topology as the Kestra UI draws it: nodes, edges and clusters.
    fun flowGraph(source: String): ApiResponse = postYaml("/flows/graph", source)

    // 2.0+ serves one icon per class; 1.x only the full index, with the icon bytes inline. Not tenant-scoped.
    fun pluginIcon(type: String): ApiResponse =
        get("/plugins/icons/${KestraUrls.segment(type)}", tenantScoped = false, timeout = Duration.ofSeconds(10))

    fun pluginIcons(): ApiResponse = get("/plugins/icons", tenantScoped = false, timeout = Duration.ofSeconds(60))

    // The plugin schema endpoint is global, not tenant-scoped.
    fun flowSchema(): ApiResponse = get("/plugins/schemas/flow", tenantScoped = false, timeout = Duration.ofSeconds(120))

    private fun builder(url: String, timeout: Duration?): HttpRequest.Builder {
        val uri = URI.create(url)
        val builder = HttpRequest.newBuilder(uri)
        // On plain http the JDK client tries an h2c upgrade, and some reverse proxies never answer an
        // upgrade request that carries a body, so the request times out. Kestra serves HTTP/1.1 there anyway.
        if (uri.scheme.equals("http", ignoreCase = true)) {
            builder.version(HttpClient.Version.HTTP_1_1)
        }
        timeout?.let { builder.timeout(it) }
        KestraCredentials.get(settings.url)?.header()?.let { (name, value) -> builder.header(name, value) }
        return builder
    }

    companion object {
        val instance: KestraApi get() = service()

        fun origin(url: String): String = runCatching {
            val uri = URI.create(url)
            "${uri.scheme}://${uri.authority}"
        }.getOrDefault(url)
    }
}
