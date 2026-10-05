package io.github.golangsupport.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import io.github.golangsupport.debugger.GoBundledDelve
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.settings.GoLintersConfigurable
import io.github.golangsupport.settings.GoToolsConfigurable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * One logger for everything about finding Go and its tools: where the plugin looked and what it took. The questions from a user are
 * always "it is installed, why does the plugin not see it" (seen live: a GOPATH under another home, tools on a PATH the IDE was not
 * started with), and the log has to answer them without a debugger.
 */
object GoLog {
    /** The logger of the IDE log; the journal ([GoPluginLog]) writes its short line through it, the rest of the plugin writes to the journal. */
    val LOG = logger<GoLog>()

    /** The category of the journal for finding Go and its tools. */
    const val CATEGORY_TOOLS = "tools"

    private val DESCRIBED = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * Once per session, when something was not found: the PATH the plugin searches. The IDE takes it from a login shell
     * ([EnvironmentUtil]), which a desktop launcher does not give it and which is loaded a moment after the start; when that fails,
     * the PATH is the one of the process and a tool the user has in the shell is invisible. The log is the only way to tell which.
     */
    fun describeSearchOnce() {
        if (!DESCRIBED.compareAndSet(false, true)) return
        val ide = EnvironmentUtil.getEnvironmentMap()["PATH"].orEmpty()
        val process = System.getenv("PATH").orEmpty()
        GoPluginLog.info(CATEGORY_TOOLS, "PATH of the IDE (${ide.split(File.pathSeparatorChar).size} entries, ${if (ide == process) "the same as the process, the shell environment may not be loaded" else "from the shell"}): $ide")
        GoPluginLog.info(CATEGORY_TOOLS, "GOPATH=${System.getenv("GOPATH")}, GOBIN=${System.getenv("GOBIN")}, HOME=${System.getProperty("user.home")}")
    }
}

/** What `go env -json` says: where Go lives and where `go install` puts the tools. */
class GoEnvironment(val values: Map<String, String>) {
    val goRoot: String? get() = values["GOROOT"]?.takeIf { it.isNotEmpty() }
    val goPath: String? get() = values["GOPATH"]?.takeIf { it.isNotEmpty() }
    val goModCache: String? get() = values["GOMODCACHE"]?.takeIf { it.isNotEmpty() } ?: goPath?.let { File(it.split(File.pathSeparator).first(), "pkg/mod").path }
    val goVersion: String? get() = values["GOVERSION"]?.removePrefix("go")

    /** GOBIN, otherwise `bin` of the first GOPATH entry: the rule of `go install`. */
    val binDirectory: File?
        get() = values["GOBIN"]?.takeIf { it.isNotEmpty() }?.let(::File) ?: goPath?.let { File(it.split(File.pathSeparator).first(), "bin") }

    companion object {
        val EMPTY = GoEnvironment(emptyMap())

        fun parse(json: String): GoEnvironment {
            val root = runCatching { JsonParser.parseString(json) as? JsonObject }.getOrNull() ?: return EMPTY
            return GoEnvironment(root.entrySet().filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString })
        }

        @Volatile private var cached: Pair<String, GoEnvironment>? = null
        private val generation = java.util.concurrent.atomic.AtomicLong()
        private val flights = SingleFlight<String, GoEnvironment>()
        /** The disk cache is looked at once per session (and not after [reset]: the user asked to read the environment again). */
        private val diskChecked = java.util.concurrent.atomic.AtomicBoolean()
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        /** Of the `go` the plugin uses; asked once per executable. Blocks on the first call: not for EDT. */
        fun get(): GoEnvironment {
            val executable = GoCli.findExecutable()
            if (executable == null) {
                GoPluginLog.info(GoLog.CATEGORY_TOOLS, "go env: no go executable, nothing to ask")
                return EMPTY
            }
            cached?.takeIf { it.first == executable }?.let { return it.second }
            if (loadFromDisk(executable)) cached?.takeIf { it.first == executable }?.let { return it.second }
            // one process for every thread that asks meanwhile (scanning threads, resolve, the language server)
            return flights.run(executable, onDone = ::logJoined) { cached?.takeIf { it.first == executable }?.second ?: ask(executable) }
        }

        private fun logJoined(callers: Int) {
            if (callers > 1) GoPluginLog.info(GoLog.CATEGORY_TOOLS, "go env: $callers callers joined one process")
        }

        /** Runs `go env -json`; a non-empty answer becomes the known one and is kept on disk. */
        private fun ask(executable: String): GoEnvironment {
            val started = System.currentTimeMillis()
            val output = runCatching { GoCli.execute(GoCli.commandLine(null, "env", "-json"), 15_000) }
                .onFailure { GoPluginLog.info(GoLog.CATEGORY_TOOLS, "go env has failed to start: ${it.message}") }.getOrNull()
            val environment = if (output != null && output.exitCode == 0) parse(output.stdout) else EMPTY
            if (environment !== EMPTY) {
                GoPluginLog.info(GoLog.CATEGORY_TOOLS,
                    "go env of $executable in ${System.currentTimeMillis() - started} ms: GOVERSION=${environment.goVersion}, GOROOT=${environment.goRoot}, " +
                        "GOPATH=${environment.goPath}, GOBIN=${environment.values["GOBIN"]}, tools are looked for in ${environment.binDirectory}",
                )
                remember(executable, environment)
                save(executable, environment)
            } else {
                GoPluginLog.warn(GoLog.CATEGORY_TOOLS, "go env of $executable gave nothing: exit code ${output?.exitCode}, ${output?.stderr?.lines()?.firstOrNull { it.isNotBlank() }.orEmpty()}")
            }
            return environment
        }

        /** Makes [environment] the known one; a different answer than before moves [generation] and tells the listeners. */
        private fun remember(executable: String, environment: GoEnvironment) {
            val previous = cached
            cached = executable to environment
            if (previous == null || previous.first != executable || !GoEnvDiskCache.sameAnswer(previous.second.values, environment.values)) {
                generation.incrementAndGet()
                for (listener in listeners) runCatching(listener).onFailure { GoPluginLog.warn(GoLog.CATEGORY_TOOLS, "go env listener failed", it) }
            }
        }

        private fun diskFile(): Path = GoPluginData.root().resolve(GoEnvDiskCache.FILE_NAME)

        private fun diskKey(executable: String, values: Map<String, String>?): String {
            val environment = EnvironmentUtil.getEnvironmentMap()
            val goEnvFile = GoEnvDiskCache.goEnvFile(environment, values, SystemInfo.isWindows, SystemInfo.isMac, System.getProperty("user.home"))
            return GoEnvDiskCache.key(File(executable), goEnvFile, environment)
        }

        private fun save(executable: String, environment: GoEnvironment) {
            runCatching {
                val file = diskFile()
                Files.createDirectories(file.parent)
                Files.writeString(file, GoEnvDiskCache.encode(GoEnvDiskCache.Stored(diskKey(executable, environment.values), environment.values, System.currentTimeMillis())))
            }.onFailure { GoPluginLog.info(GoLog.CATEGORY_TOOLS, "go env is not kept on disk: ${GoPluginLog.describe(it)}") }
        }

        /**
         * The answer of an earlier session, when nothing it depends on has changed ([GoEnvDiskCache.key]): known at once, checked by one
         * `go env` in the background afterwards. Never on EDT (the shell environment of the IDE may still be loading). True when served.
         */
        private fun loadFromDisk(executable: String): Boolean {
            val application = ApplicationManager.getApplication() ?: return false
            if (application.isDispatchThread || !diskChecked.compareAndSet(false, true)) return false
            val stored = runCatching { diskFile().takeIf { Files.isRegularFile(it) }?.let { GoEnvDiskCache.decode(Files.readString(it)) } }.getOrNull() ?: return false
            if (GoEnvDiskCache.valid(stored, diskKey(executable, stored.values)) == null) {
                GoPluginLog.info(GoLog.CATEGORY_TOOLS, "go env on disk is stale (the go executable, the go env file or the environment changed): asking go")
                return false
            }
            if (cached == null) {
                remember(executable, GoEnvironment(stored.values))
                GoPluginLog.info(GoLog.CATEGORY_TOOLS, "go env served from the disk cache (age ${(System.currentTimeMillis() - stored.savedAt) / 1000} s), checking it in the background")
            }
            application.executeOnPooledThread { refresh(executable) }
            return true
        }

        /** The background check of an answer served from disk: one process; a different answer replaces it and the listeners hear of it. */
        private fun refresh(executable: String) {
            val before = cached?.second?.values
            val fresh = flights.run(executable, onDone = ::logJoined) { ask(executable) }
            if (fresh !== EMPTY) GoPluginLog.info(GoLog.CATEGORY_TOOLS, if (before != null && GoEnvDiskCache.sameAnswer(fresh.values, before)) "go env checked: the same as on disk" else "go env checked: changed since the disk cache, updated")
        }

        /** What is known without starting a process, for EDT: the last answer of `go env`, otherwise the environment variables and defaults. */
        fun quick(): GoEnvironment = known() ?: GoEnvironment(buildMap {
            System.getenv("GOBIN")?.let { put("GOBIN", it) }
            put("GOPATH", System.getenv("GOPATH") ?: File(System.getProperty("user.home"), "go").path)
        })

        /** The known answer; off EDT the disk cache is looked at the first time. */
        private fun known(): GoEnvironment? {
            cached?.let { return it.second }
            if (!diskChecked.get() && ApplicationManager.getApplication()?.isDispatchThread == false) {
                // without a go nothing is looked at again: this is on the path of every toolchain question
                val executable = GoCli.findExecutable()
                if (executable == null) diskChecked.set(true) else loadFromDisk(executable)
            }
            return cached?.second
        }

        /** Whether `go env` has been read (in this session or from the disk cache): until then [quick] guesses GOPATH, and a tool installed elsewhere is not found. */
        fun isKnown(): Boolean = known() != null

        /** Moves whenever the known answer changes (first read, a different answer of the background check, [reset]): a part of the toolchain key. */
        fun generation(): Long = generation.get()

        /** [listener] runs, on the thread that read `go env`, whenever the known answer changes. */
        fun addChangeListener(listener: () -> Unit) {
            listeners += listener
        }

        /**
         * Reads `go env` in the background, once, and calls [onReady] when it is there (at once when it is already). GOPATH is not
         * always `$HOME/go` and GOBIN is not always on PATH (seen live: a machine where `go env GOPATH` was under another home, so
         * gopls was "not installed" until something else had asked `go env`). Callers that come meanwhile join the same process.
         */
        fun whenKnown(onReady: () -> Unit) {
            if (isKnown()) return onReady()
            ApplicationManager.getApplication().executeOnPooledThread {
                get()
                if (isKnown()) onReady()
            }
        }

        /** Forgets the answer: the next [get] runs `go env` again (the disk cache is not trusted for the rest of the session either). */
        fun reset() {
            diskChecked.set(true)
            if (cached != null) generation.incrementAndGet()
            cached = null
        }
    }
}

/**
 * The tools of the Go ecosystem the plugin drives, each installed by `go install <module>@latest`. Where one is looked for: the path
 * from Settings | Go | Tools, then PATH, then GOBIN / GOPATH/bin (a directory installers do not put on PATH by themselves).
 */
enum class GoTool(val command: String, val module: String, val purpose: String, val documentation: String) {
    GOPLS("gopls", "golang.org/x/tools/gopls", "Language server: errors, completion, navigation, refactorings", "https://go.dev/gopls"),
    DELVE("dlv", "github.com/go-delve/delve/cmd/dlv", "Debugger behind the Debug button", "https://github.com/go-delve/delve"),
    GOLANGCI_LINT("golangci-lint", "github.com/golangci/golangci-lint/v2/cmd/golangci-lint", "Optional linter: warnings in the editor once turned on on the Linters page", "https://golangci-lint.run"),
    GOIMPORTS("goimports", "golang.org/x/tools/cmd/goimports", "Reformat Code that also fixes the imports", "https://pkg.go.dev/golang.org/x/tools/cmd/goimports"),
    GOVULNCHECK("govulncheck", "golang.org/x/vuln/cmd/govulncheck", "Vulnerabilities: known issues reachable from the code, in the editor and the Go Dependencies window", "https://go.dev/blog/vuln");

    /** The path set in the settings, when the file is there. */
    fun configured(): File? = GoSettings.getInstance().toolPath(command).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

    /**
     * PATH (the one the IDE was started with, which a desktop launcher does not take from the shell profile), then GOBIN / GOPATH/bin,
     * then the places `go install` and the package managers use. [GoEnvironment.quick] only guesses GOPATH until `go env` has been read,
     * so a tool may be "not installed" for the first seconds of a session — [GoEnvironment.whenKnown] is how the callers wait for that.
     */
    fun detect(): File? {
        val name = GoCli.executableName(command)
        PathEnvironmentVariableUtil.findInPath(name)?.let { return it }
        return searchDirectories().map { File(it, name) }.firstOrNull { it.isFile }
    }

    /** Where a tool is looked for besides PATH, in order; also what the log names when nothing is found. */
    fun searchDirectories(): List<File> {
        val home = System.getProperty("user.home")
        val environment = GoEnvironment.quick()
        val wellKnown = if (SystemInfo.isWindows) listOf("$home\\go\\bin") else listOf("$home/go/bin", "$home/.local/bin", "/usr/local/bin", "/opt/homebrew/bin", "/usr/local/go/bin")
        // the tools installed into the plugin data directory (GOBIN of `go install` when the user chose one) come first
        return (listOfNotNull(GoPluginData.bin().toFile().takeIf { GoPluginData.isCustom }, environment.binDirectory) + wellKnown.map(::File)).distinctBy { it.path }
    }

    fun find(): File? {
        // delve ships with the plugin as sources and is built in the background (GoBundledDelve); the path from the settings still wins
        val found = configured() ?: (if (this == DELVE) GoBundledDelve.binary() else null) ?: detect()
        // on change only: find() is asked on every file opened, on every annotator run and before every command
        val previous = LAST_FOUND.put(this, found?.path ?: NOT_FOUND)
        if (previous != (found?.path ?: NOT_FOUND)) {
            if (found != null) GoPluginLog.info(GoLog.CATEGORY_TOOLS, "$command: $found (${if (configured() != null) "the path from the settings" else if (this == DELVE && found == GoBundledDelve.binary()) "built from the sources of the plugin" else "found by the plugin"})")
            else {
                GoPluginLog.info(GoLog.CATEGORY_TOOLS, "$command is not found: not on the PATH of the IDE and not in ${searchDirectories().joinToString(", ")}; `go env` read: ${GoEnvironment.isKnown()}")
                GoLog.describeSearchOnce()
            }
        }
        return found
    }

    fun installCommand(): List<String> = listOf("install", "$module@latest")

    /**
     * Installs or updates the tool on the calling (background) thread, handing over what `go` prints as it arrives.
     * For places with their own progress UI, such as the settings page: a modal dialog hides the Build tool window.
     * Returns the exit code, or -1 with the reason passed to [onText] when `go` cannot be started.
     */
    fun installBlocking(onText: (String) -> Unit): Int = try {
        val handler = CapturingProcessHandler(GoCli.commandLine(null, *installCommand().toTypedArray()))
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType !== ProcessOutputTypes.SYSTEM) onText(event.text)
            }
        })
        // the directory the tool lands in is known for sure only to `go env`
        GoEnvironment.get()
        handler.runProcess(600_000).exitCode
    } catch (e: Exception) {
        onText(e.message.orEmpty())
        -1
    }

    /** Installs or updates the tool in a background task; [onSuccess] runs on EDT. */
    fun install(project: Project, onSuccess: () -> Unit = {}) {
        val title = "Installing $command"
        val commands = GoCli.commandLinesOrNotify(project, title) { listOf(GoCli.commandLine(null, *installCommand().toTypedArray())) } ?: return
        GoCli.runInBackground(project, title, commands, onSuccess = onSuccess)
    }

    companion object {
        private const val NOT_FOUND = "-"

        /**
         * The tools the "Go tools are missing" notification at project open may ask for: govulncheck serves one button of the Go Dependencies
         * window and is offered there; golangci-lint is optional (Settings | Go | Linters, off by default) and asked for only while it is turned on.
         */
        fun offeredAtStart(golangciLint: Boolean, languageServer: Boolean = false): List<GoTool> =
            entries.filter { it != GOVULNCHECK && (it != GOLANGCI_LINT || golangciLint) && (it != GOPLS || languageServer) }

        /** What [find] gave last for each tool, so that the log has a line when it changes and not on every call. */
        private val LAST_FOUND = java.util.concurrent.ConcurrentHashMap<GoTool, String>()

        /** Installs [tools] one after another in one background task; the language server, when among them, is restarted afterwards by its module. */
        fun installAll(project: Project, tools: List<GoTool>, onSuccess: () -> Unit = {}) {
            val title = "Installing " + tools.joinToString(", ") { it.command }
            val commands = GoCli.commandLinesOrNotify(project, title) { tools.map { GoCli.commandLine(null, *it.installCommand().toTypedArray()) } } ?: return
            GoCli.runInBackground(project, title, commands, onSuccess = { GoEnvironment.reset(); onSuccess() })
        }
    }

    /** "The tool is not installed" with the buttons to install it and to read about it. */
    fun offerInstallation(project: Project, title: String, onInstalled: () -> Unit = {}) {
        NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification(title, "<code>$command</code> is not installed.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Install") { install(project, onInstalled) })
            .addAction(NotificationAction.createSimple("About the Tool") { BrowserUtil.browse(documentation) })
            // golangci-lint is optional: its page has the switch that turns it off; the other tools have their paths on the Tools page
            .addAction(NotificationAction.createSimple("Configure...") {
                val settings = ShowSettingsUtil.getInstance()
                if (this == GOLANGCI_LINT) settings.showSettingsDialog(project, GoLintersConfigurable::class.java) else settings.showSettingsDialog(project, GoToolsConfigurable::class.java)
            })
            .notify(project)
    }
}
