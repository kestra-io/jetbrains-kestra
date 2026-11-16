package io.kestra.jetbrains.api

import com.google.gson.JsonElement
import com.google.gson.JsonParser
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

    // The server's error body carries the reason.
    fun message(): String =
        json()?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: body.take(500).ifBlank { "HTTP $status" }
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
class KestraApi {
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
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect()
            .subscribe(KestraSettings.TOPIC, KestraInstanceListener { cachedVersion = null })
    }

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
        val url = (if (tenantScoped) tenantBase() else apiBase()) + path
        val request = builder(url, timeout).method(method, body).apply {
            contentType?.let { header("Content-Type", it) }
        }.build()
        val response = try {
            client.send(request, BodyHandlers.ofString())
        } catch (e: IOException) {
            throw failure(url, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KestraUnreachableException(origin(url), e)
        }
        if (response.statusCode() == 401) {
            throw KestraUnauthorizedException(origin(url))
        }
        return ApiResponse(response.statusCode(), response.body())
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

    // The instance version, or null if it can't be read.
    // The client carries the IDE's authenticator for proxies, and with one set the JDK turns an unanswered
    // 401 challenge into an IOException instead of returning the response.
    private fun failure(url: String, e: IOException): KestraApiException =
        if (e.message == "No credentials provided" || e.message.orEmpty().startsWith("WWW-Authenticate header missing")) {
            KestraUnauthorizedException(origin(url))
        } else {
            KestraUnreachableException(origin(url), e)
        }

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
        val multipart = Multipart.build(inputs)
        return request("POST", path, body = BodyPublishers.ofByteArray(multipart.bytes), contentType = multipart.contentType)
    }

    fun execution(executionId: String): ApiResponse = get("/executions/${KestraUrls.segment(executionId)}")

    // The kill route moved under /actions in Kestra 2.0.
    fun killExecution(executionId: String): ApiResponse {
        val action = if ((majorVersion() ?: 2) >= 2) "actions/kill" else "kill"
        return request("DELETE", "/executions/${KestraUrls.segment(executionId)}/$action")
    }

    fun followExecution(executionId: String) = stream("/executions/${KestraUrls.segment(executionId)}/follow")

    fun followLogs(executionId: String, minLevel: String) =
        stream("/logs/${KestraUrls.segment(executionId)}/follow?minLevel=${KestraUrls.segment(minLevel)}")

    // The plugin schema endpoint is global, not tenant-scoped.
    // The flow's topology as the Kestra UI draws it: nodes, edges and clusters.
    fun flowGraph(source: String): ApiResponse = postYaml("/flows/graph", source)

    // 2.0+ serves one icon per class; 1.x only the full index, with the icon bytes inline. Not tenant-scoped.
    fun pluginIcon(type: String): ApiResponse = get("/plugins/icons/${KestraUrls.segment(type)}", tenantScoped = false)

    fun pluginIcons(): ApiResponse = get("/plugins/icons", tenantScoped = false, timeout = Duration.ofSeconds(60))

    fun flowSchema(): ApiResponse = get("/plugins/schemas/flow", tenantScoped = false, timeout = Duration.ofSeconds(120))

    private fun builder(url: String, timeout: Duration?): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
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
