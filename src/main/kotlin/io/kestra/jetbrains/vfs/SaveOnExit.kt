package io.kestra.jetbrains.vfs

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.serviceIfCreated

// Uploads still queued when the IDE closes would be lost: the editor already considers them saved.
class SaveOnExit : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        serviceIfCreated<NamespaceSaver>()?.flush(EXIT_WAIT_MILLIS)
    }

    private companion object {
        const val EXIT_WAIT_MILLIS = 15_000L
    }
}
