package io.kestra.jetbrains.namespace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

// Tells open namespace views that the files of a namespace changed on the instance.
object NamespaceFilesEvents {
    fun interface Listener {
        fun changed(namespace: String)
    }

    val TOPIC: Topic<Listener> = Topic.create("Kestra namespace files changed", Listener::class.java)

    fun changed(namespace: String) = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed(namespace)
}
