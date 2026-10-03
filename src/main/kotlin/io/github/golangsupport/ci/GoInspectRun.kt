package io.github.golangsupport.ci

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorBase
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.help.GoPages
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoBuildContext
import io.github.golangsupport.project.api.GoToolchainProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One run of the plugin's Go and go.mod inspections over the files under [root]: the tools of the profile ([tools]), the files `go` would
 * build ([candidates], [includedText]), each file inspected in a non-blocking read action, the problems as SARIF results. Used by the
 * headless `go-inspect` ([GoInspectStarter]) and by Go | Export Inspections to SARIF; Go | Inspect Project gives the same tools and the
 * same files ([GoInspectScope]) to the platform's batch inspection, for its Inspection Results view with fixes. Not on the EDT.
 *
 * [inspections] names the tools (enabled in the profile or not); `null` is every Go / go.mod inspection the profile enables.
 */
class GoInspectRun(
    private val project: Project, private val root: VirtualFile, private val inspections: Set<String>? = null, private val minLevel: GoSarifLevel = GoSarifLevel.NOTE,
) {
    /** What one run found: [results] at or above the minimal level, [notifications] are inspections that threw on a file. */
    class Report(val tools: List<LocalInspectionToolWrapper>, val results: List<GoSarifResult>, val notifications: List<String>, val files: Int, val millis: Long) {
        fun sarif(srcRootUri: String?): String {
            val rules = tools.map { w ->
                GoSarifRule(w.shortName, w.displayName, GoSarif.plainText(runCatching { w.loadDescription() }.getOrNull()), w.groupDisplayName.ifEmpty { "Go" },
                    GoSarifLevel.of(w.defaultLevel.severity) ?: GoSarifLevel.NOTE)
            }
            return GoSarif.write(GoPages.pluginVersion(), rules, results, srcRootUri, notifications)
        }

        val summary: String get() {
            val byLevel = GoSarifLevel.entries.reversed().joinToString(", ") { l -> "${results.count { it.level == l }} ${l.sarif}" }
            return "${tools.size} inspections, $files files, ${results.size} findings ($byLevel), ${notifications.size} inspection failures, $millis ms"
        }

        /** As `go-inspect` exits: a run whose inspections failed and found nothing must not pass CI as clean. */
        val exitCode: Int get() = if (results.isNotEmpty()) 1 else if (notifications.isNotEmpty()) 2 else 0
    }

    /** What one file gave: its findings (every level) and the inspections that threw on it. */
    class FileOutcome(val results: List<GoSarifResult>, val failures: List<String>)

    fun run(indicator: ProgressIndicator): Report = GoBatchInspections.during(project) { inspect(indicator) }

    private fun inspect(indicator: ProgressIndicator): Report {
        val started = System.currentTimeMillis()
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val tools = tools(project, profile, inspections)
        val context = GoToolchainProvider.getInstance().toolchainFor(project)?.buildContext
        val files = ReadAction.compute<List<VirtualFile>, Throwable> { candidates(root) }
        val results = ArrayList<GoSarifResult>()
        val notifications = ArrayList<String>()
        var inspected = 0
        for ((i, vf) in files.withIndex()) {
            indicator.checkCanceled()
            val uri = GoInspectFiles.relativeUri(VfsUtilCore.getRelativePath(vf, root, '/') ?: vf.name)
            indicator.fraction = i.toDouble() / files.size
            indicator.text2 = uri
            // inspections check for a progress indicator ("must be run under progress indicator", seen live: every one failed without it);
            // the non-blocking read action restarts on a write instead of freezing typing in the IDE, so a file's results are kept apart until it completes
            // executeProcessUnderProgress, not runProcess: the indicator of a task is already running, runProcess would start it again
            var found: FileOutcome? = null
            ProgressManager.getInstance().executeProcessUnderProgress({
                found = ReadAction.nonBlocking<FileOutcome?> { inspectFile(project, vf, uri, tools, profile, context, inspections != null) }.inSmartMode(project).wrapProgress(indicator).executeSynchronously()
            }, indicator)
            val outcome = found ?: continue
            inspected++
            results += outcome.results.filter { it.level.rank >= minLevel.rank }
            notifications += outcome.failures
        }
        return Report(tools, results, notifications, inspected, System.currentTimeMillis() - started)
    }

    companion object {
        const val CATEGORY = "ci"
        private val LANGUAGES = listOf("Go", "GoModule")

        /**
         * One file through [tools] under a read action: `null` when the file is gone, excluded, generated or out of the build ([includedText]).
         * [explicit]: the tools were named, so they run whether the profile enables them for the file or not. The shared core of `go-inspect`,
         * the SARIF export and the background analysis of the project ([io.github.golangsupport.problems.GoProjectProblems]).
         */
        fun inspectFile(project: Project, vf: VirtualFile, uri: String, tools: List<LocalInspectionToolWrapper>, profile: InspectionProfileImpl, context: GoBuildContext?,
                        explicit: Boolean): FileOutcome? {
            if (!vf.isValid || ProjectFileIndex.getInstance(project).isExcluded(vf)) return null
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return null
            if (!includedText(psi.name, psi.viewProvider.contents, context)) return null
            val results = ArrayList<GoSarifResult>()
            val failures = ArrayList<String>()
            for (wrapper in tools) if (wrapper.language.equals(psi.language.id, ignoreCase = true)) inspectFile(psi, uri, wrapper, profile, explicit, results, failures)
            return FileOutcome(results, failures)
        }

        private fun inspectFile(psi: PsiFile, uri: String, wrapper: LocalInspectionToolWrapper, profile: InspectionProfileImpl, explicit: Boolean,
                                results: MutableList<GoSarifResult>, failures: MutableList<String>) {
            val key = HighlightDisplayKey.find(wrapper.shortName)
            if (!explicit && key != null && !profile.isToolEnabled(key, psi)) return
            val tool = wrapper.tool as? LocalInspectionTool ?: return
            val problems = try {
                tool.processFile(psi, InspectionManager.getInstance(psi.project))
            } catch (e: Throwable) {
                if (e is ProcessCanceledException) throw e
                failures += "${wrapper.shortName} on $uri: ${GoPluginLog.describe(e)}"
                return
            }
            val severity = (key?.let { profile.getErrorLevel(it, psi) } ?: wrapper.defaultLevel).severity
            val text = psi.viewProvider.contents
            for (problem in problems) {
                val element = problem.psiElement ?: continue
                if (element.containingFile != psi || tool.isSuppressedFor(element)) continue
                val level = GoSarifLevel.of(problem.highlightType, severity) ?: continue
                val range = rangeOf(problem) ?: continue
                results += GoSarifResult(wrapper.shortName, level, message(problem), uri, GoSarif.region(text, range.startOffset, range.endOffset))
            }
        }

        private fun rangeOf(problem: ProblemDescriptor): TextRange? = (problem as? ProblemDescriptorBase)?.textRange
            ?: problem.psiElement?.textRange?.let { r -> problem.textRangeInElement?.shiftRight(r.startOffset) ?: r }

        /** `#ref` and `#loc` resolved as the Problems view does; HTML only when the message is HTML (`chan<- T` is not a tag). */
        private fun message(problem: ProblemDescriptor): String {
            val text = ProblemDescriptorUtil.renderDescriptionMessage(problem, problem.psiElement).trim()
            return if (text.startsWith("<html>", ignoreCase = true)) GoSarif.plainText(text) else text
        }

        /** [inspections] by name (enabled in the profile or not), otherwise every Go / go.mod inspection [profile] enables; sorted by short name. */
        fun tools(project: Project, profile: InspectionProfileImpl, inspections: Set<String>?): List<LocalInspectionToolWrapper> {
            val tools = if (inspections != null) inspections.map { name ->
                profile.getInspectionTool(name, project) as? LocalInspectionToolWrapper ?: throw GoInspectOptions.UsageException("no local inspection '$name'")
            } else profile.getAllEnabledInspectionTools(project).mapNotNull { it.tool as? LocalInspectionToolWrapper }
            return tools.filter { w -> LANGUAGES.any { it.equals(w.language, ignoreCase = true) } }.sortedBy { it.shortName }
                .also { if (inspections != null && it.size != inspections.size) throw GoInspectOptions.UsageException("not Go inspections: ${inspections - it.map { w -> w.shortName }.toSet()}") }
        }

        /** `.go`, `go.mod` and `go.work` under [root] in a stable order, without the directories `go` itself skips. Under a read action. */
        fun candidates(root: VirtualFile): List<VirtualFile> {
            val found = ArrayList<VirtualFile>()
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file == root || !GoInspectFiles.skipDirectory(file.name)
                    if (GoInspectFiles.isCandidate(file.name)) found += file
                    return true
                }
            })
            return found.sortedBy { VfsUtilCore.getRelativePath(it, root, '/') }
        }

        /** A directory between [root] (not included) and [file] that `go` skips: vendor, testdata, `.x`, `_x`. */
        fun underSkippedDirectory(file: VirtualFile, root: VirtualFile?): Boolean {
            var dir = file.parent
            while (dir != null && dir != root) {
                if (GoInspectFiles.skipDirectory(dir.name)) return true
                dir = dir.parent
            }
            return false
        }

        /** go.mod / go.work always; a Go file unless it is generated or the build constraints of [context] exclude it. */
        fun includedText(name: String, text: CharSequence, context: GoBuildContext?): Boolean =
            GoInspectFiles.isGoModFile(name) || !GoInspectFiles.isGenerated(text) && (context == null || GoBuildConstraintEvaluator.matchFile(name, text, context))

        /** [includedText] for a file not open in PSI: the text of the editor when the file is open, of the disk otherwise. */
        fun includedFile(file: VirtualFile, context: GoBuildContext?): Boolean {
            if (GoInspectFiles.isGoModFile(file.name)) return true
            val text = FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence ?: runCatching { LoadTextUtil.loadText(file) }.getOrNull() ?: return false
            return includedText(file.name, text, context)
        }
    }
}

/**
 * The inspections of go-psi answer only when the plugin's diagnostics are Built-in (the gate of [GoFeature.DIAGNOSTICS]): with gopls as
 * the source the editor shows its diagnostics instead. A run asked for explicitly (Inspect Project, SARIF export, `go-inspect`) wants the
 * native ones whatever the editor shows, so for its duration the gate lets them through ([io.github.golangsupport.lang.GoIgsIdeFeatureGate]).
 * The open editors may show both for that time; they are highlighted again when the run ends.
 */
object GoBatchInspections {
    private val RUNS = Key.create<AtomicInteger>("go.batch.inspections")

    /** The gate opened on this thread alone ([onThisThread]); a project, not a flag: two projects may analyse at once. */
    private val THREAD = ThreadLocal<Project?>()

    fun isActive(project: Project): Boolean = (project.getUserData(RUNS)?.get() ?: 0) > 0 || THREAD.get() === project

    /**
     * The gate open for [block] on the calling thread only: the background analysis of the project runs all the time, and a project-wide gate
     * would let the native diagnostics into the editors next to gopls' (and need a restart of the daemon at the end). The inspections run
     * synchronously in `processFile` on this thread, so they see the gate; the highlighting passes, on their own threads, do not.
     */
    fun <T> onThisThread(project: Project, block: () -> T): T {
        val previous = THREAD.get()
        THREAD.set(project)
        try {
            return block()
        } finally {
            THREAD.set(previous)
        }
    }

    /** Opens the gate; the returned function closes it, once, however often it is called. */
    fun acquire(project: Project): () -> Unit {
        counter(project).incrementAndGet()
        val released = AtomicBoolean()
        return {
            if (released.compareAndSet(false, true) && counter(project).decrementAndGet() == 0 && !project.isDisposed && !GoFeatures.native(GoFeature.DIAGNOSTICS, project)) {
                // what the editors highlighted meanwhile came from both sources
                ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart() }, ModalityState.any())
            }
        }
    }

    fun <T> during(project: Project, block: () -> T): T {
        val release = acquire(project)
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized
    private fun counter(project: Project): AtomicInteger = project.getUserData(RUNS) ?: AtomicInteger().also { project.putUserData(RUNS, it) }
}
