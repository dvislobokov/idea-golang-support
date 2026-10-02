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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Key
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport
import com.intellij.platform.lsp.api.customization.LspCommandsCustomizer
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import com.intellij.platform.lsp.api.customization.LspCompletionCustomizer
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer
import com.intellij.platform.lsp.api.customization.LspDiagnosticsDisabled
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.codeInsight.intention.IntentionAction
import org.eclipse.lsp4j.Diagnostic
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport
import com.intellij.platform.lsp.api.customization.LspFoldingRangeCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspFoldingRangeDisabled
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionCustomizer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspHoverCustomizer
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspHoverSupport
import com.intellij.platform.lsp.api.customization.LspRenameCustomizer
import com.intellij.platform.lsp.api.customization.LspRenameSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSignatureHelpCustomizer
import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled
import com.intellij.platform.lsp.api.customization.LspSignatureHelpSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoSemanticColors
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.settings.GoLanguageServerControl
import io.github.golangsupport.settings.GoSettings
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
        if (!isGoplsFile(file)) return
        if (!GoSettings.getInstance().languageServerEnabled) {
            GoPluginLog.info(GoplsLogService.CATEGORY, "gopls is not started for ${file.name}: the language server is off in Settings | Tools | Go")
            return
        }
        if (GoTool.GOPLS.find() == null) {
            // gopls is looked for on PATH and in GOBIN / GOPATH/bin, and GOPATH is a guess until `go env` has been read: asking for
            // it here and starting again is what makes the first Go file of a session work (seen live: gopls started only when the
            // user opened go.mod a minute later, by which time something else had read the environment).
            if (!GoEnvironment.isKnown()) {
                GoPluginLog.info(GoplsLogService.CATEGORY, "gopls is not found yet for ${file.name}: reading `go env` first, the client starts when GOPATH is known")
                GoEnvironment.whenKnown {
                    ApplicationManager.getApplication().invokeLater({
                        if (project.isDisposed) return@invokeLater
                        if (GoTool.GOPLS.find() == null) return@invokeLater GoPluginLog.info(GoplsLogService.CATEGORY, "gopls is still not found after `go env`; the plugin will offer to install it")
                        GoPluginLog.info(GoplsLogService.CATEGORY, "gopls found after `go env`: starting the client for the files that are open")
                        LspClientManager.getInstance(project).startClientsIfNeeded(GoplsIntegrationProvider::class.java)
                    }, project.disposed)
                }
                return
            }
            // once per session: the notification has an Install button, and every opened file would stack another one
            if (OFFERED.compareAndSet(false, true)) GoTool.GOPLS.offerInstallation(project, "Go language server") { restart(project) }
            return
        }
        GoPluginLog.info(GoplsLogService.CATEGORY, "gopls is asked to serve ${file.name}")
        clientStarter.ensureClientStarted(GoplsDescriptor(project))
    }

    /**
     * No row in the widget of language services: the server has a widget of its own in the status bar ([GoplsStatusWidget]), and the
     * row of the platform names gopls by its `serverInfo.version`, a page of JSON (seen live).
     */
    override fun createWidgetItems(project: Project, currentFile: VirtualFile?): List<LanguageServiceWidgetItem> = emptyList()

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem? = null

    companion object {
        private val OFFERED = AtomicBoolean()

        fun isGoplsFile(file: VirtualFile): Boolean = file.fileType == GoFileType || file.fileType == GoModFileType

        /** A running server is started anew; when there is none (gopls was just installed, or was not found at the first file), one is started for the open files. */
        fun restart(project: Project) {
            val manager = LspClientManager.getInstance(project)
            manager.stopAndRestartClientsIfNeeded(GoplsIntegrationProvider::class.java)
            manager.startClientsIfNeeded(GoplsIntegrationProvider::class.java)
        }
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
            val state = GoplsServerState.getInstance(project)
            val process = handler.process.toHandle()
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (ProcessOutputType.isStderr(outputType)) log.server(event.text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    log.info("gopls has exited with code ${event.exitCode}")
                    // the project may be closing: its services are gone then, and so is its status bar
                    if (!project.isDisposed) state.stopped(process)
                }
            })
            state.started(process)
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
            GoplsServerState.getInstance(project).buildInfo = info?.version
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

    /**
     * Go to Declaration is [GoplsGotoDeclarationHandler]: with both, every target would be offered twice. The switches of [GoFeatures]
     * are read once, here, as settings only (a descriptor is often built while the IDE indexes; dumb mode is for the handlers): every
     * settings page restarts the server on Apply ([GoLanguageServerControl.restartAll]), and the restart builds a new descriptor.
     */
    override val lspCustomization: LspCustomization = object : LspCustomization() {
        private fun native(feature: GoFeature) = GoFeatures.configuredNative(feature)

        override val goToDefinitionCustomizer: LspGoToDefinitionCustomizer get() = LspGoToDefinitionDisabled

        /**
         * Folding is [io.github.golangsupport.ide.folding.GoFoldingBuilder]: bodies, blocks inside them, groups, comments. The regions of gopls
         * would come on top of the same ranges (seen live: every body twice, `{...}` and `...`), plus ones of a single line for parameters.
         */
        override val foldingRangeCustomizer: LspFoldingRangeCustomizer get() = LspFoldingRangeDisabled

        /**
         * The colours of the editor beyond what a lexer can tell: packages, references to types, fields, constants, parameters. The
         * platform asks for semantic tokens where a file has no highlighting of its own; a Go file has one, so it is said here.
         * Follows [GoFeature.SEMANTIC_COLORS] per file, not per descriptor: while the IDE indexes the native annotator is blind and gopls
         * colours; in smart mode with the Built-in source the semantic annotator of go-psi-ide colours by resolve (MIGRATION.md step 8d).
         */
        override val semanticTokensCustomizer: LspSemanticTokensCustomizer = object : LspSemanticTokensSupport() {
            // gopls refuses the request for a file above 100 000 bytes ("semantic tokens: range ... too large", seen live on a 140 KB
            // net/http/server.go); the platform reports every refusal as an unhandled exception, so such a file keeps the lexer colours
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean =
                psiFile is GoFile && psiFile.textLength <= GOPLS_SEMANTIC_TOKENS_MAX_BYTES && !GoFeatures.native(GoFeature.SEMANTIC_COLORS, psiFile.project)
            override val tokenModifiers: List<String> get() = super.tokenModifiers + GoSemanticColors.MODIFIERS
            override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? = GoSemanticColors.key(tokenType, modifiers)
        }

        /**
         * Follows [GoFeature.COMPLETION] per request, not per descriptor ([GoplsCompletionSupport.shouldRunCodeCompletion]): with the
         * PSI as the source gopls still answers while the IDE indexes, when the native contributor is blind.
         */
        override val completionCustomizer: LspCompletionCustomizer = GoplsCompletionSupport()

        /** Follows [GoFeature.HOVER]. */
        override val hoverCustomizer: LspHoverCustomizer = if (native(GoFeature.HOVER)) LspHoverDisabled else LspHoverSupport()

        /**
         * Parameter info (Ctrl+P): the platform's `LspParameterInfoHandler` is registered for every language and asks this customizer
         * before `textDocument/signatureHelp`. Follows [GoFeature.HOVER] with the native handler of go-psi-ide, so one of the two answers.
         */
        override val signatureHelpCustomizer: LspSignatureHelpCustomizer = if (native(GoFeature.HOVER)) LspSignatureHelpDisabled else LspSignatureHelpSupport()

        /**
         * Follows [GoFeature.RENAME] per request, not per descriptor ([LspRenameSupport.shouldRunRename], asked by the platform's
         * `LspRenameHandler` before it offers itself): with the PSI as the source gopls still renames while the IDE indexes, when the
         * native rename (the platform's refactoring over the references of go-psi) is blind. The registry of rename handlers takes the
         * handlers that answer and the default PSI handler only when none does, so with gopls on duty it renames alone (MIGRATION.md step 8h).
         */
        override val renameCustomizer: LspRenameCustomizer = object : LspRenameSupport() {
            override fun shouldRunRename(psiFile: PsiFile): Boolean = psiFile is GoFile && !GoFeatures.native(GoFeature.RENAME, psiFile.project)
        }

        /**
         * Follows [GoFeature.FORMATTING]: the plugin formats with a tool or the Built-in formatter of go-psi-ide (MIGRATION.md step 8j),
         * and only with the formatter set to None does gopls. Said explicitly for Go files: since step 8j a Go file has a `lang.formatter`,
         * and the platform's default then leaves the file to the IDE.
         */
        override val formattingCustomizer: LspFormattingCustomizer = if (native(GoFeature.FORMATTING)) LspFormattingDisabled else object : LspFormattingSupport() {
            override fun shouldFormatThisFileExclusivelyByServer(file: VirtualFile, ideCanFormatThisFileItself: Boolean, serverExplicitlyEnabled: Boolean): Boolean = file.fileType == GoFileType
        }

        /** Follows [GoFeature.DIAGNOSTICS]. */
        override val diagnosticsCustomizer: LspDiagnosticsCustomizer = if (native(GoFeature.DIAGNOSTICS)) LspDiagnosticsDisabled else GoplsDiagnosticsSupport()

        /**
         * The usages of the name at the caret, reads and writes apart, and the exit points of a function on `func` or `return`: the
         * platform asks a server for them only where a file has no language of its own (TextMate), so a Go file is named here.
         * Follows [GoFeature.USAGES].
         */
        override val documentHighlightsCustomizer: LspDocumentHighlightsCustomizer = if (native(GoFeature.USAGES)) LspDocumentHighlightsDisabled else object : LspDocumentHighlightsSupport() {
            override fun shouldAskServerForDocumentHighlights(psiFile: PsiFile): Boolean = psiFile is GoFile && GoSettings.getInstance().goplsHighlightUsages
        }

        /**
         * The fixes of the errors of gopls are the ones of the platform; its actions for a place are [GoplsIntention]: the platform asks
         * for them in a way gopls answers with nothing, every time the caret moves.
         */
        override val codeActionsCustomizer: LspCodeActionsCustomizer = object : LspCodeActionsSupport() {
            override val intentionActionsSupport: Boolean get() = false
        }

        /** A clicked lens and the command of a code action: [GoplsCommands], not the fire-and-forget notification of the platform. Follows [GoFeature.CODE_VISION]. */
        override val codeLensCustomizer: LspCodeLensCustomizer = if (native(GoFeature.CODE_VISION)) LspCodeLensDisabled else object : LspCodeLensSupport() {
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

/** `maxFullFileSize` of gopls (golang.org/x/tools/gopls/internal/golang/semtok.go): a bigger file gets an error instead of tokens. */
const val GOPLS_SEMANTIC_TOKENS_MAX_BYTES = 100_000

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
        // the last progress of the server comes after the project is closed (seen live): its services are gone by then
        if (project.isDisposed) return true
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

/**
 * The diagnostics of gopls as annotations, minus the ones that no longer fit the file: a diagnostic published for a longer version
 * of a big file (format on save shrank it) arrives with a range past the end, and the platform threw "Range must be inside element
 * being annotated" instead of dropping it (seen live on a 135 KB file). The next publish replaces them anyway.
 * Minus, too, the syntax errors when the plugin's parser shows its own ([GoFeature.SYNTAX_ERRORS], MIGRATION.md step 8a) and the type errors
 * (`source == "compiler"`) when the native inspections report them ([GoFeature.DIAGNOSTICS], step 8g): one underline per error, not two.
 * The analyzers of gopls stay in every mode: the PSI has none yet.
 */
class GoplsDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun createAnnotation(holder: AnnotationHolder, diagnostic: Diagnostic, textRange: TextRange, quickFixes: List<IntentionAction>) {
        val file = holder.currentAnnotationSession.file
        if (!fits(textRange, file.textLength)) return
        val project = file.project
        if (!accepts(diagnostic.source, GoFeatures.native(GoFeature.SYNTAX_ERRORS, project), GoFeatures.native(GoFeature.DIAGNOSTICS, project))) return
        // `Fill in return values` and the like: the native intentions offer them while the switch Language features says Built-in (step 9)
        val fixes = if (GoFeatures.native(GoFeature.CODE_ACTIONS, project)) quickFixes.filterNot { GoplsActionKinds.isNativeCodeAction(null, it.text) } else quickFixes
        super.createAnnotation(holder, diagnostic, textRange, fixes)
    }

    companion object {
        /** Whether a diagnostic at [range] can be annotated in a file of [textLength] characters. */
        fun fits(range: TextRange, textLength: Int): Boolean = range.startOffset >= 0 && range.endOffset <= textLength

        /**
         * Whether a diagnostic of [source] is shown: the syntax errors of gopls (`source == "syntax"`) are not when the plugin's parser shows its own
         * ([nativeSyntaxErrors]), its type-checker errors (`"compiler"`) are not when the native inspections report them ([nativeDiagnostics]).
         * Everything else stays whatever the switches say: the analyzers (`printf`, `unusedparams`, staticcheck's `SA…`/`ST…`, …) have no
         * native counterpart yet, `go list` / `go mod tidy` speak for the module, and a source-less diagnostic is nobody's to drop.
         */
        fun accepts(source: String?, nativeSyntaxErrors: Boolean, nativeDiagnostics: Boolean): Boolean = when (source) {
            "syntax" -> !nativeSyntaxErrors
            "compiler" -> !nativeDiagnostics
            else -> true
        }
    }
}
