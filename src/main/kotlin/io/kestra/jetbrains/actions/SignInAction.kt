package io.kestra.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
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
import javax.swing.JComponent
import javax.swing.JLabel

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
            val dialog = SignInDialog(project, url)
            if (!dialog.showAndGet()) {
                return
            }
            KestraCredentials.set(url, dialog.credential())
            KestraSettings.fireChanged()
            object : Task.Backgroundable(project, "Signing in to Kestra", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        KestraApi.instance.verify()
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
    }
}

private class SignInDialog(project: Project?, private val url: String) : DialogWrapper(project) {
    private val kind = ComboBox(AuthKind.entries.toTypedArray())
    private val username = JBTextField()
    private val secret = JBPasswordField()
    private val secretLabel = JLabel()
    private val usernameLabel = JLabel("Username:")

    init {
        title = "Sign In to Kestra"
        KestraCredentials.get(url)?.let {
            kind.selectedItem = it.kind
            username.text = it.username
        }
        kind.addActionListener { refresh() }
        refresh()
        init()
    }

    private fun selectedKind() = kind.selectedItem as AuthKind

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
        .addLabeledComponent(secretLabel, secret)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = if (selectedKind() == AuthKind.BASIC) username else secret

    override fun doValidate(): ValidationInfo? = when {
        selectedKind() == AuthKind.BASIC && username.text.isBlank() -> ValidationInfo("Username is required", username)
        secret.password.isEmpty() -> ValidationInfo("Required", secret)
        else -> null
    }

    fun credential() = KestraCredential(selectedKind(), username.text.trim(), String(secret.password).trim())
}

class SignOutAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val url = KestraSettings.instance.url
        if (url.isNotBlank()) {
            KestraCredentials.clear(url)
            KestraSettings.fireChanged()
        }
        KestraNotifier.info(e.project, "Signed out of Kestra.")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
