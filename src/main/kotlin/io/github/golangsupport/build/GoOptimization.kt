package io.github.golangsupport.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import io.github.golangsupport.cli.CommandOutput
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.GoProjectPresence
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The last run of Go Optimization Decisions: the decisions of the compiler, which kinds the window shows, and the gutter marks in the
 * open editors while "Show in Editor" is on and the window is visible.
 */
@Service(Service.Level.PROJECT)
class GoOptimizationService(private val project: Project) : Disposable {
    @Volatile var decisions: List<GoOptimizationDecision> = emptyList()
        private set
    @Volatile var running: Boolean = false
        private set
    val shownKinds: MutableSet<GoOptimizationKind> = java.util.EnumSet.allOf(GoOptimizationKind::class.java)
    var showInEditor: Boolean = true
        set(value) { field = value; refreshEditors() }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project && markingEditors()) mark(event.editor)
            }
        }, this)
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = refreshEditors()
        })
    }

    /** Called on EDT whenever the decisions, the filters or the state of a run change. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        com.intellij.openapi.util.Disposer.register(parent) { listeners -= listener }
    }

    fun changed() {
        listeners.forEach { it() }
        refreshEditors()
    }

    /** `go build -gcflags=-m=2 ./...` in every module (or the project directory), in the background; the window shows what it printed. */
    fun run() {
        if (running) return
        val directories = GoModulesService.getInstance(project).commandDirectories()
        val settings = GoSettings.getInstance()
        val arguments = GoOptimizationOutput.arguments(settings.buildTagArguments(), settings.optimizationBoundsChecks)
        val commands = GoCli.commandLinesOrNotify(project, TITLE) { directories.map { GoCli.commandLine(it.path, *arguments.toTypedArray()) } } ?: return
        running = true
        changed()
        GoCli.runInBackground(project, TITLE, commands, output = Collector(), onFailure = { result ->
            // `go build` exits with 1 when a package does not compile: the decisions of the others are still shown, the notification names the errors
            val errors = GoOptimizationOutput.errors(result.stderr.ifBlank { result.stdout })
            if (errors.isNotEmpty()) GoCli.notifyError(project, "$TITLE: build failed", errors.take(15).joinToString("\n"))
            true
        })
    }

    /** Collects the diagnostics per command: their paths are relative to the directory of the command. */
    private inner class Collector : CommandOutput {
        private val found = mutableListOf<GoOptimizationDecision>()
        private var directory = ""
        private val text = StringBuilder()

        override fun commandStarted(command: GeneralCommandLine) {
            directory = command.workDirectory?.path.orEmpty()
            text.setLength(0)
        }

        override fun text(text: String, isError: Boolean) {
            this.text.append(text)
        }

        override fun commandFinished(exitCode: Int) {
            found += GoOptimizationOutput.parse(text.toString(), directory)
            text.setLength(0)
        }

        override fun finished(succeeded: Boolean) {
            if (text.isNotEmpty()) commandFinished(-1)
            val result = found.sortedWith(compareBy({ it.file }, { it.line }, { it.column }))
            ApplicationManager.getApplication().invokeLater({
                decisions = result
                running = false
                changed()
            }, project.disposed)
        }
    }

    // --- the marks in the editors ---------------------------------------------------------------------------------------------------

    private fun markingEditors(): Boolean = showInEditor && decisions.isNotEmpty() &&
        ToolWindowManager.getInstance(project).getToolWindow(GoOptimizationToolWindowFactory.ID)?.isVisible == true

    private fun refreshEditors() {
        val marking = markingEditors()
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            unmark(editor)
            if (marking) mark(editor)
        }
    }

    private fun unmark(editor: Editor) {
        editor.getUserData(MARKS)?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(MARKS, null)
    }

    private fun mark(editor: Editor) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = file.path
        val lines = decisions.filter { it.file == path && it.kind in shownKinds }.groupBy { it.line }
        if (lines.isEmpty()) return
        val document = editor.document
        val marks = lines.mapNotNull { (line, onLine) ->
            if (line < 1 || line > document.lineCount) return@mapNotNull null
            editor.markupModel.addLineHighlighter(line - 1, HighlighterLayer.ADDITIONAL_SYNTAX, null).apply { gutterIconRenderer = Mark(onLine) }
        }
        editor.putUserData(MARKS, marks)
    }

    /** The icon of the first kind on the line; the tooltip lists every decision of the line. */
    private class Mark(private val decisions: List<GoOptimizationDecision>) : GutterIconRenderer(), DumbAware {
        override fun getIcon(): Icon = iconOf(decisions.minOf { it.kind })
        override fun getTooltipText(): String = decisions.joinToString("<br>") { "${it.kind.title}: ${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(it.text)}" }
        override fun getAlignment(): Alignment = Alignment.RIGHT
        override fun equals(other: Any?): Boolean = other is Mark && other.decisions == decisions
        override fun hashCode(): Int = decisions.hashCode()
    }

    override fun dispose() {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::unmark)
    }

    companion object {
        const val TITLE = "Go Optimization"
        private val MARKS = Key.create<List<RangeHighlighter>>("io.github.golangsupport.optimization.marks")

        fun getInstance(project: Project): GoOptimizationService = project.service()

        fun iconOf(kind: GoOptimizationKind): Icon = when (kind) {
            GoOptimizationKind.INLINING -> AllIcons.Actions.Lightning
            GoOptimizationKind.ESCAPE -> AllIcons.General.Warning
            GoOptimizationKind.BOUNDS -> AllIcons.General.ShowInfos
        }
    }
}

/** Go | Go Optimization Decisions: runs the analysis and shows the window. */
class GoOptimizationAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && !GoOptimizationService.getInstance(project).running
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(GoOptimizationToolWindowFactory.ID)?.activate(null)
        GoOptimizationService.getInstance(project).run()
    }
}

class GoOptimizationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GoOptimizationPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false).apply { isCloseable = false })
    }

    override fun shouldBeAvailable(project: Project): Boolean = GoProjectPresence.hasGoFiles(project)

    companion object {
        const val ID = "Go Optimization"
    }
}

/** The tree of the window: file → kind → `line:column text`, filtered by the kinds turned on in the toolbar. */
class GoOptimizationPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service = GoOptimizationService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }

    init {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Run Analysis", "go build -gcflags=-m=2 ./... in every module", AllIcons.Actions.Execute), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = !service.running }
                override fun actionPerformed(e: AnActionEvent) = service.run()
            })
            add(object : ToggleAction("Bounds Checks", "Also report the bounds checks the compiler kept (-d=ssa/check_bce/debug=1); takes effect on the next run", AllIcons.Actions.Checked), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = GoSettings.getInstance().optimizationBoundsChecks
                override fun setSelected(e: AnActionEvent, state: Boolean) { GoSettings.getInstance().optimizationBoundsChecks = state }
            })
            add(Separator.create("Show"))
            for (kind in GoOptimizationKind.entries) add(object : ToggleAction("Show ${kind.title}", null, GoOptimizationService.iconOf(kind)), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = kind in service.shownKinds
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) service.shownKinds += kind else service.shownKinds -= kind
                    service.changed()
                }
            })
            add(object : ToggleAction("Show in Editor", "Marks in the gutter of the open editors while this window is visible", AllIcons.Actions.Preview), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = service.showInEditor
                override fun setSelected(e: AnActionEvent, state: Boolean) { service.showInEditor = state }
            })
            addSeparator()
            add(object : AnAction("Expand All", null, AllIcons.Actions.Expandall), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
            })
            add(object : AnAction("Collapse All", null, AllIcons.Actions.Collapseall), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 0)
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("GoOptimization", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree))
        tree.emptyText.text = "Run Go | Go Optimization Decisions: inlining, escape analysis and bounds checks of the compiler"
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) navigate()
            }
        })
        service.addListener(parent, ::rebuild)
        rebuild()
    }

    private fun rebuild() {
        root.removeAllChildren()
        val base = project.guessProjectDir()?.path?.let { "$it/" }
        val shown = service.decisions.filter { it.kind in service.shownKinds }
        for ((file, inFile) in shown.groupBy { it.file }) {
            val fileNode = DefaultMutableTreeNode(FileNode(base?.let { file.removePrefix(it) } ?: file, inFile.size))
            for ((kind, ofKind) in inFile.groupBy { it.kind }.toSortedMap()) {
                val kindNode = DefaultMutableTreeNode(KindNode(kind, ofKind.size))
                ofKind.forEach { kindNode.add(DefaultMutableTreeNode(it)) }
                fileNode.add(kindNode)
            }
            root.add(fileNode)
        }
        model.reload()
        tree.emptyText.text = when {
            service.running -> "Building with -gcflags=-m=2..."
            service.decisions.isEmpty() -> "Run Go | Go Optimization Decisions: inlining, escape analysis and bounds checks of the compiler"
            else -> "Nothing of the chosen kinds"
        }
        if (root.childCount <= 3) TreeUtil.expandAll(tree) else TreeUtil.expand(tree, 1)
    }

    private fun navigate() {
        val decision = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? GoOptimizationDecision ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(decision.file) ?: return
        OpenFileDescriptor(project, file, decision.line - 1, (decision.column - 1).coerceAtLeast(0)).navigate(true)
    }

    private data class FileNode(val path: String, val count: Int)
    private data class KindNode(val kind: GoOptimizationKind, val count: Int)

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is FileNode -> {
                    icon = AllIcons.FileTypes.Any_type
                    append(item.path)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is KindNode -> {
                    icon = GoOptimizationService.iconOf(item.kind)
                    append(item.kind.title)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is GoOptimizationDecision -> {
                    append("${item.line}:${item.column}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(item.text)
                }
            }
        }
    }
}
