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
}

@Service(Service.Level.APP)
@State(name = "KestraSettings", storages = [Storage("kestra.xml")])
class KestraSettings : SimplePersistentStateComponent<KestraSettingsState>(KestraSettingsState()) {
    val url: String get() = state.url?.trim().orEmpty()
    val tenant: String get() = state.tenant?.trim().orEmpty()
    val logLevel: String get() = state.logLevel ?: "INFO"

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

        // Also fired on sign in or out, cached responses depend on the credentials too.
        fun fireChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).instanceChanged()
        }
    }
}

fun interface KestraInstanceListener {
    fun instanceChanged()
}
