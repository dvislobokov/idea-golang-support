package io.github.golangsupport.build

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.HttpRequests
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.settings.GoSettings
import java.awt.datatransfer.StringSelection

/** The Go Playground: what is sent to it and the link of the snippet it answers with. Pure, so that it is tested without network. */
object GoPlayground {
    /** The endpoint GoLand and `goplay` post to: the body is the source, the answer is the id of the snippet. */
    const val SHARE_URL = "https://play.golang.org/share"
    const val SNIPPET_URL = "https://go.dev/play/p/"
    const val CONTENT_TYPE = "text/plain; charset=utf-8"

    /** The playground refuses bigger snippets (64 KiB, seen in its source: `maxSnippetSize`). */
    const val MAX_SIZE = 64 * 1024

    /** The selection when there is one, otherwise the whole file. */
    fun code(fileText: CharSequence, selection: String?): String = selection?.takeIf { it.isNotBlank() } ?: fileText.toString()

    fun requestBody(code: String): ByteArray = code.toByteArray(Charsets.UTF_8)

    /** Null when [code] cannot be shared, with the reason otherwise. */
    fun problem(code: String): String? = when {
        code.isBlank() -> "There is no code to share."
        requestBody(code).size > MAX_SIZE -> "The code is larger than the 64 KB the Go Playground accepts."
        else -> null
    }

    /** `go.dev/play/p/<id>` for the answer of [SHARE_URL]; null when the answer is not an id (an error page of a proxy, say). */
    fun snippetUrl(response: String): String? = response.trim().takeIf { ID.matches(it) }?.let { SNIPPET_URL + it }

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
}

/**
 * Share in Playground / Run in Playground: the selection or the file goes to play.golang.org; the link is copied and shown with Open.
 * Run opens the browser at the snippet as well. Behind a confirmation (Settings | Go | Editor and Completion, as GoLand's "Ask before
 * sharing in Go Playground"), the request in a background task: the network is never waited for on EDT.
 */
abstract class GoPlaygroundAction(private val openInBrowser: Boolean) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val enabled = e.project != null && file != null && !file.isDirectory && file.extension == "go"
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = enabled else e.presentation.isEnabled = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val text = editor?.document?.immutableCharSequence ?: FileDocumentManager.getInstance().getDocument(file)?.immutableCharSequence ?: return
        val code = GoPlayground.code(text, editor?.selectionModel?.selectedText)
        GoPlayground.problem(code)?.let { return Messages.showWarningDialog(project, it, TITLE) }
        if (!confirmed(project)) return
        share(project, code)
    }

    private fun confirmed(project: Project): Boolean {
        val settings = GoSettings.getInstance()
        if (!settings.askBeforePlayground) return true
        return MessageDialogBuilder.okCancel(TITLE, "The code will be sent to play.golang.org and become available to anyone with the link.")
            .yesText(if (openInBrowser) "Run" else "Share")
            .doNotAsk(object : DoNotAskOption.Adapter() {
                override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                    if (isSelected && exitCode == Messages.OK) settings.askBeforePlayground = false
                }
            })
            .ask(project)
    }

    private fun share(project: Project, code: String) {
        object : Task.Backgroundable(project, "Sharing in Go Playground", true) {
            private var url: String? = null

            override fun run(indicator: ProgressIndicator) {
                val response = HttpRequests.post(GoPlayground.SHARE_URL, GoPlayground.CONTENT_TYPE).userAgent("Go Project Support").connectTimeout(15_000).readTimeout(30_000)
                    .connect { request -> request.write(GoPlayground.requestBody(code)); request.readString(indicator) }
                url = GoPlayground.snippetUrl(response) ?: throw java.io.IOException("unexpected answer of play.golang.org: ${response.take(200)}")
            }

            override fun onSuccess() {
                val link = url ?: return
                CopyPasteManager.getInstance().setContents(StringSelection(link))
                GoPluginLog.info("tools", "shared in Go Playground: $link")
                NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
                    .createNotification(TITLE, "The link is copied: <a href=\"$link\">$link</a>", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple("Open") { BrowserUtil.browse(link) })
                    .notify(project)
                if (openInBrowser) BrowserUtil.browse(link)
            }

            override fun onThrowable(error: Throwable) {
                GoCli.notifyError(project, "$TITLE failed", GoPluginLog.describe(error))
            }
        }.queue()
    }

    companion object {
        const val TITLE = "Go Playground"
    }
}

class GoShareInPlaygroundAction : GoPlaygroundAction(openInBrowser = false)

class GoRunInPlaygroundAction : GoPlaygroundAction(openInBrowser = true)
