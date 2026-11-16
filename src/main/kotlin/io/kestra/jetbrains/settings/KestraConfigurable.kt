package io.kestra.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.kestra.jetbrains.actions.SignInAction
import io.kestra.jetbrains.schema.KestraSchemaService

// Settings | Tools | Kestra
class KestraConfigurable : BoundConfigurable("Kestra") {
    private val settings get() = KestraSettings.instance

    private var url = ""
    private var tenant = ""
    private var logLevel = "INFO"

    override fun createPanel(): DialogPanel {
        url = settings.url
        tenant = settings.tenant
        logLevel = settings.logLevel
        return panel {
            group("Instance") {
                row("URL:") {
                    textField().bindText(::url).align(AlignX.FILL)
                        .comment("For example http://localhost:8080. The schema, validation and runs use this instance.")
                }
                row("Tenant:") {
                    textField().bindText(::tenant)
                        .comment("Leave empty for instances that do not use tenant-scoped API routes.")
                }
                row("Credentials:") {
                    button("Sign In…") { SignInAction.signIn(null) }
                        .comment("Stored in the IDE password safe, per instance URL. Apply a new URL before signing in.")
                }
            }
            group("Runs") {
                row("Minimum log level:") {
                    comboBox(listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")).bindItem({ logLevel }, { logLevel = it ?: "INFO" })
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        val changed = url.trim() != settings.url || tenant.trim() != settings.tenant
        settings.update(url, tenant, logLevel)
        if (changed) {
            ApplicationManager.getApplication().executeOnPooledThread { KestraSchemaService.instance.downloadQuietly() }
        }
    }
}
