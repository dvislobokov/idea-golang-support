package io.github.golangsupport.run

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowId
import io.github.golangsupport.monitor.GoProfile
import io.github.golangsupport.monitor.GoProfiles
import java.io.File
import javax.swing.Icon

/** What a run of a "Go" configuration is asked for beyond its own fields; set on the [ExecutionEnvironment] by a runner, read by the state. */
object GoRunKeys {
    /** The coverage profile the Coverage executor of the platform reads after the run (`-coverprofile`). */
    val COVERAGE_FILE: Key<File> = Key.create("io.github.golangsupport.run.coverageFile")

    /** The kind the Profile executor records; the profile is opened when the run ends. */
    val PROFILE: Key<GoProfile> = Key.create("io.github.golangsupport.run.profile")
}

/** The flags `go test` records with: a profile into its directory, the coverage profile into its file. Pure. */
object GoTestRecording {
    fun arguments(profile: GoProfile, profileDirectory: File?, coverageFile: File?, coverMode: String? = null): List<String> =
        profileDirectory?.let { GoProfiles.arguments(profile, it) }.orEmpty() +
            coverageFile?.let { listOf("-coverprofile=${it.path}") + listOfNotNull(coverMode?.let { mode -> "-covermode=$mode" }) }.orEmpty()
}

/** Run | Run with Profiler: the kinds GoLand offers there, and when a configuration can be profiled at all. Pure. */
object GoProfiler {
    val KINDS: List<GoProfile> = listOf(GoProfile.CPU, GoProfile.MEMORY, GoProfile.BLOCK, GoProfile.MUTEX)

    fun label(kind: GoProfile): String = when (kind) {
        GoProfile.CPU -> "CPU Profiler"
        GoProfile.MEMORY -> "Memory Profiler"
        GoProfile.BLOCK -> "Blocking Profiler"
        GoProfile.MUTEX -> "Mutex Profiler"
        GoProfile.TRACE -> "Execution Tracer"
        GoProfile.NONE -> "Profiler"
    }

    /** The kind of a run: asked for by an item of the menu, else the Profile field of the configuration, else CPU. */
    fun kindOf(requested: GoProfile?, configured: GoProfile): GoProfile = requested?.takeIf { it != GoProfile.NONE } ?: configured.takeIf { it != GoProfile.NONE } ?: GoProfile.CPU

    /** Why a configuration cannot be profiled, or null. `go test` writes the profiles by flags; a program only writes them with `runtime/pprof` in its own code. */
    fun unsupported(command: GoCommand, overSsh: Boolean): String? = when {
        command != GoCommand.TEST -> "Profiling needs a go test configuration: a program started by ${command.title} writes a profile only through runtime/pprof in its own code"
        overSsh -> "A configuration with an SSH host is debugged only"
        else -> null
    }

    fun canProfile(profile: RunProfile?): Boolean = profile is GoRunConfiguration && unsupported(profile.options.command, profile.runsOverSsh()) == null
}

/** "Profile 'x'": `go test` with `-cpuprofile` (or the kind asked for), the profile opened in the IDE when the tests end. */
class GoProfilerExecutor : Executor() {
    override fun getToolWindowId(): String = ToolWindowId.RUN
    override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowRun
    override fun getIcon(): Icon = AllIcons.Actions.Profile
    override fun getDisabledIcon(): Icon = com.intellij.openapi.util.IconLoader.getDisabledIcon(icon)
    override fun getDescription(): String = "Run the tests of the selected configuration with a profiler (go test -cpuprofile and the like)"
    override fun getActionName(): String = "Profile"
    override fun getId(): String = EXECUTOR_ID
    override fun getStartActionText(): String = "Profile"
    override fun getStartActionText(configurationName: String): String = "Profile '${shortenNameIfNeeded(configurationName)}'"
    override fun getContextActionId(): String = "GoProfileContext"
    override fun getHelpId(): String? = null

    companion object {
        const val EXECUTOR_ID = "GoProfiler"

        fun getInstance(): Executor? = ExecutorRegistry.getInstance().getExecutorById(EXECUTOR_ID)
    }
}

/** The runner of [GoProfilerExecutor]: the test state of the configuration with [GoRunKeys.PROFILE] set, in the Run tool window. */
class GoProfilerRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "GoProfilerRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean = executorId == GoProfilerExecutor.EXECUTOR_ID && GoProfiler.canProfile(profile)

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as? GoRunConfiguration ?: return null
        environment.putUserData(GoRunKeys.PROFILE, GoProfiler.kindOf(environment.getUserData(GoRunKeys.PROFILE), configuration.options.profile))
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }
}

/** Run | Run with Profiler: "Profile 'x' with 'CPU Profiler'" and the other kinds, for the selected configuration (GoLand's group). */
class GoRunWithProfilerGroup : ActionGroup(), DumbAware {
    private val children: Array<AnAction> = GoProfiler.KINDS.map { ProfileWith(it) }.toTypedArray()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children

    private class ProfileWith(private val kind: GoProfile) : AnAction("Profile with '${GoProfiler.label(kind)}'", null, icon(kind)), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val settings = e.project?.let { RunManager.getInstance(it).selectedConfiguration }
            val configuration = settings?.configuration as? GoRunConfiguration
            e.presentation.text = if (settings == null) "Profile with '${GoProfiler.label(kind)}'" else "Profile '${Executor.shortenNameIfNeeded(settings.name)}' with '${GoProfiler.label(kind)}'"
            e.presentation.isEnabled = configuration != null && GoProfiler.canProfile(configuration)
            e.presentation.description = configuration?.let { GoProfiler.unsupported(it.options.command, it.runsOverSsh()) } ?: "go test ${kind.flag}: the profile opens in the IDE when the tests end"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            run(project, kind)
        }
    }

    companion object {
        private fun icon(kind: GoProfile): Icon = when (kind) {
            GoProfile.CPU -> AllIcons.Actions.ProfileCPU
            GoProfile.MEMORY -> AllIcons.Actions.ProfileMemory
            else -> AllIcons.Actions.Profile
        }

        /** The selected configuration under [GoProfilerExecutor], recording [kind]. */
        fun run(project: Project, kind: GoProfile) {
            val settings = RunManager.getInstance(project).selectedConfiguration ?: return
            val executor = GoProfilerExecutor.getInstance() ?: return
            val environment = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build() ?: return
            environment.putUserData(GoRunKeys.PROFILE, kind)
            ProgramRunnerUtil.executeConfiguration(environment, true, true)
        }
    }
}
