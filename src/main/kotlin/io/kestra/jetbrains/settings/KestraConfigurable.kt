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
                    button("Sign In…") { SignInAction.signIn(null) }
                        .comment("Stored in the IDE password safe, per instance URL. Apply a new URL before signing in.")
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
