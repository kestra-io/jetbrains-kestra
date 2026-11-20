package io.kestra.jetbrains.namespace

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.ui.tree.TreeUtil
import io.kestra.jetbrains.KestraNotifier
import io.kestra.jetbrains.api.KestraApiException
import io.kestra.jetbrains.api.KestraUnauthorizedException
import io.kestra.jetbrains.api.NamespaceFilesApi
import io.kestra.jetbrains.settings.KestraInstanceListener
import io.kestra.jetbrains.settings.KestraSettings
import io.kestra.jetbrains.vfs.KestraFileSystem
import io.kestra.jetbrains.vfs.KestraVirtualFile
import io.kestra.jetbrains.vfs.NamespaceSaver
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.IOException
import javax.swing.JTree
import java.util.concurrent.CompletableFuture
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class KestraNamespacesState : BaseState() {
    var namespaces by list<String>()
}

// The namespaces opened in this project, reopened with it.
@Service(Service.Level.PROJECT)
@State(name = "KestraNamespaces", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class KestraNamespaces : SimplePersistentStateComponent<KestraNamespacesState>(KestraNamespacesState()) {
    val namespaces: List<String> get() = state.namespaces.toList()

    // Assigning a new list is what marks the state as changed.
    fun add(namespace: String) {
        if (namespace !in state.namespaces) state.namespaces = (state.namespaces + namespace).toMutableList()
    }

    fun remove(namespace: String) {
        if (namespace in state.namespaces) state.namespaces = (state.namespaces - namespace).toMutableList()
    }
}

// The Kestra Namespaces tool window: the namespaces opened in this project as folders of the instance.
// Folders are listed in the background when expanded. Every change is sent to the instance first, then
// applied to the tree and to the open editors.
class KestraNamespacesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    internal val tree = Tree(model)
    private val fs get() = KestraFileSystem.instance
    private val namespaces get() = project.service<KestraNamespaces>()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "No namespace open"
        tree.emptyText.appendSecondaryText("Open Namespace…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { openNamespace() }
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree)
        tree.addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
            override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                val dir = node.userObject as? KestraVirtualFile ?: return
                if (!dir.isLoaded) load(dir)
            }

            override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) = Unit
        })
        EditSourceOnDoubleClickHandler.install(tree) { selectedFiles().singleOrNull()?.takeIf { !it.isDirectory }?.let(::open) }
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedFiles().singleOrNull()?.takeIf { !it.isDirectory }?.let(::open)
            }
        })

        val actions = DefaultActionGroup(
            action("Open Namespace…", AllIcons.General.Add) { openNamespace() },
            action("Refresh", AllIcons.Actions.Refresh) { refresh() },
            action("New File…", AllIcons.Actions.AddFile, enabled = { targetFolder() != null }) { create(directory = false) },
            action("New Folder…", AllIcons.Actions.NewFolder, enabled = { targetFolder()?.let { !fs.isFlowsFolder(it) } == true }) { create(directory = true) },
            action("Upload Here…", AllIcons.Actions.Upload, enabled = { targetFolder()?.let { !fs.isFlowsFolder(it) } == true }) { uploadHere() },
            // Flows keep their id, the instance has no rename for them.
            action("Rename…", AllIcons.Actions.Edit, enabled = {
                selectedFiles().singleOrNull()?.let { !it.isRoot && !fs.isFlowsFolder(it) && !fs.isFlow(it) } == true
            }) { rename() }
                .also { it.registerCustomShortcutSet(ActionManager.getInstance().getAction("RenameElement").shortcutSet, tree) },
            action("Delete…", AllIcons.General.Remove, enabled = {
                selectedFiles().isNotEmpty() && selectedFiles().none { it.isRoot || fs.isFlowsFolder(it) }
            }) { delete() }
                .also { it.registerCustomShortcutSet(CommonShortcuts.getDelete(), tree) },
            action("Close Namespace", AllIcons.Actions.Close, enabled = { selectedFiles().any { it.isRoot } }) { closeSelected() },
            action("Discard Local Changes…", AllIcons.Actions.Rollback, enabled = { unsyncedUnderSelection().isNotEmpty() }) { discardLocalChanges() },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("KestraNamespaces", actions, true).also { it.targetComponent = tree }.component
        PopupHandler.installPopupMenu(tree, actions, "KestraNamespacesPopup")
        setContent(ScrollPaneFactory.createScrollPane(tree, true))

        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(KestraSettings.TOPIC, KestraInstanceListener { ApplicationManager.getApplication().invokeLater({ rebuildRoots() }, project.disposed) })
        bus.subscribe(NamespaceFilesEvents.TOPIC, NamespaceFilesEvents.Listener { namespace ->
            ApplicationManager.getApplication().invokeLater({ if (namespace in namespaces.namespaces) refresh() }, project.disposed)
        })
        rebuildRoots()
    }

    override fun dispose() = Unit

    // The roots are the project's namespaces on the configured instance.
    private fun rebuildRoots() {
        root.removeAllChildren()
        val instance = KestraFileSystem.currentInstance()
        if (instance != null) {
            namespaces.namespaces.forEach { root.add(node(fs.root(instance, it))) }
        }
        tree.emptyText.text = if (instance == null) "Set the Kestra instance in Settings | Tools | Kestra" else "No namespace open"
        model.reload()
    }

    private fun node(file: KestraVirtualFile): DefaultMutableTreeNode = DefaultMutableTreeNode(file).apply {
        if (file.isDirectory) {
            if (file.isLoaded) file.children.forEach { add(node(it as KestraVirtualFile)) } else add(DefaultMutableTreeNode(LOADING))
        }
    }

    // Rebuilds a folder's subtree from the model, keeping what was expanded and selected.
    private fun rebuild(dir: KestraVirtualFile) {
        val node = findNode(dir) ?: return
        val state = TreeState.createOn(tree, root)
        node.removeAllChildren()
        node(dir).children().toList().forEach { node.add(it as DefaultMutableTreeNode) }
        model.nodeStructureChanged(node)
        state.applyTo(tree, root)
    }

    private fun findNode(file: KestraVirtualFile): DefaultMutableTreeNode? =
        TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).find { it.userObject === file }

    internal fun load(dir: KestraVirtualFile, then: () -> Unit = {}) = background("Listing ${dir.location}", {
        fs.list(dir)
    }) {
        rebuild(dir)
        then()
    }

    // Lists again every folder already listed. Completes once the tree was updated. An open file the listing found
    // changed is read again by itself, even when a later folder fails to list, see KestraVirtualFile.invalidate.
    fun refresh(): CompletableFuture<Unit> {
        val roots = root.children().toList().mapNotNull { (it as DefaultMutableTreeNode).userObject as? KestraVirtualFile }
        return background("Refreshing Kestra namespaces", {
            fun walk(dir: KestraVirtualFile) {
                if (!dir.isLoaded) return
                fs.list(dir).filter { it.isDirectory }.forEach(::walk)
            }
            roots.forEach { if (it.isLoaded) walk(it) else fs.list(it) }
        }) {
            roots.forEach(::rebuild)
        }
    }

    fun openNamespace() {
        if (KestraFileSystem.currentInstance() == null) {
            KestraNotifier.warn(project, "Set the Kestra instance URL in Settings | Tools | Kestra.")
            return
        }
        val listed = NamespaceTargetDialog.loadNamespaces(project) ?: return
        val dialog = NamespaceTargetDialog(project, "Open Kestra Namespace", listed, null, requireFileName = false, note = null)
        if (!dialog.showAndGet() || !dialog.confirmNew(project)) return
        open(dialog.selectedNamespace())
    }

    internal fun open(namespace: String) {
        namespaces.add(namespace)
        rebuildRoots()
        val file = fs.root(KestraFileSystem.currentInstance() ?: return, namespace)
        findNode(file)?.let { node ->
            val path = TreePath(node.path)
            tree.selectionPath = path
            tree.expandPath(path)
        }
    }

    // The selected files, and the files under the selected folders, whose changes the instance has not accepted.
    // Not a file still being saved: its upload would land after the discard, see NamespaceSaver.discardChanges.
    private fun unsyncedUnderSelection(): List<KestraVirtualFile> {
        val selected = selectedFiles()
        val saver = NamespaceSaver.instance
        return saver.unsyncedFiles().filter { file -> !saver.isSaving(file) && selected.any { it === file || file.path.startsWith(it.path + "/") } }
    }

    // Reachable at any time, unlike the buttons of the notification that reported the refused save, which expires.
    internal fun discardLocalChanges() {
        val files = unsyncedUnderSelection().ifEmpty { return }
        val what = files.singleOrNull()?.let { "\"${it.location}\"" } ?: "${files.size} files"
        if (Messages.showOkCancelDialog(project, "Discard the changes to $what that were not saved to Kestra, and bring back the version of the instance?", "Discard Local Changes",
                "Discard", Messages.getCancelButton(), Messages.getWarningIcon()) != Messages.OK) return
        files.forEach(NamespaceSaver.instance::discardChanges)
    }

    private fun closeSelected() {
        selectedFiles().filter { it.isRoot }.forEach { namespaces.remove(it.namespace) }
        rebuildRoots()
    }

    // Reads the content in the background, so the editor never waits on the instance.
    internal fun open(file: KestraVirtualFile) = background("Opening ${file.location}", { file.load() }) {
        OpenFileDescriptor(project, file).navigate(true)
    }

    // Completes once the file was created, or reported as not created.
    internal fun create(directory: Boolean): CompletableFuture<Unit> {
        val done = CompletableFuture.completedFuture(Unit)
        val parent = targetFolder() ?: return done
        val flow = fs.isFlowsFolder(parent)
        if (flow && directory) return done
        val kind = if (directory) "Folder" else if (flow) "Flow" else "File"
        val prompt = if (flow) "File name, the flow id followed by .yml:" else "Name:"
        val name = Messages.showInputDialog(project, prompt, "New $kind in ${parent.location}", null, if (flow) ".yml" else "", nameValidator(parent))?.trim() ?: return done
        val path = NamespacePaths.join(parent.relativePath, name)
        val template = flowTemplate(name.removeSuffix(".yml"), parent.namespace)
        return background("Creating ${parent.namespace}$path", {
            // Asked to the instance: the tree only knows the folders that were expanded, and the server
            // overwrites an existing file, or replaces an existing flow, without a word.
            if (exists(parent, name, flow)) throw KestraApiException("${parent.namespace}$path already exists")
            when {
                flow -> NamespaceFilesApi().saveFlow(parent.namespace, name.removeSuffix(".yml"), template)
                directory -> NamespaceFilesApi().createDirectory(parent.namespace, path)
                else -> NamespaceFilesApi().write(parent.namespace, path, ByteArray(0))
            }
        }) {
            val created = WriteAction.compute<KestraVirtualFile, RuntimeException> { fs.created(parent, name, directory) }
            if (flow) created.content = template.toByteArray()
            rebuild(parent)
            if (!directory) OpenFileDescriptor(project, created).navigate(true)
        }
    }

    private fun exists(parent: KestraVirtualFile, name: String, flow: Boolean): Boolean {
        val api = NamespaceFilesApi()
        return if (flow) name.removeSuffix(".yml") in api.flowIds(parent.namespace)
        else api.list(parent.namespace, parent.relativePath).any { it.name == name }
    }

    internal fun rename() {
        val file = selectedFiles().singleOrNull()?.takeIf { !it.isRoot } ?: return
        val parent = file.parentFile() ?: return
        val name = Messages.showInputDialog(project, "New name:", "Rename ${file.location}", null, file.name, nameValidator(parent, file.name))?.trim() ?: return
        if (name == file.name) return
        val target = NamespacePaths.join(parent.relativePath, name)
        background("Renaming ${file.location}", { NamespaceFilesApi().move(file.namespace, file.relativePath, target) }) {
            WriteAction.run<RuntimeException> { fs.renamed(file, name) }
            val editors = FileEditorManagerEx.getInstanceEx(project)
            editors.openFiles.filterIsInstance<KestraVirtualFile>().filter { it.path.startsWith(file.path) }.forEach(editors::updateFilePresentation)
            rebuild(parent)
        }
    }

    internal fun delete() {
        val files = selectedFiles().filter { !it.isRoot }.ifEmpty { return }
        val what = files.singleOrNull()?.let { "\"${it.location}\"" + if (it.isDirectory) " and everything in it" else "" } ?: "${files.size} items"
        if (Messages.showOkCancelDialog(project, "Delete $what from the instance? This cannot be undone.", "Delete from Kestra", "Delete",
                Messages.getCancelButton(), Messages.getWarningIcon()) != Messages.OK) return
        background("Deleting from Kestra", {
            files.forEach {
                if (fs.isFlow(it)) NamespaceFilesApi().deleteFlow(it.namespace, fs.flowId(it)) else NamespaceFilesApi().delete(it.namespace, it.relativePath)
            }
        }) {
            val editors = FileEditorManager.getInstance(project)
            files.forEach { file ->
                editors.openFiles.filterIsInstance<KestraVirtualFile>().filter { it == file || it.path.startsWith(file.path + "/") }.forEach(editors::closeFile)
                val parent = file.parentFile()
                WriteAction.run<RuntimeException> { fs.deleted(file) }
                parent?.let(::rebuild)
            }
        }
    }

    private fun uploadHere() {
        val dir = targetFolder() ?: return
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true).withTitle("Upload to ${dir.location}")
        val chosen = FileChooser.chooseFiles(descriptor, project, null).toList().ifEmpty { return }
        val files = NamespaceUpload.gather(chosen, KestraSettings.instance.excludedNames)
        if (files.isEmpty()) {
            KestraNotifier.info(project, "No files to upload: the selection only holds excluded names.")
            return
        }
        if (Messages.showOkCancelDialog(project, "Upload ${files.size} file(s) to ${dir.location}? Files at the same path are overwritten.",
                "Upload to Kestra", "Upload", Messages.getCancelButton(), null) != Messages.OK) return
        NamespaceUpload.run(project, dir.namespace, files, dir.relativePath)
    }

    private fun nameValidator(parent: KestraVirtualFile, current: String? = null) = object : InputValidator {
        override fun checkInput(input: String): Boolean {
            val name = input.trim()
            if (fs.isFlowsFolder(parent) && !FLOW_FILE.matches(name)) return false
            return name.isNotEmpty() && '/' !in name && name != "." && name != ".." && name !in KestraFileSystem.HIDDEN &&
                !(parent.isRoot && name == KestraFileSystem.FLOWS) && (name == current || parent.children.none { it.name == name })
        }

        override fun canClose(input: String) = checkInput(input)
    }

    internal fun selectedFiles(): List<KestraVirtualFile> =
        tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? KestraVirtualFile }

    // The selected folder, or the folder of the selected file.
    private fun targetFolder(): KestraVirtualFile? = selectedFiles().singleOrNull()?.let { if (it.isDirectory) it else it.parentFile() }

    private fun KestraVirtualFile.parentFile() = parent as? KestraVirtualFile

    // Runs the network part off the EDT, then the UI part on it. Failures are reported, never thrown.
    // The future completes once the UI part ran, or the failure was reported.
    private fun <T> background(title: String, work: () -> T, then: (T) -> Unit): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                Result.success(work())
            } catch (e: KestraUnauthorizedException) {
                KestraNotifier.signInRequired(project, "${e.origin} requires authentication. Sign in to reach its namespaces.")
                done.complete(Unit)
                return@executeOnPooledThread
            } catch (e: Exception) {
                Result.failure(e)
            }
            ApplicationManager.getApplication().invokeLater({
                try {
                    result.onSuccess(then).onFailure {
                        val reason = (it as? IOException)?.cause?.message ?: it.message ?: it.javaClass.simpleName
                        KestraNotifier.error(project, "$title failed: $reason")
                    }
                } finally {
                    done.complete(Unit)
                }
            }, project.disposed)
        }
        return done
    }

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, perform: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is KestraVirtualFile -> when {
                    item.isRoot -> {
                        icon = AllIcons.Nodes.Module
                        append(item.namespace, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  ${KestraFileSystem.describe(item.instance)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    item.isDirectory && item.fileSystem.isFlowsFolder(item) -> {
                        icon = AllIcons.Nodes.ConfigFolder
                        append(item.name)
                        append("  flows", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    item.isDirectory -> {
                        icon = AllIcons.Nodes.Folder
                        append(item.name)
                    }
                    else -> {
                        icon = FileTypeManager.getInstance().getFileTypeByFileName(item.name).icon
                        append(item.name)
                        if (serviceIfCreated<NamespaceSaver>()?.hasUnsyncedChanges(item) == true) append("  not saved to Kestra", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                }
                LOADING -> append(LOADING, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    companion object {
        private const val LOADING = "Loading…"

        // A flow id as Kestra accepts it, as the file name.
        private val FLOW_FILE = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*\\.yml")

        fun flowTemplate(id: String, namespace: String) = """
            |id: $id
            |namespace: $namespace
            |
            |tasks:
            |  - id: hello
            |    type: io.kestra.plugin.core.log.Log
            |    message: Hello from {{ flow.id }}
            |""".trimMargin()
    }
}
