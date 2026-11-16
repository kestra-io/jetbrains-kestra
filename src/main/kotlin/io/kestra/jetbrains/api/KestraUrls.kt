package io.kestra.jetbrains.api

object KestraUrls {
    private const val API_PATH = "/api/v1"

    // Accepts the UI root (http://host:8080) or the API root (http://host:8080/api/v1).
    fun apiBase(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return ""
        }
        return if (trimmed.contains(API_PATH)) trimmed else trimmed + API_PATH
    }

    fun withTenant(apiBase: String, tenant: String): String =
        if (tenant.isEmpty()) apiBase else apiBase.replaceFirst(API_PATH, "$API_PATH/$tenant")

    fun executionPage(apiBase: String, tenant: String, namespace: String, flowId: String, executionId: String): String {
        val web = apiBase.substringBefore(API_PATH)
        return "$web/ui/${tenant.ifEmpty { "main" }}/executions/$namespace/$flowId/$executionId"
    }

    // Namespaces, flow ids and execution ids are restricted to safe characters by Kestra, but encode anyway.
    fun segment(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() && byte >= 0 || char in "-._~") {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}
