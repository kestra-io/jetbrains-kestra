package io.kestra.jetbrains.vfs

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.serviceIfCreated

// Uploads still queued when the IDE closes would be lost: the editor already considers them saved.
// NamespaceSaver asks before quitting, with a window; this covers the exits that skip that question. The
// frames are already hidden when this runs, so it can only wait, and keep a local copy of what did not make it.
class SaveOnExit : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        serviceIfCreated<NamespaceSaver>()?.finishOnExit()
    }
}
