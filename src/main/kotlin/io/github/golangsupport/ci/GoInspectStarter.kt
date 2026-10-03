package io.github.golangsupport.ci

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorBase
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.observation.Observation
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.help.GoPages
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoBuildContext
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * `idea64 go-inspect <projectDir> <out.sarif> [--inspections a,b] [--min-severity weak|warning|error]`: the plugin's Go and go.mod inspections
 * over a project without the UI, as a SARIF 2.1.0 report for CI. The platform's own `inspect` command writes only its XML / JSON formats
 * (SARIF there comes from the Qodana plugin, which is IDEA-only and licensed), hence a starter of our own.
 *
 * Exit codes: 0 no findings at or above the minimal severity, 1 findings, 2 bad arguments or a failed run.
 */
class GoInspectStarter : ApplicationStarter {
    override val requiredModality: Int get() = ApplicationStarter.NOT_IN_EDT
    override val isHeadless: Boolean get() = true

    override fun main(args: List<String>) {
        val code = try {
            GoInspectRun(GoInspectOptions.parse(args)).run()
        } catch (e: GoInspectOptions.UsageException) {
            System.err.println("${GoInspectOptions.COMMAND}: ${e.message}\n${GoInspectOptions.USAGE}")
            2
        } catch (e: Throwable) {
            System.err.println("${GoInspectOptions.COMMAND}: failed: ${GoPluginLog.describe(e)}")
            GoPluginLog.error(GoInspectRun.CATEGORY, "headless inspection failed", e)
            2
        }
        System.out.flush()
        // `bin\idea.bat` ends with `DEL` of its argument file, so on Windows the exit code of the JVM never reaches the caller: the wrappers read it here
        System.getenv(EXIT_CODE_FILE)?.takeIf { it.isNotBlank() }?.let { runCatching { Files.writeString(Path.of(it), code.toString()) } }
        ApplicationManagerEx.getApplicationEx().exit(ApplicationEx.FORCE_EXIT or ApplicationEx.EXIT_CONFIRMED, code)
    }

    companion object {
        /** Environment variable: a file to write the exit code to (`tools/ci/go-inspect.cmd` / `.sh` set it). */
        const val EXIT_CODE_FILE = "GO_INSPECT_EXIT_CODE_FILE"
    }
}

/** One run: open, wait for the indexes, inspect every file, write the report. Not on the EDT: it waits for indexing and for `go env`. */
internal class GoInspectRun(private val options: GoInspectOptions) {

    fun run(): Int {
        if (!Files.isDirectory(options.projectDir)) throw GoInspectOptions.UsageException("not a directory: ${options.projectDir}")
        val settings = GoSettings.getInstance()
        val saved = settings.languageFeaturesSource to settings.languageServerEnabled
        // the inspections answer only when their features are Built-in; without gopls nothing waits for a server that CI does not have
        settings.languageFeaturesSource = GoFeatureSource.NATIVE
        settings.languageServerEnabled = false
        try {
            GoEnvironment.get() // the toolchain (GOROOT, build tags) is complete before the project model asks for it
            val project = ProjectUtil.openOrImport(options.projectDir, OpenProjectTask { forceOpenInNewFrame = true; showWelcomeScreen = false; runConfigurators = true })
                ?: throw IllegalStateException("cannot open ${options.projectDir} as a project")
            try {
                awaitReady(project)
                return inspect(project)
            } finally {
                runCatching { ApplicationManager.getApplication().invokeAndWait({ ProjectManagerEx.getInstanceEx().forceCloseProject(project) }, ModalityState.any()) }
            }
        } finally {
            settings.languageFeaturesSource = saved.first
            settings.languageServerEnabled = saved.second
        }
    }

    /** Configuration activities may start another indexing round (library roots of the module cache): wait until the IDE stays smart. */
    private fun awaitReady(project: Project) {
        val dumb = DumbService.getInstance(project)
        repeat(5) {
            runBlocking { Observation.awaitConfiguration(project) }
            dumb.waitForSmartMode()
            if (!dumb.isDumb) return
        }
    }

    private fun inspect(project: Project): Int {
        val started = System.currentTimeMillis()
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val tools = tools(project, profile)
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(options.projectDir) ?: throw IllegalStateException("not in VFS: ${options.projectDir}")
        VfsUtil.markDirtyAndRefresh(false, true, true, rootVf)
        val covered = ReadAction.compute<Boolean, Throwable> { ProjectRootManager.getInstance(project).contentRoots.any { VfsUtilCore.isAncestor(it, rootVf, false) } }
        if (!covered) System.err.println("${GoInspectOptions.COMMAND}: warning: ${options.projectDir} is not a content root of the opened project, resolve may be incomplete")
        val context = GoToolchainProvider.getInstance().toolchainFor(project)?.buildContext
        val results = ArrayList<GoSarifResult>()
        val notifications = ArrayList<String>()
        var files = 0
        for (path in candidates(options.projectDir)) {
            val vf = LocalFileSystem.getInstance().findFileByNioFile(path) ?: continue
            val uri = GoInspectFiles.relativeUri(options.projectDir, path)
            // inspections check for a progress indicator ("must be run under progress indicator", seen live: every one failed without it)
            val inspected = ProgressManager.getInstance().runProcess(Computable {
                DumbService.getInstance(project).runReadActionInSmartMode(Computable {
                    if (!vf.isValid || ProjectFileIndex.getInstance(project).isExcluded(vf)) return@Computable false
                    val psi = PsiManager.getInstance(project).findFile(vf) ?: return@Computable false
                    if (!included(psi, context)) return@Computable false
                    for (wrapper in tools) if (wrapper.language.equals(psi.language.id, ignoreCase = true)) inspectFile(psi, uri, wrapper, profile, results, notifications)
                    true
                })
            }, EmptyProgressIndicator())
            if (inspected) files++
        }
        val reported = results.filter { it.level.rank >= options.minLevel.rank }
        val rules = tools.map { w ->
            GoSarifRule(w.shortName, w.displayName, GoSarif.plainText(runCatching { w.loadDescription() }.getOrNull()), w.groupDisplayName.ifEmpty { "Go" },
                GoSarifLevel.of(w.defaultLevel.severity) ?: GoSarifLevel.NOTE)
        }
        val sarif = GoSarif.write(GoPages.pluginVersion(), rules, reported, options.projectDir.toUri().toString(), notifications)
        options.output.parent?.let { Files.createDirectories(it) }
        Files.writeString(options.output, sarif)
        val byLevel = GoSarifLevel.entries.reversed().joinToString(", ") { l -> "${reported.count { it.level == l }} ${l.sarif}" }
        val summary = "${tools.size} inspections, $files files, ${reported.size} findings ($byLevel), ${notifications.size} inspection failures, " +
            "${System.currentTimeMillis() - started} ms -> ${options.output}"
        println("${GoInspectOptions.COMMAND}: $summary")
        GoPluginLog.info(CATEGORY, "${options.projectDir}: $summary")
        // a run whose inspections failed and found nothing must not pass CI as clean
        return if (reported.isNotEmpty()) 1 else if (notifications.isNotEmpty()) 2 else 0
    }

    /** `--inspections` names them (enabled in the profile or not); otherwise every Go / go.mod inspection the profile enables. */
    private fun tools(project: Project, profile: InspectionProfileImpl): List<LocalInspectionToolWrapper> {
        val chosen = options.inspections
        val tools = if (chosen != null) chosen.map { name ->
            profile.getInspectionTool(name, project) as? LocalInspectionToolWrapper ?: throw GoInspectOptions.UsageException("no local inspection '$name'")
        } else profile.getAllEnabledInspectionTools(project).mapNotNull { it.tool as? LocalInspectionToolWrapper }
        return tools.filter { w -> LANGUAGES.any { it.equals(w.language, ignoreCase = true) } }.sortedBy { it.shortName }
            .also { if (chosen != null && it.size != chosen.size) throw GoInspectOptions.UsageException("not Go inspections: ${chosen - it.map { w -> w.shortName }.toSet()}") }
    }

    private fun included(psi: PsiFile, context: GoBuildContext?): Boolean {
        if (GoInspectFiles.isGoModFile(psi.name)) return true
        val text = psi.viewProvider.contents
        return !GoInspectFiles.isGenerated(text) && (context == null || GoBuildConstraintEvaluator.matchFile(psi.name, text, context))
    }

    private fun inspectFile(psi: PsiFile, uri: String, wrapper: LocalInspectionToolWrapper, profile: InspectionProfileImpl, results: MutableList<GoSarifResult>, failures: MutableList<String>) {
        val key = HighlightDisplayKey.find(wrapper.shortName)
        if (options.inspections == null && key != null && !profile.isToolEnabled(key, psi)) return
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

    companion object {
        const val CATEGORY = "ci"
        private val LANGUAGES = listOf("Go", "GoModule")

        /** `.go`, `go.mod` and `go.work` under [root], in a stable order, without the directories `go` itself skips. */
        fun candidates(root: Path): List<Path> {
            val found = ArrayList<Path>()
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && GoInspectFiles.skipDirectory(dir.fileName.toString())) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && GoInspectFiles.isCandidate(file.fileName.toString())) found.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
            return found.sorted()
        }
    }
}
