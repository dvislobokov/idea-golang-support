package io.github.golangsupport.ci

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.text.TextWithMnemonic
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ui.BooleanCommitOption
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.lang.GoProjectPresence
import io.github.golangsupport.project.api.GoToolchainProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The pure part of the commit check: which changed files it reads, how its findings are told. */
object GoCommitChecks {
    /** A Go file `go` would build, by its path relative to the content root: not under vendor / testdata / `.x` / `_x`. */
    fun isChecked(relativePath: String): Boolean {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.last().endsWith(".go") && parts.dropLast(1).none { GoInspectFiles.skipDirectory(it) }
    }

    /** The findings that stop a commit: warnings and errors; weak warnings do not. */
    fun blocking(results: List<GoSarifResult>): List<GoSarifResult> = results.filter { it.level.rank >= GoSarifLevel.WARNING.rank }

    /** "2 errors and 1 warning in 2 Go files", `null` when nothing blocks. */
    fun summary(results: List<GoSarifResult>): String? {
        val found = blocking(results)
        if (found.isEmpty()) return null
        val errors = found.count { it.level == GoSarifLevel.ERROR }
        val warnings = found.size - errors
        val counts = listOfNotNull(errors.takeIf { it > 0 }?.let { count(it, "error") }, warnings.takeIf { it > 0 }?.let { count(it, "warning") }).joinToString(" and ")
        return "$counts in ${count(found.map { it.uri }.distinct().size, "Go file")}"
    }

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
}

/** "Check Go code" of the commit options, per project in the workspace file, as the platform keeps "Analyze code". */
@Service(Service.Level.PROJECT)
@State(name = "GoCommitCheck", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GoCommitCheckSettings : SimplePersistentStateComponent<GoCommitCheckSettings.Options>(Options()) {
    class Options : BaseState() {
        var checkGoCode by property(true)
    }

    var checkGoCode: Boolean
        get() = state.checkGoCode
        set(value) { state.checkGoCode = value }

    companion object {
        fun getInstance(project: Project): GoCommitCheckSettings = project.service()
    }
}

/** The commit option in projects with Go only. */
class GoCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        if (GoProjectPresence.hasGoFiles(panel.project)) GoCheckinHandler(panel.project) else CheckinHandler.DUMMY
}

/**
 * Before a commit, the plugin's native Go inspections (the ones of Inspect Project, by the current profile) over the changed Go files only;
 * warnings or errors stop the commit with the platform's "review / commit anyway". Never an external process: golangci-lint is not run here.
 */
class GoCheckinHandler(private val project: Project) : CheckinHandler(), CommitCheck {
    private val settings get() = GoCommitCheckSettings.getInstance(project)

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent =
        BooleanCommitOption.create(project, this, true, "Check Go code", { settings.checkGoCode }, { settings.checkGoCode = it })

    // after the modifying checks (reformat, optimize imports), with the platform's code analysis
    override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.LATE

    override fun isEnabled(): Boolean = settings.checkGoCode

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val files = readAction { changedGoFiles(commitInfo) }
        if (files.isEmpty()) return null
        // the editor may hold changes not yet in PSI; any modality: the commit dialog may be modal
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { PsiDocumentManager.getInstance(project).commitAllDocuments() }
        val results = withContext(Dispatchers.Default) { coroutineToIndicator { indicator -> inspect(files, indicator) } }
        val summary = GoCommitChecks.summary(results) ?: return null
        GoPluginLog.info(GoInspectRun.CATEGORY, "Commit check: $summary")
        return GoCommitProblem(files.map { it.first }, "Go inspections found $summary")
    }

    /** The changed Go files that exist after the commit, with their paths relative to the content root. */
    private fun changedGoFiles(commitInfo: CommitInfo): List<Pair<VirtualFile, String>> {
        val index = ProjectFileIndex.getInstance(project)
        return commitInfo.committedChanges.mapNotNull { it.virtualFile }.distinct().mapNotNull { vf ->
            val root = index.getContentRootForFile(vf) ?: return@mapNotNull null
            val relative = VfsUtilCore.getRelativePath(vf, root, '/') ?: return@mapNotNull null
            if (vf.isValid && !index.isExcluded(vf) && GoCommitChecks.isChecked(relative)) vf to GoInspectFiles.relativeUri(relative) else null
        }
    }

    private fun inspect(files: List<Pair<VirtualFile, String>>, indicator: ProgressIndicator): List<GoSarifResult> = GoBatchInspections.during(project) {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val tools = GoInspectRun.tools(project, profile, null)
        if (tools.isEmpty()) return@during emptyList()
        val context = GoToolchainProvider.getInstance().toolchainFor(project)?.buildContext
        files.flatMap { (vf, uri) ->
            indicator.checkCanceled()
            indicator.text2 = uri
            ReadAction.nonBlocking<GoInspectRun.FileOutcome?> { GoInspectRun.inspectFile(project, vf, uri, tools, profile, context, false) }
                .inSmartMode(project).wrapProgress(indicator).executeSynchronously()?.results.orEmpty()
        }
    }
}

/** What stops the commit: "Review" opens the findings in Inspection Results (with fixes), the commit goes on only when asked to. */
class GoCommitProblem(private val files: List<VirtualFile>, override val text: String) : CommitProblemWithDetails {
    override val showDetailsAction: String get() = "Review Go problems"

    override fun showDetails(project: Project) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val base = InspectionProjectProfileManager.getInstance(project).currentProfile
        val tools = GoInspectRun.tools(project, base, null)
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = GoInspectContext(project, manager.contentManager)
        context.setExternalProfile(GoInspectProfiles.limited(project, base, tools))
        context.doInspections(AnalysisScope(project, files.filter { it.isValid }))
    }

    override fun showModalSolution(project: Project, commitInfo: CommitInfo): CheckinHandler.ReturnResult {
        val anyway = "${StringUtil.removeEllipsisSuffix(TextWithMnemonic.parse(commitInfo.commitActionText).text)} Anyway"
        val answer = Messages.showYesNoCancelDialog(project, "$text.\nReview them before the commit?", "Go Code Check", "Review", anyway, Messages.getCancelButton(), Messages.getWarningIcon())
        return when (answer) {
            Messages.YES -> { showDetails(project); CheckinHandler.ReturnResult.CLOSE_WINDOW }
            Messages.NO -> CheckinHandler.ReturnResult.COMMIT
            else -> CheckinHandler.ReturnResult.CANCEL
        }
    }
}
