package io.github.golangsupport.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageHelper
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageRunnerData
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.SimpleCoverageAnnotator
import com.intellij.coverage.view.CoverageViewExtension
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.rt.coverage.data.LineCoverage
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoRunConfiguration
import io.github.golangsupport.run.GoRunKeys
import io.github.golangsupport.testing.GoCoverage
import io.github.golangsupport.testing.GoCoverageData
import io.github.golangsupport.testing.GoCoverageFiles
import io.github.golangsupport.testing.GoLineCoverage
import java.io.File

/*
 * Coverage of `go test` through the Coverage subsystem of the platform (module com.intellij.modules.coverage; go-coverage.xml, an optional
 * dependency): "Run 'x' with Coverage", the Coverage tool window with packages and files, the bars in the editor, Hide Coverage, Manage
 * Coverage Reports. Nothing outside this package refers to these classes: in an IDE without the module they are never loaded, and the box
 * "Collect coverage" of the configuration keeps the plugin's own gutter (GoCoverageService).
 */

/** Which configurations the coverage of the platform takes, and which files it counts. Pure. */
object GoCoverageRules {
    /** `go test` writes `-coverprofile`; `go run` would need `-cover` with `GOCOVERDIR` and `go tool covdata` afterwards: not done. */
    fun applicable(command: GoCommand, overSsh: Boolean): Boolean = command == GoCommand.TEST && !overSsh

    /** What is measured: the sources, not the tests. */
    fun counted(fileName: String): Boolean = fileName.endsWith(".go") && !fileName.endsWith("_test.go")

    /** The suite name in the Coverage window and in Manage Coverage Reports. */
    fun suiteName(configurationName: String): String = configurationName.ifBlank { "Go tests" }
}

/** A cover profile as the platform's [ProjectData]: a "class" per file, keyed by its path; one-based lines with hits and FULL / PARTIAL / NONE. */
object GoCoverageProjectData {
    fun build(data: GoCoverageData, modules: List<Pair<String, String>>): ProjectData {
        val project = ProjectData()
        for (key in data.byFile.keys) {
            val path = GoCoverageFiles.resolve(key, modules) ?: continue
            val hits = data.lineHits(key).takeIf { it.isNotEmpty() } ?: continue
            val lines = arrayOfNulls<LineData>(hits.keys.max() + 1)
            for ((line, value) in hits) lines[line] = LineData(line, null).apply {
                setHits(value.second)
                setStatus(status(value.first))
            }
            project.getOrCreateClassData(path).setLines(lines)
        }
        return project
    }

    fun status(coverage: GoLineCoverage): Byte = when (coverage) {
        GoLineCoverage.COVERED -> LineCoverage.FULL
        GoLineCoverage.PARTIAL -> LineCoverage.PARTIAL
        GoLineCoverage.UNCOVERED -> LineCoverage.NONE
    }
}

class GoCoverageEngine : CoverageEngine() {
    override fun getPresentableText(): String = "Go Coverage"

    override fun isApplicableTo(conf: RunConfigurationBase<*>): Boolean = conf is GoRunConfiguration && GoCoverageRules.applicable(conf.options.command, conf.runsOverSsh())

    override fun canHavePerTestCoverage(conf: RunConfigurationBase<*>): Boolean = false

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>): CoverageEnabledConfiguration = GoCoverageEnabledConfiguration(conf)

    override fun createCoverageSuite(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long): CoverageSuite =
        GoCoverageSuite(name, project, runner, fileProvider, timestamp)

    override fun createCoverageSuite(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long, config: CoverageEnabledConfiguration): CoverageSuite =
        GoCoverageSuite(name, project, runner, fileProvider, timestamp).also { it.configuration = config.configuration }

    override fun createEmptyCoverageSuite(coverageRunner: CoverageRunner): CoverageSuite = GoCoverageSuite()

    override fun getCoverageAnnotator(project: Project): CoverageAnnotator = GoCoverageAnnotator.getInstance(project)

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile): Boolean = psiFile.fileType == GoFileType

    override fun acceptedByFilters(psiFile: PsiFile, suite: CoverageSuitesBundle): Boolean = psiFile.fileType == GoFileType && GoCoverageRules.counted(psiFile.name)

    override fun coverageProjectViewStatisticsApplicableTo(fileOrDir: VirtualFile): Boolean = !fileOrDir.isDirectory && fileOrDir.fileType == GoFileType && GoCoverageRules.counted(fileOrDir.name)

    // the "class" of a file is its path, as GoCoverageProjectData keys it
    override fun getQualifiedName(outputFile: File, sourceFile: PsiFile): String? = sourceFile.virtualFile?.path

    override fun getQualifiedNames(sourceFile: PsiFile): Set<String> = setOfNotNull(sourceFile.virtualFile?.path)

    // a file no test reached is not in the profile of `go test`; it is not guessed at
    override fun includeUntouchedFileInCoverage(qualifiedName: String, outputFile: File, sourceFile: PsiFile, suite: CoverageSuitesBundle): Boolean = false

    override fun recompileProjectAndRerunAction(module: com.intellij.openapi.module.Module, suite: CoverageSuitesBundle, chooseSuiteAction: Runnable): Boolean = false

    override fun createCoverageViewExtension(project: Project, suiteBundle: CoverageSuitesBundle): CoverageViewExtension =
        DirectoryCoverageViewExtension(project, getCoverageAnnotator(project), suiteBundle)

    companion object {
        fun getInstance(): GoCoverageEngine = EP_NAME.findExtensionOrFail(GoCoverageEngine::class.java)
    }
}

/** Reads the cover profile `go test -coverprofile` wrote. */
class GoCoverageRunner : CoverageRunner() {
    override fun loadCoverageData(sessionDataFile: File, baseCoverageSuite: CoverageSuite?): ProjectData? {
        val text = runCatching { sessionDataFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val data = GoCoverage.parse(text)
        val project = baseCoverageSuite?.project?.takeIf { !it.isDisposed }
        val modules = project?.let { ReadAction.compute<List<Pair<String, String>>, RuntimeException> { GoModulesService.getInstance(it).modules().map { module -> module.path to module.root.path } } }.orEmpty()
        return GoCoverageProjectData.build(data, modules)
    }

    override fun getPresentableName(): String = "Go"
    override fun getId(): String = ID
    override fun getDataFileExtension(): String = "out"
    override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean = engine is GoCoverageEngine

    companion object {
        const val ID = "GoCoverage"

        fun getInstance(): GoCoverageRunner = getInstance(GoCoverageRunner::class.java)
    }
}

class GoCoverageSuite : BaseCoverageSuite {
    constructor() : super()
    constructor(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long) : super(name, project, runner, fileProvider, timestamp)

    override fun getCoverageEngine(): CoverageEngine = GoCoverageEngine.getInstance()
}

/** The configuration's side of the coverage: the runner, and where the profile goes (the coverage directory of the IDE, named by the configuration). */
class GoCoverageEnabledConfiguration(configuration: RunConfigurationBase<*>) : CoverageEnabledConfiguration(configuration, GoCoverageRunner.getInstance()) {
    override fun createSuiteName(): String = GoCoverageRules.suiteName(configuration.name)
}

/** Percentages of files and directories for the Project view and the Coverage window. */
@Service(Service.Level.PROJECT)
class GoCoverageAnnotator(project: Project) : SimpleCoverageAnnotator(project) {
    companion object {
        fun getInstance(project: Project): GoCoverageAnnotator = project.service()
    }
}

/** "Run 'x' with Coverage" for a `go test` configuration: the test state with the platform's profile path, then the platform loads it. */
class GoCoverageProgramRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "GoCoverageRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == CoverageExecutor.EXECUTOR_ID && profile is GoRunConfiguration && GoCoverageRules.applicable(profile.options.command, profile.runsOverSsh())

    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = CoverageRunnerData()

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as? GoRunConfiguration ?: return null
        val coverage = CoverageEnabledConfiguration.getOrCreate(configuration)
        val file = File(coverage.coverageFilePath ?: return null)
        // the profile of the previous run must not pass for this one when the tests do not build
        file.parentFile?.mkdirs()
        file.delete()
        environment.putUserData(GoRunKeys.COVERAGE_FILE, file)
        val result = state.execute(environment.executor, this) ?: return null
        CoverageHelper.attachToProcess(configuration, result.processHandler, environment.runnerSettings)
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }
}
