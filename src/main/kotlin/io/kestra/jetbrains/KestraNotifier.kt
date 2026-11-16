package io.kestra.jetbrains

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.kestra.jetbrains.actions.SignInAction

object KestraNotifier {
    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Kestra")

    fun info(project: Project?, message: String) = group().createNotification(message, NotificationType.INFORMATION).notify(project)

    fun warn(project: Project?, message: String) = group().createNotification(message, NotificationType.WARNING).notify(project)

    fun error(project: Project?, message: String) = group().createNotification(message, NotificationType.ERROR).notify(project)

    fun signInRequired(project: Project?, message: String) {
        group().createNotification(message, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Sign in…") { SignInAction.signIn(project) })
            .notify(project)
    }
}
