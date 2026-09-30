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
    TEST("go test"),

    /** A binary that is built already: run as it is, or debugged with `launch` in `exec` mode (delve builds nothing). */
    EXEC("Binary"),

    /** A core dump (a minidump on Windows) with the binary it came from: `launch` in `core` mode; Debug only. */
    CORE("Core dump"),

    /** A `dlv dap --listen=host:port` elsewhere: attach to a process there, or launch a binary of that machine; Debug only. */
    REMOTE("Remote dlv dap");

    val isDebugOnly: Boolean get() = this == CORE || this == REMOTE

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

    /** `-benchmem`: B/op and allocs/op next to ns/op. */
    var benchmem by property(true)

    /** `-fuzz` with the pattern: fuzzing, which runs until it finds a failing input or is stopped (`-fuzztime` goes to the go tool arguments). */
    var fuzz by property(false)

    /** `go test` only: `-coverprofile`, shown in the editors and in the Go Tests window after the run. */
    var coverage by property(false)

    /** Flags of the go command itself: `-race`, `-count=1`, `-ldflags=...`. */
    var goArguments by string()

    /** `-race`: the race detector, for run and test alike. */
    var race by property(false)

    /** `go test` only: `-count=1`, the tests run instead of their cached result. */
    var noTestCache by property(false)

    /** `go test` only: `-short`, `-failfast`, `-timeout`. */
    var short by property(false)
    var failFast by property(false)
    var timeout by string()
    var programArguments by string()
    var workingDirectory by string()
    var environment by map<String, String>()
    var passParentEnvironment by property(true)

    /** `go run` only: the program is built and started with `GODEBUG` traces for the Go Monitor tool window. */
    var runtimeTelemetry by property(false)

    /** `go test` only: what the tests record (`-cpuprofile` and the like), opened in pprof after the run. */
    var profile by enum(GoProfile.NONE)

    /** [GoCommand.EXEC] and [GoCommand.CORE]: the binary; [GoCommand.REMOTE]: the path of the binary on the remote machine to launch there. */
    var binary by string()

    /** [GoCommand.CORE]: the core dump. */
    var coreFile by string()

    var remoteHost by string("localhost")
    var remotePort by property(2345)

    /** [GoCommand.REMOTE]: a process of the remote machine to attach to; 0 launches [binary] there instead. */
    var remotePid by property(0)

    /** `local=remote`, a line each: where the sources of the program are here and where they were when it was built (`substitutePath` of delve). */
    var pathSubstitutions by string()
}

class GoRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<GoRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): GoRunConfigurationOptions = super.getOptions() as GoRunConfigurationOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoSettingsEditor(project)

    override fun checkConfiguration() {
        when (options.command) {
            GoCommand.RUN, GoCommand.TEST -> {
                val target = options.target
                if (target.isNullOrBlank()) throw RuntimeConfigurationError("Package directory is not specified")
                if (!File(target).exists()) throw RuntimeConfigurationError("Not found: $target")
                if (GoCli.findExecutable() == null) throw RuntimeConfigurationError("The 'go' executable is not found on PATH")
            }
            GoCommand.EXEC -> if (!File(options.binary.orEmpty()).isFile) throw RuntimeConfigurationError("The binary is not found: ${options.binary.orEmpty()}")
            GoCommand.CORE -> {
                if (!File(options.binary.orEmpty()).isFile) throw RuntimeConfigurationError("The binary the core dump came from is not found: ${options.binary.orEmpty()}")
                if (!File(options.coreFile.orEmpty()).isFile) throw RuntimeConfigurationError("The core dump is not found: ${options.coreFile.orEmpty()}")
            }
            GoCommand.REMOTE -> {
                if (options.remotePort !in 1..65535) throw RuntimeConfigurationError("The port of dlv dap is not set")
                if (options.remotePid <= 0 && options.binary.isNullOrBlank()) throw RuntimeConfigurationError("Either a process id on the remote machine or the path of a binary there is needed")
            }
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = when {
        // A debugger starts the program itself (see debugLaunchArguments), and the runner of the platform still executes the state first.
        executor.id == DefaultDebugExecutor.EXECUTOR_ID -> RunProfileState { _, _ -> null }
        options.command.isDebugOnly -> throw com.intellij.execution.ExecutionException("${options.command.title}: only Debug makes sense here")
        options.command == GoCommand.TEST -> GoTestRunState(this, environment)
        options.command == GoCommand.EXEC -> object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(withEnvironment(GeneralCommandLine(listOf(options.binary.orEmpty()) + ParametersListUtil.parse(options.programArguments.orEmpty())).withWorkDirectory(execDirectory()))).also { ProcessTerminatedListener.attach(it) }
        }
        options.runtimeTelemetry -> GoTelemetryRunState(this, environment)
        else -> object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(buildCommandLine()).also { ProcessTerminatedListener.attach(it) }
        }
    }

    /** Where a binary runs: the working directory of the configuration, or its own directory. */
    private fun execDirectory(): String = options.workingDirectory?.takeIf { it.isNotBlank() } ?: File(options.binary.orEmpty()).parent.orEmpty()

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

    fun goArgumentList(): List<String> = GoSettings.getInstance().buildTagArguments() + listOfNotNull("-race".takeIf { options.race }) +
        listOfNotNull("-count=1".takeIf { options.noTestCache && options.command == GoCommand.TEST }) + ParametersListUtil.parse(options.goArguments.orEmpty())

    /**
     * [profileDirectory]: where `go test` writes the profile of [GoRunConfigurationOptions.profile], when the run records one;
     * [coverageFile]: where it writes the coverage profile, when the run collects one.
     */
    fun buildCommandLine(profileDirectory: File? = null, coverageFile: File? = null): GeneralCommandLine {
        val programArguments = ParametersListUtil.parse(options.programArguments.orEmpty())
        val profile = profileDirectory?.let { GoProfiles.arguments(options.profile, it) }.orEmpty()
        val coverage = coverageFile?.let { listOf("-coverprofile=${it.path}") }.orEmpty()
        val arguments = when (options.command) {
            GoCommand.RUN -> listOf("run") + goArgumentList() + packageArgument() + programArguments
            GoCommand.TEST -> listOf("test", "-json") + goArgumentList() + testFlags() + GoSettings.getInstance().testArgumentList() + testSelection() + profile + coverage + packageArgument() +
                (if (programArguments.isEmpty()) emptyList() else listOf("-args") + programArguments)
            // a binary and the debug-only kinds have no go command to run (see getState)
            GoCommand.EXEC, GoCommand.CORE, GoCommand.REMOTE -> throw com.intellij.execution.ExecutionException("${options.command.title} is not run with the go command")
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

    /** `-short`, `-failfast`, `-timeout=...` of the configuration, as `go test` takes them. */
    fun testFlags(): List<String> = GoTestFlags.forGoTest(options.short, options.failFast, options.timeout)

    private fun testSelection(): List<String> {
        val pattern = options.testPattern?.takeIf { it.isNotBlank() }
        return when {
            options.benchmark -> listOf("-run", "^$", "-bench", pattern ?: ".") + listOfNotNull("-benchmem".takeIf { options.benchmem })
            options.fuzz -> listOf("-run", "^$", "-fuzz", pattern ?: ".")
            pattern != null -> listOf("-run", pattern)
            else -> emptyList()
        }
    }

    /** A remote configuration with a process id attaches; everything else is a `launch`. */
    fun debugIsAttach(): Boolean = options.command == GoCommand.REMOTE && options.remotePid > 0

    /** The `launch` (or `attach`) request for delve, see [GoLaunchArguments]. */
    fun debugLaunchArguments(): Map<String, Any> {
        val settings = GoSettings.getInstance()
        val substitutions = GoLaunchArguments.substitutions(options.pathSubstitutions.orEmpty())
        val programArguments = ParametersListUtil.parse(options.programArguments.orEmpty())
        return when (options.command) {
            GoCommand.EXEC -> GoLaunchArguments.exec(options.binary.orEmpty(), programArguments, options.workingDirectory, options.environment, substitutions, settings)
            GoCommand.CORE -> GoLaunchArguments.core(options.binary.orEmpty(), options.coreFile.orEmpty(), substitutions, settings)
            GoCommand.REMOTE -> if (options.remotePid > 0) GoLaunchArguments.attach(options.remotePid, substitutions)
                else GoLaunchArguments.exec(options.binary.orEmpty(), programArguments, options.workingDirectory, options.environment, substitutions, settings)
            GoCommand.RUN, GoCommand.TEST -> GoLaunchArguments.build(
                test = options.command == GoCommand.TEST, program = if (isFileTarget()) options.target.orEmpty() else packageDirectory(),
                programArguments = programArguments, testPattern = options.testPattern, benchmark = options.benchmark,
                workingDirectory = options.workingDirectory, environment = options.environment, buildFlags = goArgumentList(), settings = settings,
                // created here: go build does not make the directory of -o
                binaryDirectory = settings.debugBinaryDirectory(packageDirectory())?.also { File(it).mkdirs() },
                testFlags = GoTestFlags.forBinary(testFlags()),
            )
        }
    }
}

/** The flags of `go test` a configuration has boxes for, and the same flags for the test binary delve runs. Pure. */
object GoTestFlags {
    fun forGoTest(short: Boolean, failFast: Boolean, timeout: String?): List<String> = listOfNotNull(
        "-short".takeIf { short }, "-failfast".takeIf { failFast }, timeout?.trim()?.takeIf { it.isNotEmpty() }?.let { "-timeout=$it" },
    )

    /** `-short` of `go test` is `-test.short` of the binary: what `go test` passes on, delve does not. */
    fun forBinary(flags: List<String>): List<String> = flags.map { "-test." + it.removePrefix("-") }
}

/**
 * Arguments of the DAP `launch` request of delve (`dlv dap`), which compiles the program itself. Plain maps, so that the
 * configuration knows nothing about the DAP classes of the platform, which are not in every IDE.
 */
object GoLaunchArguments {
    fun build(
        test: Boolean, program: String, programArguments: List<String>, testPattern: String?, benchmark: Boolean,
        workingDirectory: String?, environment: Map<String, String>, buildFlags: List<String>, settings: GoSettings? = null, binaryDirectory: String? = null,
        /** The flags of the test binary (`-test.short`, ...), see [GoTestFlags.forBinary]. */
        testFlags: List<String> = emptyList(),
    ): Map<String, Any> = buildMap {
        put("mode", if (test) "test" else "debug")
        // what the program prints comes as `output` events of the protocol, not from the streams of the delve process
        put("outputMode", "remote")
        // delve builds the binary itself; by default as __debug_bin... inside the package directory. Put it in the temp directory
        // instead, the way GoLand does, so nothing is left in the project, or where the configuration says (a temp directory on another
        // drive or with noexec is not where a program runs from); GoDebugProcess removes it when the session ends either way.
        put("output", debugBinaryPath(binaryDirectory))
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
        ((if (test) testFlags else emptyList()) + testArguments + programArguments).takeIf { it.isNotEmpty() }?.let { put("args", it) }
        workingDirectory?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
        if (environment.isNotEmpty()) put("env", environment)
        if (buildFlags.isNotEmpty()) put("buildFlags", buildFlags.joinToString(" "))
    }

    fun attach(processId: Int, substitutions: List<Map<String, String>> = emptyList()): Map<String, Any> = buildMap {
        put("mode", "local")
        put("processId", processId)
        if (substitutions.isNotEmpty()) put("substitutePath", substitutions)
    }

    /** `launch` of a binary that exists: nothing is built, `program` is its path (on the machine of the delve that serves the request). */
    fun exec(binary: String, programArguments: List<String>, workingDirectory: String?, environment: Map<String, String>, substitutions: List<Map<String, String>>, settings: GoSettings? = null): Map<String, Any> = buildMap {
        put("mode", "exec")
        put("outputMode", "remote")
        put("program", binary)
        if (settings != null) debugSettings(this, settings)
        if (programArguments.isNotEmpty()) put("args", programArguments)
        workingDirectory?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
        if (environment.isNotEmpty()) put("env", environment)
        if (substitutions.isNotEmpty()) put("substitutePath", substitutions)
    }

    /** `launch` of a core dump: the binary it came from and the dump; nothing runs, the stacks and the values are what there is. */
    fun core(binary: String, coreFile: String, substitutions: List<Map<String, String>>, settings: GoSettings? = null): Map<String, Any> = buildMap {
        put("mode", "core")
        put("program", binary)
        put("coreFilePath", coreFile)
        if (settings != null) debugSettings(this, settings)
        if (substitutions.isNotEmpty()) put("substitutePath", substitutions)
    }

    /**
     * The request as lines for the console of the session, the way GoLand shows what it runs: the build delve does (`go build
     * -gcflags="all=-N -l" ...`, the flags delve adds itself) and the program it starts with its arguments, or the process it attaches to.
     */
    fun describe(arguments: Map<String, Any?>, attach: Boolean): List<String> {
        val mode = arguments["mode"] as? String
        val program = arguments["program"] as? String
        val args = (arguments["args"] as? List<*>)?.joinToString(" ") { quote(it.toString()) }.orEmpty()
        val lines = ArrayList<String>()
        when {
            attach -> lines += "attach to process ${arguments["processId"] ?: "?"}"
            mode == "debug" || mode == "test" -> {
                val tool = if (mode == "test") "go test -c" else "go build"
                val flags = listOfNotNull("-gcflags=\"all=-N -l\"", (arguments["buildFlags"] as? String)?.takeIf { it.isNotBlank() }, (arguments["output"] as? String)?.let { "-o ${quote(it)}" })
                lines += "$tool ${flags.joinToString(" ")} ${quote(program.orEmpty())}"
                lines += "run: ${quote(program.orEmpty())} $args".trimEnd()
            }
            mode == "core" -> lines += "core dump ${arguments["coreFilePath"]} of ${program.orEmpty()}"
            else -> lines += "run: ${quote(program.orEmpty())} $args".trimEnd()
        }
        (arguments["cwd"] as? String)?.takeIf { it.isNotBlank() }?.let { lines += "in $it" }
        (arguments["env"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }?.let { env -> lines += "env: " + env.entries.joinToString(" ") { "${it.key}=${it.value}" } }
        return lines
    }

    private fun quote(s: String): String = if (' ' in s) "\"$s\"" else s

    private fun debugSettings(arguments: MutableMap<String, Any>, settings: GoSettings) {
        arguments["showGlobalVariables"] = settings.debugShowGlobalVariables
        arguments["hideSystemGoroutines"] = settings.debugHideSystemGoroutines
        arguments["stackTraceDepth"] = settings.debugStackTraceDepth
    }

    /** `local=remote` lines -> the `substitutePath` entries of delve (`from` is the path in the binary, `to` the path here); blank and broken lines are skipped. */
    fun substitutions(text: String): List<Map<String, String>> = text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
        val separator = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        val local = line.substring(0, separator).trim()
        val remote = line.substring(separator + 1).trim()
        if (local.isEmpty() || remote.isEmpty()) null else mapOf("from" to remote, "to" to local)
    }

    /** A unique path for the binary delve builds: in [directory], or in the temp directory, so it is never left in the project tree. */
    fun debugBinaryPath(directory: String? = null): String =
        File(directory ?: System.getProperty("java.io.tmpdir"), "__debug_bin" + UUID.randomUUID().toString().replace("-", "") + if (SystemInfo.isWindows) ".exe" else "").path
}
