package io.kestra.jetbrains.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.util.Base64

enum class AuthKind(val label: String) {
    BASIC("Basic auth"),
    API_TOKEN("API token (Enterprise Edition)"),
    JWT("JWT token (legacy)");

    override fun toString() = label
}

data class KestraCredential(val kind: AuthKind, val username: String, val secret: String) {
    fun header(): Pair<String, String> = when (kind) {
        AuthKind.BASIC -> "Authorization" to "Basic " + Base64.getEncoder().encodeToString("$username:$secret".toByteArray())
        AuthKind.API_TOKEN -> "Authorization" to "Bearer $secret"
        AuthKind.JWT -> "Cookie" to "JWT=$secret"
    }
}

// Stored in the IDE password safe (OS keychain by default), one entry per instance URL.
object KestraCredentials {
    private fun attributes(url: String) = CredentialAttributes(generateServiceName("Kestra", url))

    fun get(url: String): KestraCredential? {
        if (url.isBlank()) {
            return null
        }
        val stored = PasswordSafe.instance.get(attributes(url)) ?: return null
        val secret = stored.getPasswordAsString()?.takeIf { it.isNotEmpty() } ?: return null
        val user = stored.userName.orEmpty()
        return when {
            user == TOKEN_MARKER -> KestraCredential(AuthKind.API_TOKEN, "", secret)
            user == JWT_MARKER -> KestraCredential(AuthKind.JWT, "", secret)
            user.isNotEmpty() -> KestraCredential(AuthKind.BASIC, user, secret)
            else -> null
        }
    }

    fun set(url: String, credential: KestraCredential) {
        val user = when (credential.kind) {
            AuthKind.BASIC -> credential.username
            AuthKind.API_TOKEN -> TOKEN_MARKER
            AuthKind.JWT -> JWT_MARKER
        }
        PasswordSafe.instance.set(attributes(url), Credentials(user, credential.secret))
    }

    fun clear(url: String) {
        PasswordSafe.instance.set(attributes(url), null)
    }

    private const val TOKEN_MARKER = "\u0000api-token"
    private const val JWT_MARKER = "\u0000jwt"
}
