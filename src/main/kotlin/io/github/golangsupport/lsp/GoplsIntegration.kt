package io.github.golangsupport.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.Key
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport
import com.intellij.platform.lsp.api.customization.LspCommandsCustomizer
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionCustomizer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile
import io.github.golangsupport.GoIcons
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.lang.GoSemanticColors
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.settings.GoLanguageServerControl
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.settings.GoSettingsConfigurable
import io.github.golangsupport.settings.GoplsCatalogue
import io.github.golangsupport.settings.GoplsDefaults
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressReport
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Starts gopls for the project when a Go file (or a go.mod / go.work, which gopls checks too) is opened. */
class GoplsIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (!isGoplsFile(file) || !GoSettings.getInstance().languageServerEnabled) return
        if (GoTool.GOPLS.find() == null) {
            // once per session: the notification has an Install button, and every opened file would stack another one
            if (OFFERED.compareAndSet(false, true)) GoTool.GOPLS.offerInstallation(project, "Go language server") { restart(project) }
            return
        }
        clientStarter.ensureClientStarted(GoplsDescriptor(project))
    }

    /** The item of the status bar widget: the icon of the plugin, its settings page, the log window among the actions. */
    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem = GoplsWidgetItem(lspClient, currentFile)

    companion object {
        private val OFFERED = AtomicBoolean()

        fun isGoplsFile(file: VirtualFile): Boolean = file.fileType == GoFileType || file.fileType == GoModFileType

        fun restart(project: Project) = LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(GoplsIntegrationProvider::class.java)
    }
}

class GoplsDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "gopls") {
    override fun isSupportedFile(file: VirtualFile): Boolean = GoplsIntegrationProvider.isGoplsFile(file)

    override fun createCommandLine(): GeneralCommandLine {
        val gopls = GoTool.GOPLS.find() ?: throw com.intellij.execution.ExecutionException("gopls is not found")
        return GoCli.toolCommandLine(gopls.path, project.guessProjectDir()?.path, *GoplsServerArguments.of(GoSettings.getInstance()).toTypedArray())
    }

    /** The process of the platform, with its stderr (the log of gopls, the RPC trace) going to the log window as well. */
    override fun startServerProcess(): BaseProcessHandler<*> {
        val log = GoplsLogService.getInstance(project)
        log.debugPagesUrl = null
        return super.startServerProcess().also { handler ->
            log.info("Starting: " + handler.commandLine)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (ProcessOutputType.isStderr(outputType)) log.server(event.text)
                }
                override fun processTerminated(event: ProcessEvent) = log.info("gopls has exited with code ${event.exitCode}")
            })
        }
    }

    private val failures = GoplsWorkspaceFailures(project)

    /** `window/logMessage` and `window/showMessage` of the server go to the log window too, on their way to the platform. */
    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        super.createLsp4jClient(LoggingNotificationsHandler(handler, GoplsLogService.getInstance(project), failures))

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            val info = params.serverInfo
            GoplsLogService.getInstance(project).info("Initialized: ${info?.name ?: "gopls"} ${GoplsLogLines.version(info?.version)}")
        }
        override fun serverStopped(shutdownNormally: Boolean) {
            failures.forget()
            val log = GoplsLogService.getInstance(project)
            if (shutdownNormally) log.info("Stopped") else log.error("Stopped unexpectedly: see the lines above. The platform restarts the server a few times, then gives up until Go | gopls | Restart")
        }
    }

    override fun getLanguageId(file: VirtualFile): String = when {
        file.name == GoModFileType.GO_WORK -> "go.work"
        file.fileType == GoModFileType -> "go.mod"
        else -> "go"
    }

    /** Go to Declaration is [GoplsGotoDeclarationHandler]: with both, every target would be offered twice. */
    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val goToDefinitionCustomizer: LspGoToDefinitionCustomizer get() = LspGoToDefinitionDisabled

        /**
         * The colours of the editor beyond what a lexer can tell: packages, references to types, fields, constants, parameters. The
         * platform asks for semantic tokens where a file has no highlighting of its own; a Go file has one, so it is said here.
         */
        override val semanticTokensCustomizer: LspSemanticTokensCustomizer = object : LspSemanticTokensSupport() {
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean = psiFile is GoFile
            override val tokenModifiers: List<String> get() = super.tokenModifiers + GoSemanticColors.MODIFIERS
            override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? = GoSemanticColors.key(tokenType, modifiers)
        }

        /** A clicked lens and the command of a code action: [GoplsCommands], not the fire-and-forget notification of the platform. */
        override val codeLensCustomizer: LspCodeLensCustomizer = object : LspCodeLensSupport() {
            override fun codeLensClicked(lspClient: LspClient, contextFile: VirtualFile, command: Command, mouseEvent: MouseEvent?) = GoplsCommands.execute(lspClient, contextFile, command)
        }
        override val commandsCustomizer: LspCommandsCustomizer = object : LspCommandsSupport() {
            override fun executeCommand(lspClient: LspClient, contextFile: VirtualFile, command: Command) = GoplsCommands.execute(lspClient, contextFile, command)
        }
    }

    override fun createInitializationOptions(): Any = GoplsOptions.build(GoSettings.getInstance())

    /** gopls asks for the section `gopls` after `workspace/didChangeConfiguration`. */
    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? = if (item.section == "gopls") GoplsOptions.build(GoSettings.getInstance()) else null
}

/** The arguments of `gopls serve`, from the settings; pure, for the tests. */
object GoplsServerArguments {
    fun of(settings: GoSettings): List<String> = buildList {
        add("serve")
        if (settings.goplsTrace) add("-rpc.trace")
        // a free port: the address is in the first lines of stderr, `debug server listening at http://localhost:NNNNN`
        if (settings.goplsDebugPages) add("-debug=localhost:0")
    }
}

/** The notifications of the server, with `window/logMessage` and `window/showMessage` copied to the log window. */
private class LoggingNotificationsHandler(
    private val delegate: LspServerNotificationsHandler, private val log: GoplsLogService, private val failures: GoplsWorkspaceFailures,
) : LspServerNotificationsHandler by delegate {
    override fun notifyProgress(params: ProgressParams) {
        if (!failures.taken(params)) delegate.notifyProgress(params)
    }

    override fun logMessage(params: MessageParams) {
        write(params)
        // the platform shows a log message of the Error kind as a balloon (seen live): the ones about a half-typed go.mod stay in the log,
        // and so do the ones about a workspace that has not loaded, which is told once by GoplsWorkspaceFailures
        if (!GoplsLogLines.isTypingNoise(params.message) && !GoplsLogLines.isLoadFailureEcho(params.message)) delegate.logMessage(params)
    }

    override fun showMessage(params: MessageParams) {
        write(params, "showMessage: ")
        // a go.mod that is being typed fails every code lens of gopls, one balloon each (seen live): the log keeps them, the user is not told five times
        if (!GoplsLogLines.isTypingNoise(params.message)) delegate.showMessage(params)
    }

    private fun write(params: MessageParams, prefix: String = "") {
        val text = prefix + GoplsLogLines.withoutStamp(params.message.trimEnd())
        if (params.type == MessageType.Error || params.type == MessageType.Warning) log.error(text) else log.info(text)
    }
}

/**
 * A workspace that has not loaded is a progress that does not end: `Error loading workspace` is its title, the error is its message, and
 * the end comes when the workspace loads. The platform shows it as a process that runs forever, with the error cut to a line (seen live:
 * `go: inconsistent ven...`). Here it is an error in the log and a balloon with the whole text, which goes away when the server ends it.
 */
private class GoplsWorkspaceFailures(private val project: Project) {
    private class Shown(val text: String, val notification: Notification)

    private val shown = ConcurrentHashMap<String, Shown>()

    /** True for a progress of a failed load: the platform is not told about it. */
    fun taken(params: ProgressParams): Boolean {
        val token = params.token?.let { if (it.isLeft) it.left else it.right?.toString() } ?: return false
        when (val progress = params.value?.takeIf { it.isLeft }?.left) {
            is WorkDoneProgressBegin -> if (GoplsLogLines.isLoadFailure(progress.title)) show(token, progress.message.orEmpty()) else return false
            // gopls reports when the error has changed
            is WorkDoneProgressReport -> if (shown.containsKey(token)) progress.message?.let { show(token, it) } else return false
            is WorkDoneProgressEnd -> {
                (shown.remove(token) ?: return false).notification.expire()
                GoplsLogService.getInstance(project).info("${GoplsLogLines.LOAD_FAILURE}: over" + progress.message?.let { ", $it" }.orEmpty())
            }
            else -> return false
        }
        return true
    }

    private fun show(token: String, message: String) {
        val text = GoplsLogLines.loadFailure(message)
        if (shown[token]?.text == text) return
        GoplsLogService.getInstance(project).error("${GoplsLogLines.LOAD_FAILURE}: $message")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification("gopls: ${GoplsLogLines.LOAD_FAILURE}", GoplsLogLines.html(text), NotificationType.ERROR)
            .addAction(NotificationAction.createSimple("Show Log") { ShowGoplsLogAction.show(project) })
            .addAction(NotificationAction.createSimple("Restart gopls") { GoplsIntegrationProvider.restart(project) })
        shown.put(token, Shown(text, notification))?.notification?.expire()
        notification.notify(project)
    }

    /** The server has stopped: what it has said is not true any more. */
    fun forget() {
        shown.values.forEach { it.notification.expire() }
        shown.clear()
    }
}

/** The line of gopls in the status bar widget, with the settings of the plugin behind its gear and the log window next to Restart. */
private class GoplsWidgetItem(client: LspClient, file: VirtualFile?) : LspClientWidgetItem(client, file, GoIcons.Gopls, GoSettingsConfigurable::class.java) {
    override fun createAdditionalInlineActions(): List<AnAction> = listOf(ActionManager.getInstance().getAction("Go.Gopls.ShowLog"))
}

/** The settings of gopls (https://go.dev/gopls/settings) the page Settings | Tools | Go has switches for. */
object GoplsOptions {
    /** What the plugin sets by itself ([GoplsDefaults]), with what the user has set on the page of gopls settings on top of it. */
    fun build(settings: GoSettings): Map<String, Any> = GoplsCatalogue.merge(GoplsDefaults.of(settings), settings.goplsOverrides)
}

class GoplsControl : GoLanguageServerControl {
    override fun restart(project: Project) = GoplsIntegrationProvider.restart(project)
}

class RestartGoplsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(GoplsIntegrationProvider::restart)
    }
}
