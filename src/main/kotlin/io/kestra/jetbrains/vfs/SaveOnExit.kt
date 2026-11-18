package io.kestra.jetbrains.vfs

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.serviceIfCreated

// Uploads still queued when the IDE closes would be lost: the editor already considers them saved.
// NamespaceSaver already asked before quitting; this covers exits that skip that question.
class SaveOnExit : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        serviceIfCreated<NamespaceSaver>()?.flush(NamespaceSaver.EXIT_WAIT_MILLIS)
    }
}
