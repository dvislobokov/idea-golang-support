package io.github.golangsupport.ci

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.observation.Observation
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * `idea64 go-inspect <projectDir> <out.sarif> [--inspections a,b] [--min-severity weak|warning|error]`: the plugin's Go and go.mod inspections
 * over a project without the UI, as a SARIF 2.1.0 report for CI. The platform's own `inspect` command writes only its XML / JSON formats
 * (SARIF there comes from the Qodana plugin, which is IDEA-only and licensed), hence a starter of our own. The run itself is [GoInspectRun],
 * the same as Go | Export Inspections to SARIF in the IDE.
 *
 * Exit codes: 0 no findings at or above the minimal severity, 1 findings, 2 bad arguments or a failed run.
 */
class GoInspectStarter : ApplicationStarter {
    override val requiredModality: Int get() = ApplicationStarter.NOT_IN_EDT
    override val isHeadless: Boolean get() = true

    override fun main(args: List<String>) {
        val code = try {
            GoHeadlessInspection(GoInspectOptions.parse(args)).run()
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

/** Headless around [GoInspectRun]: open the project, wait for the indexes, run, write the report. Not on the EDT: it waits for indexing and for `go env`. */
internal class GoHeadlessInspection(private val options: GoInspectOptions) {

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
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(options.projectDir) ?: throw IllegalStateException("not in VFS: ${options.projectDir}")
        VfsUtil.markDirtyAndRefresh(false, true, true, rootVf)
        val covered = ReadAction.compute<Boolean, Throwable> { ProjectRootManager.getInstance(project).contentRoots.any { VfsUtilCore.isAncestor(it, rootVf, false) } }
        if (!covered) System.err.println("${GoInspectOptions.COMMAND}: warning: ${options.projectDir} is not a content root of the opened project, resolve may be incomplete")
        val report = GoInspectRun(project, rootVf, options.inspections, options.minLevel).run(EmptyProgressIndicator())
        options.output.parent?.let { Files.createDirectories(it) }
        Files.writeString(options.output, report.sarif(options.projectDir.toUri().toString()))
        val summary = "${report.summary} -> ${options.output}"
        println("${GoInspectOptions.COMMAND}: $summary")
        GoPluginLog.info(GoInspectRun.CATEGORY, "${options.projectDir}: $summary")
        return report.exitCode
    }
}
