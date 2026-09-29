package io.github.golangsupport.debugger

import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.ui.XDebugTabLayouter
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * What delve puts in the name of a thread: `[Go 12] main.worker (Running)`, `[Go 18] runtime.gopark`, `* [Go 1] store.(*Order).Total (Thread 39264)`
 * for the goroutine that is on a thread and current (seen live), sometimes with the labels of the goroutine after.
 */
data class GoGoroutineName(val id: Int?, val function: String, val state: String) {
    /** Goroutines of the runtime itself: parked helpers, the scavenger, finalizers; noise in a list of five hundred. */
    val isRuntime: Boolean get() = function.startsWith("runtime.") || function.startsWith("os/signal.") || function.isEmpty()

    companion object {
        private val NAME = Regex("""^\*?\s*\[Go (\d+)]\s*(\S*)\s*(?:\((.*?)\))?""")

        fun parse(name: String): GoGoroutineName {
            val match = NAME.find(name) ?: return GoGoroutineName(null, name, "")
            return GoGoroutineName(match.groupValues[1].toIntOrNull(), match.groupValues[2], match.groupValues[3])
        }

        /** The goroutines by the function they are in, most first: what a leak or a deadlock looks like. */
        fun summary(names: List<GoGoroutineName>): List<Pair<String, Int>> = names.groupingBy { it.function }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}

/** The "Goroutines" tab of the debug session: registered next to the frames and variables of the platform. */
class GoGoroutinesTabLayouter(private val process: GoDebugProcess) : XDebugTabLayouter() {
    override fun registerAdditionalContent(ui: com.intellij.execution.ui.RunnerLayoutUi) {
        val panel = GoGoroutinesPanel(process)
        val content = ui.createContent("GoGoroutines", panel, "Goroutines", AllIcons.Debugger.Threads, null)
        content.isCloseable = false
        ui.addContent(content, 0, PlaceInGrid.left, false)
    }
}

/**
 * All goroutines of the stop, grouped by the function they are in, with their state; the ones of the runtime hidden by default, a search
 * field, and a double click that makes a goroutine the current one in the frames view. Rebuilt at every stop.
 */
class GoGoroutinesPanel(private val process: GoDebugProcess) : JPanel(BorderLayout()) {
    private val session: XDebugSession = process.session
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply { isRootVisible = false; showsRootHandles = true; emptyText.text = "The goroutines appear when the program stops" }
    private val search = SearchTextField(false)
    private val hideRuntime = JBCheckBox("Hide runtime goroutines", true)
    private var stacks: List<GoExecutionStack> = emptyList()

    private class Group(val function: String, val count: Int)
    private class Goroutine(val stack: GoExecutionStack, val name: GoGoroutineName)
    private class Frame(val text: String)
    private object Loading

    init {
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is Group -> { icon = AllIcons.Nodes.Function; append(item.function.ifEmpty { "(no function)" }); append("  × ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    is Goroutine -> {
                        icon = if (item.stack.threadId == (session.suspendContext as? GoSuspendContext)?.activeThreadId) AllIcons.Debugger.ThreadCurrent else AllIcons.Debugger.ThreadSuspended
                        append("Go ${item.name.id ?: item.stack.threadId}")
                        if (item.name.state.isNotEmpty()) append("  ${item.name.state}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is Frame -> { icon = AllIcons.Debugger.Frame; append(item.text, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    Loading -> append("loading…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        // the frames of a goroutine are asked for when it is opened: a stop must not fetch five hundred stacks
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                val goroutine = node.userObject as? Goroutine ?: return
                if (node.childCount != 1 || node.firstChild.let { (it as DefaultMutableTreeNode).userObject !== Loading }) return
                process.stackTrace(goroutine.stack.threadId, 0, FRAMES).whenComplete { frames, error ->
                    ApplicationManager.getApplication().invokeLater({
                        node.removeAllChildren()
                        if (error != null) node.add(DefaultMutableTreeNode(Frame(GoDebugProcess.errorText(error))))
                        for (frame in frames.orEmpty()) {
                            val file = frame.getAsJsonObject("source")?.string("path")?.substringAfterLast('/')?.substringAfterLast('\\')
                            node.add(DefaultMutableTreeNode(Frame(frame.string("name").orEmpty() + (if (file != null) "  $file:${frame.int("line")}" else ""))))
                        }
                        model.nodeStructureChanged(node)
                    }, ModalityState.any())
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val goroutine = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Goroutine ?: return false
                select(goroutine.stack)
                return true
            }
        }.installOn(tree)
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })
        hideRuntime.addActionListener { rebuild() }
        add(JPanel(BorderLayout()).apply {
            add(search, BorderLayout.CENTER)
            add(hideRuntime, BorderLayout.EAST)
            border = JBUI.Borders.empty(2, 4)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = ApplicationManager.getApplication().invokeLater({ load() }, ModalityState.any())
            override fun sessionResumed() = ApplicationManager.getApplication().invokeLater({ stacks = emptyList(); rebuild() }, ModalityState.any())
        })
        load()
    }

    private fun load() {
        stacks = (session.suspendContext as? GoSuspendContext)?.goroutines.orEmpty()
        rebuild()
    }

    /** The goroutine becomes the current one: its frames and variables show, as a click in the threads list of the platform does. */
    private fun select(stack: GoExecutionStack) {
        val top = stack.topFrame
        if (top != null) return session.setCurrentStackFrame(stack, top)
        process.stackTrace(stack.threadId, 0, 1).thenAccept { frames ->
            val frame = frames.firstOrNull()?.let { GoStackFrame(process, it) } ?: return@thenAccept
            ApplicationManager.getApplication().invokeLater({ if (!session.isStopped) session.setCurrentStackFrame(stack, frame) }, ModalityState.any())
        }
    }

    private fun rebuild() {
        val filter = search.text.trim().lowercase()
        val parsed = stacks.map { Goroutine(it, GoGoroutineName.parse(it.displayName)) }
            .filter { !hideRuntime.isSelected || !it.name.isRuntime }
            .filter { filter.isEmpty() || it.stack.displayName.lowercase().contains(filter) }
        root.removeAllChildren()
        for ((function, count) in GoGoroutineName.summary(parsed.map { it.name })) {
            val group = DefaultMutableTreeNode(Group(function, count))
            for (goroutine in parsed.filter { it.name.function == function }) group.add(DefaultMutableTreeNode(goroutine).apply { add(DefaultMutableTreeNode(Loading)) })
            root.add(group)
        }
        model.reload()
        if (root.childCount <= EXPAND_ALL_BELOW) TreeUtil.expand(tree, 1)
    }

    private companion object {
        const val FRAMES = 8
        const val EXPAND_ALL_BELOW = 12
    }
}
