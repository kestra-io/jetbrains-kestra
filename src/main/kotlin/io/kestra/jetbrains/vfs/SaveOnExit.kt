package io.kestra.jetbrains.vfs

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages

// What the IDE does with namespace files that are not on the instance when it quits or restarts.
//
// ApplicationListener, the only way to refuse an exit, has no message bus topic and so cannot be declared in
// plugin.xml: ApplicationImpl keeps its own dispatcher, fed by addApplicationListener. This listener is declared,
// and registers ExitGuard when an exit starts. ApplicationImpl publishes appClosing before it asks the
// ApplicationListeners (canExit), and publishes appWillBeClosed after them. Declared like this it is there
// even when nothing of ours was used since the start: a tab restored at startup, edited, then Cmd+Q.
class SaveOnExit : AppLifecycleListener {
    override fun appClosing() {
        service<ExitGuard>().begin()
    }

    override fun appWillBeClosed(isRestart: Boolean) {
        service<ExitGuard>().finishOnExit()
    }
}

// Asks before dropping a save the instance has not accepted, and waits for the ones it still may.
@Service
class ExitGuard : ApplicationListener, Disposable {
    // True once the user chose Quit Anyway: the exit then waits for nothing and has nothing more to ask.
    @Volatile private var quitAnyway = false

    init {
        ApplicationManager.getApplication().addApplicationListener(this, this)
    }

    // A new exit attempt: an earlier Quit Anyway, followed by a refusal of another listener, does not carry over.
    internal fun begin() {
        quitAnyway = false
    }

    // Quit and Restart IDE (also Invalidate Caches and plugin installs) take different paths through
    // ApplicationImpl.canExit: canRestartApplication, or canExitApplication.
    override fun canExitApplication() = confirmExit()

    override fun canRestartApplication() = confirmExit()

    private fun confirmExit(): Boolean {
        val saver = saveAndWait() ?: return true
        val unsynced = saver.unsyncedFiles()
        if (unsynced.isEmpty()) return true
        if (!confirmQuit(unsynced)) return false
        quitAnyway = true
        saver.keepCopies(unsynced)
        return true
    }

    // For the exits that skip the question above (a forced exit). After Quit Anyway nothing waits. Otherwise what
    // is not on the instance at the end is kept locally. Frames are hidden by now only when the platform could
    // shut down instantly, so this may hold a visible window for a moment: it is the same cancellable progress.
    internal fun finishOnExit() {
        if (quitAnyway) return
        val saver = saveAndWait() ?: return
        saver.keepCopies(saver.unsyncedFiles())
    }

    // The documents typed into are saved first, which hands them to the saver: the platform saves them only when it
    // closes the projects, after both ApplicationImpl.canExit and appWillBeClosed, and an upload started then dies
    // with the JVM. Null when no namespace file was ever saved or listed: nothing of ours can be outstanding.
    private fun saveAndWait(): NamespaceSaver? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val saver = serviceIfCreated<NamespaceSaver>() ?: return null
        saver.waitForSaves(NamespaceSaver.EXIT_WAIT_MILLIS)
        return saver
    }

    private fun confirmQuit(files: List<KestraVirtualFile>): Boolean {
        val names = files.take(MAX_LISTED).joinToString("\n") { "  ${it.location}" } +
            if (files.size > MAX_LISTED) "\n  and ${files.size - MAX_LISTED} more" else ""
        return Messages.showOkCancelDialog(
            "These files were not saved to Kestra:\n$names\n\nQuit anyway to keep a copy of each in ${UnsavedCopies.directory}.",
            "Changes Not Saved to Kestra",
            "Quit Anyway",
            "Keep Open",
            Messages.getWarningIcon(),
        ) == Messages.OK
    }

    override fun dispose() = Unit

    private companion object {
        const val MAX_LISTED = 10
    }
}
