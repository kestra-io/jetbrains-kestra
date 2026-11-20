package io.kestra.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

class KestraSettingsState : BaseState() {
    var url by string()
    var tenant by string()
    var logLevel by string("INFO")
    // Null keeps the defaults, so new defaults reach users who never edited the list.
    var excludedNames by string()
}

@Service(Service.Level.APP)
@State(name = "KestraSettings", storages = [Storage("kestra.xml")])
class KestraSettings : SimplePersistentStateComponent<KestraSettingsState>(KestraSettingsState()) {
    val url: String get() = state.url?.trim().orEmpty()
    val tenant: String get() = state.tenant?.trim().orEmpty()
    val logLevel: String get() = state.logLevel ?: "INFO"

    // Names skipped when uploading a folder to a namespace. A leading or trailing * is a wildcard.
    var excludedNames: List<String>
        get() = state.excludedNames?.let(::parseNames) ?: DEFAULT_EXCLUDED_NAMES
        set(value) {
            state.excludedNames = if (value == DEFAULT_EXCLUDED_NAMES) null else value.joinToString("\n")
        }

    fun updateTenant(tenant: String) = update(url, tenant, logLevel)

    fun update(url: String, tenant: String, logLevel: String) {
        val changed = url.trim() != this.url || tenant.trim() != this.tenant
        state.url = url.trim().ifEmpty { null }
        state.tenant = tenant.trim().ifEmpty { null }
        state.logLevel = logLevel
        if (changed) {
            fireChanged()
        }
    }

    companion object {
        val TOPIC = Topic.create("Kestra instance changed", KestraInstanceListener::class.java)

        val instance: KestraSettings get() = service()

        // Metadata and secrets, the same defaults as the VS Code extension.
        val DEFAULT_EXCLUDED_NAMES = listOf(
            ".git", ".vscode", ".idea", "node_modules", ".DS_Store", ".env", ".env.*", ".envrc", "*.pem", "*.key", "*.p8",
            "*.pfx", "*.p12", "*.crt", "id_rsa*", "credentials.json", ".npmrc", ".netrc", ".pypirc",
        )

        fun parseNames(text: String): List<String> = text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

        // Also fired on sign in or out, cached responses depend on the credentials too.
        fun fireChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).instanceChanged()
        }
    }
}

fun interface KestraInstanceListener {
    fun instanceChanged()
}
