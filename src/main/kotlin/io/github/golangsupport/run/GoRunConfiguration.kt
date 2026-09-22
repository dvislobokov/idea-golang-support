package io.github.golangsupport.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import java.util.UUID
import io.github.golangsupport.GoIcons
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.monitor.GoProfile
import io.github.golangsupport.monitor.GoProfiles
import io.github.golangsupport.monitor.GoRuntimeTrace
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.testing.GoTestRunState
import java.io.File

enum class GoCommand(val title: String) {
    RUN("go run"),
    TEST("go test");

    override fun toString(): String = title
}

class GoConfigurationType : ConfigurationTypeBase("GoRunConfiguration", "Go", "Run or test a Go package with the go command", NotNullLazyValue.createValue { GoIcons.Run }) {
    val factory: ConfigurationFactory = object : ConfigurationFactory(this) {
        override fun getId(): String = "Go"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = GoRunConfiguration(project, this, "")
        override fun getOptionsClass(): Class<out BaseState> = GoRunConfigurationOptions::class.java
    }

    init {
        addFactory(factory)
    }

    companion object {
        val instance: GoConfigurationType get() = ConfigurationTypeUtil.findConfigurationType(GoConfigurationType::class.java)
    }
}

class GoRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var command by enum(GoCommand.RUN)

    /** The directory of the package, or for `go run` a single `.go` file. */
    var target by string()

    /** `go test ./...`: the packages below the directory as well. */
    var recursive by property(false)

    /** `-run`: set by the gutter icons and by "Rerun Failed Tests". */
    var testPattern by string()

    /** `-bench` with the pattern instead of `-run`. */
    var benchmark by property(false)

    /** Flags of the go command itself: `-race`, `-count=1`, `-ldflags=...`. */
    var goArguments by string()
    var programArguments by string()
    var workingDirectory by string()
    var environment by map<String, String>()
    var passParentEnvironment by property(true)

    /** `go run` only: the program is built and started with `GODEBUG` traces for the Go Monitor tool window. */
    var runtimeTelemetry by property(false)

    /** `go test` only: what the tests record (`-cpuprofile` and the like), opened in pprof after the run. */
    var profile by enum(GoProfile.NONE)
}

class GoRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<GoRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): GoRunConfigurationOptions = super.getOptions() as GoRunConfigurationOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoSettingsEditor(project)

    override fun checkConfiguration() {
        val target = options.target
        if (target.isNullOrBlank()) throw RuntimeConfigurationError("Package directory is not specified")
        if (!File(target).exists()) throw RuntimeConfigurationError("Not found: $target")
        if (GoCli.findExecutable() == null) throw RuntimeConfigurationError("The 'go' executable is not found on PATH")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = when {
        // A debugger starts the program itself (see debugLaunchArguments), and the runner of the platform still executes the state first.
        executor.id == DefaultDebugExecutor.EXECUTOR_ID -> RunProfileState { _, _ -> null }
        options.command == GoCommand.TEST -> GoTestRunState(this, environment)
        options.runtimeTelemetry -> GoTelemetryRunState(this, environment)
        else -> object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(buildCommandLine()).also { ProcessTerminatedListener.attach(it) }
        }
    }

    /** The directory of the package: the target itself, or the directory of the file it is. */
    fun packageDirectory(): String = File(options.target.orEmpty()).let { if (it.isFile) it.parent.orEmpty() else it.path }

    private fun isFileTarget(): Boolean = File(options.target.orEmpty()).isFile

    /** `go` resolves the module from where it runs, so by default that is the package directory and the package is `.`. */
    private fun goDirectory(): String = options.workingDirectory?.takeIf { it.isNotBlank() && options.command == GoCommand.RUN } ?: packageDirectory()

    private fun packageArgument(): String = when {
        isFileTarget() -> options.target.orEmpty()
        options.command == GoCommand.TEST && options.recursive -> "./..."
        goDirectory() == packageDirectory() -> "."
        else -> packageDirectory()
    }

    fun goArgumentList(): List<String> = GoSettings.getInstance().buildTagArguments() + ParametersListUtil.parse(options.goArguments.orEmpty())

    /** [profileDirectory]: where `go test` writes the profile of [GoRunConfigurationOptions.profile], when the run records one. */
    fun buildCommandLine(profileDirectory: File? = null): GeneralCommandLine {
        val programArguments = ParametersListUtil.parse(options.programArguments.orEmpty())
        val profile = profileDirectory?.let { GoProfiles.arguments(options.profile, it) }.orEmpty()
        val arguments = when (options.command) {
            GoCommand.RUN -> listOf("run") + goArgumentList() + packageArgument() + programArguments
            GoCommand.TEST -> listOf("test", "-json") + goArgumentList() + GoSettings.getInstance().testArgumentList() + testSelection() + profile + packageArgument() +
                (if (programArguments.isEmpty()) emptyList() else listOf("-args") + programArguments)
        }
        return withEnvironment(GoCli.commandLine(goDirectory(), *arguments.toTypedArray()))
    }

    /** `go build -o [binary]` of the package: the first step of a run with telemetry, so that `GODEBUG` reaches the program and not the go command. */
    fun buildBinaryCommandLine(binary: File): GeneralCommandLine = withEnvironment(GoCli.commandLine(goDirectory(), *(listOf("build", "-o", binary.path) + goArgumentList() + packageArgument()).toTypedArray()))

    /** The built [binary] with the arguments of the program, in the working directory of the configuration, with the `GODEBUG` traces added. */
    fun binaryCommandLine(binary: File): GeneralCommandLine {
        val line = withEnvironment(GeneralCommandLine(listOf(binary.path) + ParametersListUtil.parse(options.programArguments.orEmpty())).withWorkDirectory(goDirectory()))
        val own = options.environment["GODEBUG"] ?: System.getenv("GODEBUG")?.takeIf { options.passParentEnvironment }
        return line.withEnvironment("GODEBUG", listOfNotNull(own?.takeIf { it.isNotBlank() }, GoRuntimeTrace.GODEBUG).joinToString(","))
    }

    private fun withEnvironment(line: GeneralCommandLine): GeneralCommandLine = line.withEnvironment(options.environment)
        .withParentEnvironmentType(if (options.passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)

    private fun testSelection(): List<String> {
        val pattern = options.testPattern?.takeIf { it.isNotBlank() }
        return when {
            options.benchmark -> listOf("-run", "^$", "-bench", pattern ?: ".")
            pattern != null -> listOf("-run", pattern)
            else -> emptyList()
        }
    }

    /** The `launch` request for delve, see [GoLaunchArguments]. */
    fun debugLaunchArguments(): Map<String, Any> = GoLaunchArguments.build(
        test = options.command == GoCommand.TEST, program = if (isFileTarget()) options.target.orEmpty() else packageDirectory(),
        programArguments = ParametersListUtil.parse(options.programArguments.orEmpty()), testPattern = options.testPattern, benchmark = options.benchmark,
        workingDirectory = options.workingDirectory, environment = options.environment, buildFlags = goArgumentList(), settings = GoSettings.getInstance(),
    )
}

/**
 * Arguments of the DAP `launch` request of delve (`dlv dap`), which compiles the program itself. Plain maps, so that the
 * configuration knows nothing about the DAP classes of the platform, which are not in every IDE.
 */
object GoLaunchArguments {
    fun build(
        test: Boolean, program: String, programArguments: List<String>, testPattern: String?, benchmark: Boolean,
        workingDirectory: String?, environment: Map<String, String>, buildFlags: List<String>, settings: GoSettings? = null,
    ): Map<String, Any> = buildMap {
        put("mode", if (test) "test" else "debug")
        // what the program prints comes as `output` events of the protocol, not from the streams of the delve process
        put("outputMode", "remote")
        // delve builds the binary itself; by default as __debug_bin... inside the package directory. Put it in the temp directory
        // instead, the way GoLand does, so nothing is left in the project; GoDebugProcess removes it when the session ends.
        put("output", debugBinaryPath())
        if (settings != null) {
            put("showGlobalVariables", settings.debugShowGlobalVariables)
            put("hideSystemGoroutines", settings.debugHideSystemGoroutines)
            put("stackTraceDepth", settings.debugStackTraceDepth)
        }
        put("program", program)
        val pattern = testPattern?.takeIf { it.isNotBlank() }
        val testArguments = when {
            !test -> emptyList()
            benchmark -> listOf("-test.run", "^$", "-test.bench", pattern ?: ".")
            pattern != null -> listOf("-test.v", "-test.run", pattern)
            else -> listOf("-test.v")
        }
        (testArguments + programArguments).takeIf { it.isNotEmpty() }?.let { put("args", it) }
        workingDirectory?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
        if (environment.isNotEmpty()) put("env", environment)
        if (buildFlags.isNotEmpty()) put("buildFlags", buildFlags.joinToString(" "))
    }

    fun attach(processId: Int): Map<String, Any> = mapOf("mode" to "local", "processId" to processId)

    /** A unique path in the temp directory for the binary delve builds, so it is never left in the project tree. */
    private fun debugBinaryPath(): String =
        File(System.getProperty("java.io.tmpdir"), "__debug_bin" + UUID.randomUUID().toString().replace("-", "") + if (SystemInfo.isWindows) ".exe" else "").path
}
