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
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.settings.GoSettingsConfigurable
import java.io.File

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

        /** Of the `go` the plugin uses; asked once per executable. Blocks on the first call: not for EDT. */
        fun get(): GoEnvironment {
            val executable = GoCli.findExecutable() ?: return EMPTY
            cached?.takeIf { it.first == executable }?.let { return it.second }
            val output = runCatching { GoCli.execute(GoCli.commandLine(null, "env", "-json"), 15_000) }.getOrNull()
            val environment = if (output != null && output.exitCode == 0) parse(output.stdout) else EMPTY
            if (environment !== EMPTY) cached = executable to environment
            return environment
        }

        /** What is known without starting a process, for EDT: the last answer of `go env`, otherwise the environment variables and defaults. */
        fun quick(): GoEnvironment = cached?.second ?: GoEnvironment(buildMap {
            System.getenv("GOBIN")?.let { put("GOBIN", it) }
            put("GOPATH", System.getenv("GOPATH") ?: File(System.getProperty("user.home"), "go").path)
        })

        /** Whether `go env` has been read: until then [quick] guesses GOPATH, and a tool installed elsewhere is not found. */
        fun isKnown(): Boolean = cached != null

        /**
         * Reads `go env` in the background, once, and calls [onReady] when it is there (at once when it is already). GOPATH is not
         * always `$HOME/go` and GOBIN is not always on PATH (seen live: a machine where `go env GOPATH` was under another home, so
         * gopls was "not installed" until something else had asked `go env`).
         */
        fun whenKnown(onReady: () -> Unit) {
            if (isKnown()) return onReady()
            ApplicationManager.getApplication().executeOnPooledThread {
                get()
                if (isKnown()) onReady()
            }
        }

        fun reset() {
            cached = null
        }
    }
}

/**
 * The tools of the Go ecosystem the plugin drives, each installed by `go install <module>@latest`. Where one is looked for: the path
 * from Settings | Tools | Go, then PATH, then GOBIN / GOPATH/bin (a directory installers do not put on PATH by themselves).
 */
enum class GoTool(val command: String, val module: String, val purpose: String, val documentation: String) {
    GOPLS("gopls", "golang.org/x/tools/gopls", "Language server: errors, completion, navigation, refactorings", "https://go.dev/gopls"),
    DELVE("dlv", "github.com/go-delve/delve/cmd/dlv", "Debugger behind the Debug button", "https://github.com/go-delve/delve"),
    GOLANGCI_LINT("golangci-lint", "github.com/golangci/golangci-lint/v2/cmd/golangci-lint", "Linter: warnings in the editor", "https://golangci-lint.run"),
    GOIMPORTS("goimports", "golang.org/x/tools/cmd/goimports", "Reformat Code that also fixes the imports", "https://pkg.go.dev/golang.org/x/tools/cmd/goimports"),
    GOVULNCHECK("govulncheck", "golang.org/x/vuln/cmd/govulncheck", "Vulnerabilities: known issues reachable from the code, in the Go Dependencies window", "https://go.dev/blog/vuln");

    /** The path set in the settings, when the file is there. */
    fun configured(): File? = GoSettings.getInstance().toolPath(command).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

    fun detect(): File? {
        val name = GoCli.executableName(command)
        return PathEnvironmentVariableUtil.findInPath(name) ?: GoEnvironment.quick().binDirectory?.let { File(it, name) }?.takeIf { it.isFile }
    }

    fun find(): File? = configured() ?: detect()

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
            .addAction(NotificationAction.createSimple("Configure...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, GoSettingsConfigurable::class.java) })
            .notify(project)
    }
}
