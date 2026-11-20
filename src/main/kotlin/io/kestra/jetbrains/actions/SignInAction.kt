package io.kestra.jetbrains.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.api.KestraApi
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.schema.KestraSchemaService
import io.kestra.jetbrains.settings.AuthKind
import io.kestra.jetbrains.settings.KestraConfigurable
import io.kestra.jetbrains.settings.KestraCredential
import io.kestra.jetbrains.settings.KestraCredentials
import io.kestra.jetbrains.settings.KestraSettings
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class SignInAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = signIn(e.project)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        fun signIn(project: Project?) {
            val url = KestraSettings.instance.url
            if (url.isBlank()) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, KestraConfigurable::class.java)
                return
            }
            // The password safe can be slow (OS keychain), so it is never called on the EDT.
            val stored = ProgressManager.getInstance().runProcessWithProgressSynchronously<KestraCredential?, Exception>(
                { KestraCredentials.get(url) }, "Reading Kestra credentials", false, project,
            )
            val dialog = SignInDialog(project, url, stored)
            if (!dialog.showAndGet()) {
                return
            }
            val credential = dialog.credential()
            object : Task.Backgroundable(project, "Signing in to Kestra", false) {
                override fun run(indicator: ProgressIndicator) {
                    KestraCredentials.set(url, credential)
                    KestraSettings.fireChanged()
                    try {
                        if (KestraSettings.instance.tenant.isEmpty()) {
                            pickTenant(project, KestraApi.instance.tenants().orEmpty())
                        }
                        val response = KestraApi.instance.verify()
                        if (response.status == 403) {
                            val tenant = KestraSettings.instance.tenant.ifEmpty { "the default tenant" }
                            KestraNotifier.warn(project, "Signed in to Kestra at ${KestraApi.origin(url)}, but access to $tenant is denied: check the tenant in Settings | Tools | Kestra and the user's permissions.")
                            return
                        }
                        KestraNotifier.info(project, "Signed in to Kestra at ${KestraApi.origin(url)}.")
                        KestraSchemaService.instance.downloadQuietly()
                    } catch (e: KestraUnauthorizedException) {
                        KestraCredentials.clear(url)
                        KestraNotifier.signInRequired(project, "Sign in failed: invalid credentials.")
                    } catch (e: KestraApiException) {
                        KestraNotifier.warn(project, e.message.orEmpty())
                    }
                }
            }.queue()
        }

        // With no tenant set, an Enterprise Edition instance routes requests to "main", which the user may not
        // belong to. Uses their only tenant, or asks which one when they belong to several.
        private fun pickTenant(project: Project?, tenants: List<String>) {
            val tenant = when (tenants.size) {
                0 -> return
                1 -> tenants.single()
                else -> {
                    var chosen: String? = null
                    // Any modality: sign in can start from the Settings dialog.
                    ApplicationManager.getApplication().invokeAndWait({
                        val dialog = TenantDialog(project, tenants)
                        chosen = if (dialog.showAndGet()) dialog.tenant() else null
                    }, ModalityState.any())
                    chosen ?: return
                }
            }
            KestraSettings.instance.updateTenant(tenant)
            KestraNotifier.info(project, "Using the Kestra tenant \"$tenant\". Change it in Settings | Tools | Kestra.")
        }
    }
}

private class SignInDialog(project: Project?, private val url: String, stored: KestraCredential?) : DialogWrapper(project) {
    private val kind = ComboBox(AuthKind.entries.toTypedArray())
    private val username = JBTextField()
    private val secret = JBPasswordField()
    private val secretLabel = JLabel()
    private val usernameLabel = JLabel("Username:")
    private val hiddenEchoChar = secret.echoChar
    private val revealButton = InplaceButton("Show", AllIcons.Actions.Show) { toggleReveal() }
    private val secretRow = JPanel(BorderLayout(4, 0)).apply {
        add(secret, BorderLayout.CENTER)
        add(revealButton, BorderLayout.EAST)
    }

    init {
        title = "Sign In to Kestra"
        stored?.let {
            kind.selectedItem = it.kind
            username.text = it.username
        }
        kind.addActionListener { refresh() }
        refresh()
        init()
    }

    private fun selectedKind() = kind.selectedItem as AuthKind

    private fun isRevealed() = secret.echoChar == 0.toChar()

    private fun toggleReveal() {
        secret.echoChar = if (isRevealed()) hiddenEchoChar else 0.toChar()
        revealButton.toolTipText = if (isRevealed()) "Hide" else "Show"
        secret.requestFocusInWindow()
    }

    private fun refresh() {
        val basic = selectedKind() == AuthKind.BASIC
        username.isVisible = basic
        usernameLabel.isVisible = basic
        secretLabel.text = if (basic) "Password:" else "Token:"
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Instance:", JLabel(url))
        .addLabeledComponent("Authentication:", kind)
        .addLabeledComponent(usernameLabel, username)
        .addLabeledComponent(secretLabel, secretRow)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = if (selectedKind() == AuthKind.BASIC) username else secret

    override fun doValidate(): ValidationInfo? = when {
        selectedKind() == AuthKind.BASIC && username.text.isBlank() -> ValidationInfo("Username is required", username)
        secret.password.isEmpty() -> ValidationInfo("Required", secret)
        else -> null
    }

    fun credential() = KestraCredential(selectedKind(), username.text.trim(), String(secret.password).trim())
}

private class TenantDialog(project: Project?, tenants: List<String>) : DialogWrapper(project) {
    private val tenant = ComboBox(tenants.toTypedArray())

    init {
        title = "Kestra Tenant"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(JLabel("This user belongs to several tenants. Pick the one the IDE works with."))
        .addLabeledComponent("Tenant:", tenant)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = tenant

    fun tenant() = tenant.selectedItem as String
}

class SignOutAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val url = KestraSettings.instance.url
        val project = e.project
        ApplicationManager.getApplication().executeOnPooledThread {
            if (url.isNotBlank()) {
                KestraCredentials.clear(url)
                KestraSettings.fireChanged()
            }
            KestraNotifier.info(project, "Signed out of Kestra.")
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
