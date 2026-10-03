package io.github.golangsupport.ci

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.ui.content.ContentManager
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.project.api.GoToolchainProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Go inspections of the whole project (main menu) or of one directory (context menu of the Project view). Hidden in the context menu
 * on anything but a directory of the project, disabled in the main menu of a project without Go.
 */
abstract class GoInspectActionBase : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (e.isFromContextMenu) {
            val dir = e.getData(CommonDataKeys.VIRTUAL_FILE)
            e.presentation.isEnabledAndVisible = project != null && dir != null && dir.isDirectory && ProjectFileIndex.getInstance(project).isInContent(dir)
        } else e.presentation.isEnabled = project != null && hasGo(project)
    }

    /** The directory of the context menu, or `null` for the whole project. */
    protected fun selectedDirectory(e: AnActionEvent): VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { e.isFromContextMenu && it.isDirectory }

    companion object {
        /** While the IDE indexes the answer is not known: enabled, the run waits for the indexes. */
        fun hasGo(project: Project): Boolean = DumbService.isDumb(project) || GlobalSearchScope.projectScope(project).let { scope ->
            FileTypeIndex.containsFileOfType(GoFileType, scope) || FileTypeIndex.containsFileOfType(GoModFileType, scope)
        }
    }
}

/**
 * Go | Inspect Project: the platform's batch inspection (`GlobalInspectionContextImpl.doInspections`, as Code | Inspect Code does it) with a
 * profile of only the Go and go.mod inspections the current profile enables ([GoInspectProfiles]) over the files `go-inspect` reads
 * ([GoInspectScope]). The results come in the standard Inspection Results view: tree by inspection and file, navigation, quick fixes, batch apply.
 */
class GoInspectProjectAction : GoInspectActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = selectedDirectory(e)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val base = InspectionProjectProfileManager.getInstance(project).currentProfile
        val tools = GoInspectRun.tools(project, base, null)
        if (tools.isEmpty()) return GoCli.notifyInfo(project, "Inspect Project", "No Go inspection is enabled in the profile '${base.name}' (Settings | Editor | Inspections).")
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = GoInspectContext(project, manager.contentManager)
        context.setExternalProfile(GoInspectProfiles.limited(project, base, tools))
        GoPluginLog.info(GoInspectRun.CATEGORY, "Inspect Project: ${tools.size} inspections over ${dir?.path ?: "the project"}")
        context.doInspections(AnalysisScope(GoInspectScope.of(project, dir), project))
    }
}

/**
 * Go | Export Inspections to SARIF…: [GoInspectRun] in a background task, the report where the save dialog says. The robot has no file
 * dialog to answer: the system property [OUTPUT_PROPERTY] (an absolute path) replaces it.
 */
class GoExportSarifAction : GoInspectActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = selectedDirectory(e) ?: project.guessProjectDir() ?: return
        val target = System.getProperty(OUTPUT_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: chooseTarget(project, root) ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        export(project, root, target)
    }

    private fun chooseTarget(project: Project, root: VirtualFile): Path? {
        val descriptor = FileSaverDescriptor("Export Inspections to SARIF", "Where to write the SARIF 2.1.0 report of the Go inspections", "sarif")
        return FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(project.guessProjectDir() ?: root, DEFAULT_NAME)?.file?.toPath()
    }

    companion object {
        const val DEFAULT_NAME = "go-inspect.sarif"
        const val OUTPUT_PROPERTY = "golangsupport.exportSarif.path"

        fun export(project: Project, root: VirtualFile, target: Path) {
            object : Task.Backgroundable(project, "Inspecting Go code", true) {
                private var report: GoInspectRun.Report? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    val done = GoInspectRun(project, root).run(indicator)
                    target.parent?.let { Files.createDirectories(it) }
                    Files.writeString(target, done.sarif(srcRootUri(root)))
                    GoPluginLog.info(GoInspectRun.CATEGORY, "${root.path}: ${done.summary} -> $target")
                    report = done
                }

                override fun onSuccess() {
                    report?.let { notifyWritten(project, it, target) }
                }

                override fun onThrowable(error: Throwable) {
                    GoPluginLog.warn(GoInspectRun.CATEGORY, "SARIF export failed", error)
                    GoCli.notifyError(project, "Export Inspections to SARIF", GoPluginLog.describe(error))
                }
            }.queue()
        }

        private fun srcRootUri(root: VirtualFile): String = runCatching { root.toNioPath().toUri().toString() }.getOrElse { root.url }

        private fun notifyWritten(project: Project, report: GoInspectRun.Report, target: Path) {
            val failures = if (report.notifications.isEmpty()) "" else "<br>${report.notifications.size} inspection failures are listed in the report."
            val notification = NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
                .createNotification("Export Inspections to SARIF", "${findings(report.results.size)} written to ${target.fileName}$failures", NotificationType.INFORMATION)
            notification.addAction(NotificationAction.createSimple("Open File") {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            })
            if (RevealFileAction.isSupported()) notification.addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(target) })
            notification.notify(project)
        }

        fun findings(count: Int): String = if (count == 1) "1 finding" else "$count findings"
    }
}

/** The profile of a Go run: copies of [tools] with their settings (`copyToolSettings`) and the levels [base] gives them, nothing else (RunInspectionIntention.createProfile does the same for one tool). */
object GoInspectProfiles {
    fun limited(project: Project, base: InspectionProfileImpl, tools: List<LocalInspectionToolWrapper>): InspectionProfileImpl {
        val profile = InspectionProfileImpl("Go inspections", InspectionToolsSupplier.Simple(tools.map { InspectionProfileImpl.copyToolSettings(it) }), base)
        for (wrapper in tools) {
            profile.enableTool(wrapper.shortName, project)
            HighlightDisplayKey.find(wrapper.shortName)?.let { profile.setErrorLevel(it, base.getErrorLevel(it, null as PsiElement?), project) }
        }
        return profile
    }
}

/** Within [base], the files `go-inspect` reads: Go files and go.mod / go.work outside vendor / testdata / `.x` / `_x`, not generated, under the build constraints. */
class GoInspectScope private constructor(private val project: Project, base: GlobalSearchScope, private val name: String) : DelegatingGlobalSearchScope(base, "go-inspect") {
    private val fileIndex = ProjectFileIndex.getInstance(project)
    private val context by lazy { GoToolchainProvider.getInstance().toolchainFor(project)?.buildContext }

    override fun contains(file: VirtualFile): Boolean =
        !file.isDirectory && GoInspectFiles.isCandidate(file.name) && super.contains(file) &&
            !GoInspectRun.underSkippedDirectory(file, fileIndex.getContentRootForFile(file)) && GoInspectRun.includedFile(file, context)

    override fun getDisplayName(): String = name

    companion object {
        fun of(project: Project, dir: VirtualFile?): GoInspectScope {
            val projectScope = GlobalSearchScope.projectScope(project)
            return if (dir == null) GoInspectScope(project, projectScope, "Go files of the project")
            else GoInspectScope(project, GlobalSearchScopesCore.directoryScope(project, dir, true).intersectWith(projectScope), "Go files in ${dir.name}")
        }
    }
}

/**
 * The platform's batch context that opens the gate of the native diagnostics ([GoBatchInspections]) for each run, Rerun of the view included,
 * and closes it when the run ends, is cancelled or its view is closed.
 */
private class GoInspectContext(project: Project, contentManager: NotNullLazyValue<out ContentManager>) : GlobalInspectionContextImpl(project, contentManager) {
    @Volatile private var release: (() -> Unit)? = null

    override fun doInspections(scope: AnalysisScope) {
        release?.invoke()
        release = GoBatchInspections.acquire(project)
        super.doInspections(scope)
    }

    override fun notifyInspectionsFinished(scope: AnalysisScope) {
        try { super.notifyInspectionsFinished(scope) } finally { release?.invoke() }
    }

    override fun canceled() {
        try { super.canceled() } finally { release?.invoke() }
    }

    override fun close(noSuspiciousCodeFound: Boolean) {
        try { super.close(noSuspiciousCodeFound) } finally { release?.invoke() }
    }
}
