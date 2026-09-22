package io.github.golangsupport.testing

import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.executeState
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoRunConfiguration
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.io.File

/**
 * The coverage of the last run with coverage: stripes in the gutter of the Go files (green ran, red did not, yellow partly), the
 * percentages in the Project view, a notification with the total. It stays until the next run with coverage or Hide Coverage.
 */
@Service(Service.Level.PROJECT)
class GoCoverageService(private val project: Project) : Disposable {
    /** Local path (system independent) -> the coverage of the file. */
    @Volatile var files: Map<String, GoFileCoverage> = emptyMap()
        private set

    private val highlighters = HashMap<Editor, List<RangeHighlighter>>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) show(event.editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                highlighters.remove(event.editor)
            }
        }, this)
    }

    /** Reads [profile] (on a pooled thread), then shows it; the notification says the total. */
    fun load(profile: File, title: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!profile.isFile || profile.length() == 0L) return@executeOnPooledThread
            val parsed = GoCoverProfile.parse(profile.readText())
            // the modules come from the VFS and the PSI of go.mod: a read action (seen live: SEVERE without it, and no coverage)
            val modules = com.intellij.openapi.application.ReadAction.compute<Map<String, String>, RuntimeException> {
                GoModulesService.getInstance(project).modules().mapNotNull { module -> module.content.modulePath?.let { it to module.root.path } }.toMap()
            }
            val result = LinkedHashMap<String, GoFileCoverage>()
            for ((path, blocks) in parsed.byFile) {
                val local = GoCoverageFormat.localPath(path, modules) ?: continue
                val text = runCatching { File(local).readLines() }.getOrNull()
                result[FileUtil.toSystemIndependentName(local)] = GoFileCoverage.of(blocks, text)
            }
            profile.delete()
            ApplicationManager.getApplication().invokeLater({
                files = result
                refresh()
                notifyTotal(title, result)
            }, project.disposed)
        }
    }

    fun hide() {
        files = emptyMap()
        refresh()
    }

    val isShown: Boolean get() = files.isNotEmpty()

    fun of(file: VirtualFile): GoFileCoverage? = files[file.path]

    /** Statements and covered statements of the files under [directory]; null when none of them has coverage. */
    fun ofDirectory(directory: VirtualFile): Pair<Int, Int>? {
        val prefix = directory.path.trimEnd('/') + "/"
        val under = files.filterKeys { it.startsWith(prefix) }.values
        if (under.isEmpty()) return null
        return under.sumOf { it.statements } to under.sumOf { it.coveredStatements }
    }

    private fun refresh() {
        for (editor in EditorFactory.getInstance().allEditors) if (editor.project == project) show(editor)
        ProjectView.getInstance(project).refresh()
    }

    private fun show(editor: Editor) {
        highlighters.remove(editor)?.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val coverage = of(file) ?: return
        val document = editor.document
        val added = ArrayList<RangeHighlighter>()
        for ((line, state) in coverage.lines) {
            val index = line - 1
            if (index !in 0 until document.lineCount) continue
            val highlighter = editor.markupModel.addRangeHighlighter(
                document.getLineStartOffset(index), document.getLineEndOffset(index), HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.lineMarkerRenderer = Stripe(state, coverage.hits[line] ?: 0)
            highlighter.putUserData(COVERAGE, true)
            added += highlighter
        }
        highlighters[editor] = added
    }

    private fun notifyTotal(title: String, result: Map<String, GoFileCoverage>) {
        val statements = result.values.sumOf { it.statements }
        val covered = result.values.sumOf { it.coveredStatements }
        val total = if (statements == 0) 100.0 else covered * 100.0 / statements
        // the packages with the least coverage first: where the tests are missing
        val packages = result.entries.groupBy { it.key.substringBeforeLast('/') }.map { (dir, list) ->
            val s = list.sumOf { it.value.statements }
            dir.substringAfterLast('/') to (if (s == 0) 100.0 else list.sumOf { it.value.coveredStatements } * 100.0 / s)
        }.sortedBy { it.second }.take(6)
        val content = packages.joinToString("<br>") { "${it.first}: ${GoCoverageFormat.percent(it.second)}" }
        NotificationGroupManager.getInstance().getNotificationGroup("Go")
            .createNotification("Coverage: ${GoCoverageFormat.percent(total)} of statements", "<b>$title</b><br>$content", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Hide coverage") { hide() })
            .notify(project)
    }

    override fun dispose() {
        highlighters.clear()
    }

    /** The stripe in the gutter; its tooltip says how many times the line ran. */
    private class Stripe(private val state: LineCoverage, private val hits: Long) : ActiveGutterRenderer, LineMarkerRendererEx {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = when (state) {
                LineCoverage.COVERED -> COVERED
                LineCoverage.UNCOVERED -> UNCOVERED
                LineCoverage.PARTIAL -> PARTIAL
            }
            g.fillRect(r.x, r.y, JBUI.scale(3), r.height)
        }

        override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

        override fun getTooltipText(): String = when (state) {
            LineCoverage.COVERED -> "Covered: ran $hits time${if (hits == 1L) "" else "s"}"
            LineCoverage.UNCOVERED -> "Not covered by the tests"
            LineCoverage.PARTIAL -> "Partly covered: some statements of the line did not run"
        }

        override fun canDoAction(editor: Editor, e: MouseEvent): Boolean = false
        override fun doAction(editor: Editor, e: MouseEvent) {}
        override fun getAccessibleName(): String = tooltipText
    }

    companion object {
        private val COVERAGE = Key.create<Boolean>("io.github.golangsupport.coverage")
        val COVERED = JBColor(Color(0x5FB865), Color(0x4F8F52))
        val UNCOVERED = JBColor(Color(0xE06C75), Color(0xB0484F))
        val PARTIAL = JBColor(Color(0xE5C07B), Color(0xB39247))

        fun getInstance(project: Project): GoCoverageService = project.service()
    }
}

/** `82% statements` next to the Go files and the directories in the Project view while coverage is shown. */
class GoCoverageProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val service = project.getServiceIfCreated(GoCoverageService::class.java) ?: return
        if (!service.isShown) return
        val file = node.virtualFile ?: return
        val percent = if (file.isDirectory) service.ofDirectory(file)?.let { (s, c) -> if (s == 0) 100.0 else c * 100.0 / s } else service.of(file)?.percent
        percent ?: return
        if (data.coloredText.isEmpty()) data.addText(data.presentableText ?: file.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        data.addText("  ${GoCoverageFormat.percent(percent)} statements", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

/**
 * Run with Coverage of the platform for the "Go" test configurations: the executor is the one of the platform coverage module, known
 * here by its id only (the module is not in every IDE); the state is the test run with `-coverprofile`.
 */
class GoCoverageRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "GoCoverageRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == COVERAGE_EXECUTOR_ID && profile is GoRunConfiguration && profile.options.command == GoCommand.TEST

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? = executeState(state, environment, this)

    companion object {
        const val COVERAGE_EXECUTOR_ID = "Coverage"
    }
}

/** Go | Hide Coverage. */
class HideGoCoverageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.getServiceIfCreated(GoCoverageService::class.java)?.isShown == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { GoCoverageService.getInstance(it).hide() }
    }
}

/**
 * Go | Run Tests with Coverage: the tests of the package of the file in the editor (or of the directory chosen in the Project view,
 * with the packages below it), for the IDEs without the coverage module of the platform, and for a quick look without a configuration.
 */
class RunGoTestsWithCoverageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (directory, recursive) = target(e) ?: return
        GoCoverageRuns.run(project, directory, recursive)
    }

    private fun target(e: AnActionEvent): Pair<VirtualFile, Boolean>? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return if (file.isDirectory) file to true else if (file.extension == "go") (file.parent ?: return null) to false else null
    }
}

object GoCoverageRuns {
    /** Set on an environment whose test run records coverage although its executor is Run. */
    val WITH_COVERAGE: Key<Boolean> = Key.create("io.github.golangsupport.withCoverage")

    fun run(project: Project, directory: VirtualFile, recursive: Boolean) {
        val runManager = com.intellij.execution.RunManager.getInstance(project)
        val name = "Coverage of ${directory.name}" + if (recursive) "/..." else ""
        val settings = runManager.createConfiguration(name, io.github.golangsupport.run.GoConfigurationType.instance.factory)
        (settings.configuration as GoRunConfiguration).options.apply {
            command = GoCommand.TEST
            target = directory.path
            this.recursive = recursive
        }
        runManager.setTemporaryConfiguration(settings)
        val executor = com.intellij.execution.ExecutorRegistry.getInstance().getExecutorById(GoCoverageRunner.COVERAGE_EXECUTOR_ID)
            ?: com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance()
        val builder = com.intellij.execution.runners.ExecutionEnvironmentBuilder.create(executor, settings)
        val environment = builder.build()
        environment.putUserData(WITH_COVERAGE, true)
        com.intellij.execution.ExecutionManager.getInstance(project).restartRunProfile(environment)
    }

    fun isWanted(environment: ExecutionEnvironment): Boolean =
        environment.executor.id == GoCoverageRunner.COVERAGE_EXECUTOR_ID || environment.getUserData(WITH_COVERAGE) == true
}
