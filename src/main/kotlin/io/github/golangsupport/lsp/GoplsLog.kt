package io.github.golangsupport.lsp

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.settings.GoLanguageServerConfigurable
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * The log window of gopls (tool window `gopls`): what the server writes to stderr (its own log, and the whole protocol with `-rpc.trace`),
 * its `window/logMessage` and `window/showMessage`, and what the plugin does with it: starts, stops, the commands it sends. The console
 * is made on EDT when the window is first shown; what comes before is kept, so the start of the server is there too.
 */
@Service(Service.Level.PROJECT)
class GoplsLogService(private val project: Project) : Disposable {
    private val pending = StringBuilder()
    @Volatile private var console: ConsoleView? = null

    /** `debug server listening at http://localhost:61674`, from stderr when the debug pages are on. */
    @Volatile var debugPagesUrl: String? = null

    /** What the plugin does with the server: in this window, and in the journal of the plugin (the server's own lines stay here). */
    fun info(text: String) {
        GoPluginLog.info(CATEGORY, text)
        append("[${TIME.format(LocalTime.now())}] $text\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    fun error(text: String) {
        GoPluginLog.error(CATEGORY, text)
        append("[${TIME.format(LocalTime.now())}] $text\n", ConsoleViewContentType.ERROR_OUTPUT)
    }

    /** A line of the server itself; a line about the debug pages is remembered for the action that opens them. */
    fun server(text: String) {
        GoplsLogLines.debugUrl(text)?.let { debugPagesUrl = it }
        append(text, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    @Synchronized
    private fun append(text: String, type: ConsoleViewContentType) {
        val view = console
        if (view != null) view.print(text, type) else pending.append(text)
    }

    /** The console, made for the tool window. Its content before this point is printed as plain text: the kinds are not kept. */
    @Synchronized
    fun console(): ConsoleView = console ?: TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also {
        Disposer.register(this, it)
        it.print(pending.toString(), ConsoleViewContentType.NORMAL_OUTPUT)
        pending.setLength(0)
        console = it
    }

    override fun dispose() {}

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** The category of the journal for the language server. */
        const val CATEGORY = "gopls"

        fun getInstance(project: Project): GoplsLogService = project.service()
    }
}

/** What is read out of the lines of gopls; pure, for the tests. */
object GoplsLogLines {
    private val DEBUG = Regex("""debug server listening at (http://\S+)""")
    private val STAMP = Regex("""^\d{4}/\d\d/\d\d \d\d:\d\d:\d\d """)

    fun debugUrl(line: String): String? = DEBUG.find(line)?.groupValues?.get(1)

    /** The messages of gopls start with its own date and time; the log has a time of its own. */
    fun withoutStamp(message: String): String = STAMP.replace(message, "")

    private val TYPING_NOISE = Regex("""go\.(mod|work):\d+: |code lens \S+ failed|failed to compute document links""")

    /** What gopls says while a go.mod is half typed: every code lens fails with the parse error, one message each; nothing the user can act on. */
    fun isTypingNoise(message: String): Boolean = TYPING_NOISE.containsMatchIn(message)

    /** The title of the progress gopls keeps while the workspace is not loaded. */
    const val LOAD_FAILURE = "Error loading workspace"

    fun isLoadFailure(title: String?): Boolean = title?.trim() == LOAD_FAILURE

    private val LOAD_ECHO = Regex("""packages\.Load\b.*\berr|workspace load failed|errors loading workspace""")

    /** The same failure as log messages, one for every load and every file that has asked (seen live: nine for one broken vendor directory). */
    fun isLoadFailureEcho(message: String): Boolean = LOAD_ECHO.containsMatchIn(message)

    private val LOAD_PREFIX =Regex("""^.*?\bstderr: """, RegexOption.DOT_MATCHES_ALL)

    /** What `go` has said: gopls puts `packages.Load error: err: exit status 1: stderr: ` before it; a message without it stays as it is. */
    fun loadFailure(message: String): String = LOAD_PREFIX.replaceFirst(message, "").trim().ifEmpty { message.trim() }

    /** For a balloon: the lines and the indents of the text are kept. */
    fun html(text: String): String =
        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text).replace("\r", "").replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;").replace("\n", "<br>")

    /** The version in `serverInfo` of gopls is its whole build info as JSON (seen live): `v0.23.0` is `Main.Version` in it. */
    fun version(serverInfoVersion: String?): String {
        val raw = serverInfoVersion.orEmpty()
        if (!raw.startsWith("{")) return raw
        val main = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject.getAsJsonObject("Main") }.getOrNull() ?: return raw
        return main.get("Version")?.takeIf { it.isJsonPrimitive }?.asString ?: raw
    }

    /** `go1.26.8` of the same build info: what gopls was built with; null when the version is a plain one. */
    fun goVersion(serverInfoVersion: String?): String? {
        val raw = serverInfoVersion.orEmpty()
        if (!raw.startsWith("{")) return null
        return runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject.get("GoVersion") }.getOrNull()?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
    }
}

class GoplsLogToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = GoplsLogService.getInstance(project).console()
        val actions = DefaultActionGroup().apply {
            add(ActionManager.getInstance().getAction("Go.RestartLanguageServer"))
            add(ActionManager.getInstance().getAction("Go.Gopls.DebugPages"))
            add(ActionManager.getInstance().getAction("Go.Gopls.Settings"))
            addSeparator()
            addAll(*console.createConsoleActions())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("GoplsLog", actions, false)
        toolbar.targetComponent = console.component
        val panel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(console.component, BorderLayout.CENTER)
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    // later changes: GoProjectPresence.Ui switches the window by its id
    // gopls is off by default: no stripe button for its log then (GoProjectPresence.refreshUi follows the setting)
    override fun shouldBeAvailable(project: Project): Boolean = GoProjectPresence.hasGoFiles(project) && io.github.golangsupport.settings.GoSettings.getInstance().languageServerEnabled
}

class ShowGoplsLogAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        show(e.project ?: return)
    }

    companion object {
        const val TOOL_WINDOW_ID = "gopls"
        fun show(project: Project) {
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        }
    }
}

/** The web pages of gopls: on when the setting is, the address is in its log. */
class OpenGoplsDebugPagesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val url = GoplsLogService.getInstance(project).debugPagesUrl
        if (url == null) GoCli.notifyInfo(project, "gopls", "The debug pages are switched on in Settings | Go | Language Server; the server is restarted with them")
        else BrowserUtil.browse(url)
    }
}

class GoplsSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().invokeLater { ShowSettingsUtil.getInstance().showSettingsDialog(e.project, GoLanguageServerConfigurable::class.java) }
    }
}
