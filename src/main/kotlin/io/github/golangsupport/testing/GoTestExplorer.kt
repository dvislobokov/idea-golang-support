package io.github.golangsupport.testing

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import io.github.golangsupport.GoIcons
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.run.GoRunLauncher
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** A test function found in the sources, with the file it is in. */
class DiscoveredGoTest(val declaration: GoDeclarationInfo, val kind: GoTestKind, val file: VirtualFile, val packageDirectory: VirtualFile) {
    val name: String get() = declaration.name
}

/** The directory of a package that has tests; [title] is its import path, or the path from the project when it is in no module. */
class GoTestPackage(val directory: VirtualFile, val title: String, val tests: List<DiscoveredGoTest>)

enum class GoTestStatus { PASSED, FAILED, SKIPPED }

object GoTestExplorerModel {
    private val SKIPPED_DIRECTORIES = setOf("vendor", "testdata", "node_modules", ".git", ".idea")

    /** Tests of every module of the project, found by tokens without compiling anything. Blocking, needs read access. */
    fun discover(project: Project): List<GoTestPackage> {
        val modules = GoModulesService.getInstance(project)
        val byDirectory = LinkedHashMap<VirtualFile, MutableList<DiscoveredGoTest>>()
        for (root in modules.commandDirectories()) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file.name !in SKIPPED_DIRECTORIES && !file.name.startsWith("_")
                    if (!file.name.endsWith(GoFile.TEST_SUFFIX)) return true
                    val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return true
                    val directory = file.parent ?: return true
                    GoTests.find(GoDeclarations.scan(text), file.name).mapTo(byDirectory.getOrPut(directory) { ArrayList() }) { (declaration, kind) -> DiscoveredGoTest(declaration, kind, file, directory) }
                    return true
                }
            })
        }
        return byDirectory.filterValues { it.isNotEmpty() }
            .map { (directory, tests) -> GoTestPackage(directory, modules.moduleOf(directory)?.importPath(directory) ?: directory.path, tests) }
            .sortedBy { it.title }
    }

    /** The key a result of a run is kept under and looked up by: the directory of the package and the top-level function. */
    fun key(packageDirectory: String, test: String): String = packageDirectory.replace('\\', '/').trimEnd('/') + "|" + test.substringBefore('/')
}

/**
 * What the last runs have said about every test function, whoever started them: gutter, the Go Tests window, a saved configuration.
 * A service with a listener of the project and not a part of the window: the window is made when it is first shown, after the runs.
 */
@Service(Service.Level.PROJECT)
class GoTestStatuses {
    private val statuses = ConcurrentHashMap<String, GoTestStatus>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun of(packageDirectory: String, test: String): GoTestStatus? = statuses[GoTestExplorerModel.key(packageDirectory, test)]

    fun onChange(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun record(locationUrl: String?, status: GoTestStatus) {
        val (directory, name) = locationUrl?.substringAfter("gotest://", "")?.split('|')?.takeIf { it.size == 2 && it[1].isNotEmpty() } ?: return
        // Go fails the function of a failed subtest itself, so the functions are all that is kept; a subtest only ever adds a failure
        if ('/' in name && status != GoTestStatus.FAILED) return
        statuses[GoTestExplorerModel.key(directory, name)] = status
        listeners.forEach { it() }
    }

    class Listener(private val project: Project) : SMTRunnerEventsAdapter() {
        private val statuses get() = project.service<GoTestStatuses>()

        override fun onTestFinished(test: SMTestProxy) = statuses.record(test.locationUrl, if (test.isIgnored) GoTestStatus.SKIPPED else if (test.isDefect) GoTestStatus.FAILED else GoTestStatus.PASSED)
        override fun onTestIgnored(test: SMTestProxy) = statuses.record(test.locationUrl, GoTestStatus.SKIPPED)
        override fun onTestFailed(test: SMTestProxy) = statuses.record(test.locationUrl, GoTestStatus.FAILED)
    }
}

class GoTestExplorerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GoTestExplorerPanel(project, toolWindow)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false).apply { isCloseable = false })
    }
}

private class GoTestExplorerPanel(private val project: Project, toolWindow: ToolWindow) : SimpleToolWindowPanel(true, true) {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "No tests found: functions TestXxx, BenchmarkXxx, FuzzXxx, ExampleXxx of _test.go files"
    }

    init {
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is GoTestPackage -> {
                        icon = statusIcon(item.tests.mapNotNull { statusOf(it) }) ?: GoIcons.Package
                        append(item.title)
                        append("  ${item.tests.size} tests", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is DiscoveredGoTest -> {
                        icon = statusIcon(listOfNotNull(statusOf(item))) ?: kindIcon(item.kind)
                        append(item.name)
                        append("  " + item.file.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
        TreeSpeedSearch.installOn(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val test = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DiscoveredGoTest ?: return false
                OpenFileDescriptor(project, test.file, test.declaration.nameRange.startOffset).navigate(true)
                return true
            }
        }.installOn(tree)

        setContent(ScrollPaneFactory.createScrollPane(tree))
        val actions = DefaultActionGroup(
            action("Run Selected Tests", AllIcons.Actions.Execute, { tree.selectionCount > 0 }) { runSelected(debug = false) },
            action("Debug Selected Tests", AllIcons.Actions.StartDebugger, { tree.selectionCount > 0 }) { runSelected(debug = true) },
            action("Run All Tests", AllIcons.Actions.RunAll, { root.childCount > 0 }) { runAll() },
            action("Refresh", AllIcons.Actions.Refresh, { true }) { reload() },
            action("Expand All", AllIcons.Actions.Expandall, { true }) { TreeUtil.expandAll(tree) },
            action("Collapse All", AllIcons.Actions.Collapseall, { true }) { TreeUtil.collapseAll(tree, 0) },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("GoTestExplorer", actions, true).also { it.targetComponent = tree }.component

        project.service<GoTestStatuses>().onChange(toolWindow.disposable) { ApplicationManager.getApplication().invokeLater({ tree.repaint() }, ModalityState.any()) }
        reload()
    }

    private fun statusOf(test: DiscoveredGoTest): GoTestStatus? = project.service<GoTestStatuses>().of(test.packageDirectory.path, test.name)

    private fun statusIcon(statuses: List<GoTestStatus>): Icon? = when {
        statuses.isEmpty() -> null
        GoTestStatus.FAILED in statuses -> AllIcons.RunConfigurations.TestFailed
        statuses.all { it == GoTestStatus.SKIPPED } -> AllIcons.RunConfigurations.TestIgnored
        else -> AllIcons.RunConfigurations.TestPassed
    }

    private fun kindIcon(kind: GoTestKind): Icon = when (kind) {
        GoTestKind.TEST -> AllIcons.Nodes.Test
        GoTestKind.BENCHMARK -> GoIcons.Benchmark
        GoTestKind.FUZZ -> GoIcons.Fuzz
        GoTestKind.EXAMPLE -> GoIcons.Example
    }

    private fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val packages = ReadAction.computeBlocking<List<GoTestPackage>, RuntimeException> { if (project.isDisposed) emptyList() else GoTestExplorerModel.discover(project) }
            ApplicationManager.getApplication().invokeLater({
                root.removeAllChildren()
                for (testPackage in packages) root.add(DefaultMutableTreeNode(testPackage).apply { testPackage.tests.forEach { add(DefaultMutableTreeNode(it)) } })
                model.reload()
                TreeUtil.expand(tree, 2)
            }, ModalityState.any())
        }
    }

    /** One run per package and kind: benchmarks need `-bench`, and a `-run` pattern belongs to one package. */
    private fun runSelected(debug: Boolean) {
        val selected = tree.selectionPaths.orEmpty().map { (it.lastPathComponent as DefaultMutableTreeNode).userObject }
        val wholePackages = selected.filterIsInstance<GoTestPackage>()
        for (testPackage in wholePackages) start(testPackage.directory, "go test ${testPackage.directory.name}", null, benchmark = false, debug)
        selected.filterIsInstance<DiscoveredGoTest>().filter { test -> wholePackages.none { it.directory == test.packageDirectory } }
            .groupBy { it.packageDirectory to (it.kind == GoTestKind.BENCHMARK) }
            .forEach { (group, tests) ->
                val name = tests.singleOrNull()?.name ?: "${tests.size} tests of ${group.first.name}"
                start(group.first, name, GoTests.pattern(tests.map { it.name }), benchmark = group.second, debug)
            }
    }

    private fun runAll() {
        for (directory in GoModulesService.getInstance(project).commandDirectories()) start(directory, "go test ${directory.name}/...", null, benchmark = false, debug = false, recursive = true)
    }

    private fun start(directory: VirtualFile, name: String, pattern: String?, benchmark: Boolean, debug: Boolean, recursive: Boolean = false) =
        GoRunLauncher.runTests(project, directory.path, name, pattern, benchmark, debug, recursive)

    private fun action(text: String, icon: Icon, enabled: () -> Boolean, perform: () -> Unit): AnAction = object : AnAction(text, null, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }
}
