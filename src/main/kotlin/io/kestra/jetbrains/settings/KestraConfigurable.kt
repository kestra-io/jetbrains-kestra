package io.kestra.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.kestra.jetbrains.actions.SignInAction
import io.kestra.jetbrains.schema.KestraSchemaService
import java.awt.Component

// Settings | Tools | Kestra
class KestraConfigurable : BoundConfigurable("Kestra") {
    private val settings get() = KestraSettings.instance

    private var url = ""
    private var tenant = ""
    private var logLevel = "INFO"
    private var excludedNames = ""

    override fun createPanel(): DialogPanel {
        url = settings.url
        tenant = settings.tenant
        logLevel = settings.logLevel
        excludedNames = settings.excludedNames.joinToString("\n")
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
                    button("Sign In…") { event ->
                        // Sign in reads the saved URL, so the form is applied first. With no URL,
                        // signIn would open this Settings dialog again, which does nothing while it is open.
                        apply()
                        if (settings.url.isBlank()) {
                            Messages.showWarningDialog(event.source as Component, "Enter the instance URL first.", "Sign In to Kestra")
                        } else {
                            SignInAction.signIn(null)
                        }
                    }.comment("Stored in the IDE password safe, per instance URL. Signing in applies these settings.")
                }
            }
            group("Runs") {
                row("Default log level:") {
                    comboBox(listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")).bindItem({ logLevel }, { logLevel = it ?: "INFO" })
                        .comment("Level the run view starts with. Its log level selector changes it for that run.")
                }
            }
            group("Namespace Files") {
                row("Excluded names:") {
                    textArea().bindText(::excludedNames).applyToComponent { rows = 6 }.align(AlignX.FILL)
                        .comment("One per line. Skipped when uploading a folder to a namespace. A leading or trailing * is a wildcard, for example *.pem or .env.*")
                }
                row {
                    link("Restore defaults") { excludedNames = KestraSettings.DEFAULT_EXCLUDED_NAMES.joinToString("\n"); reset() }
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        val changed = url.trim() != settings.url || tenant.trim() != settings.tenant
        settings.update(url, tenant, logLevel)
        settings.excludedNames = KestraSettings.parseNames(excludedNames)
        if (changed) {
            ApplicationManager.getApplication().executeOnPooledThread { KestraSchemaService.instance.downloadQuietly() }
        }
    }
}
