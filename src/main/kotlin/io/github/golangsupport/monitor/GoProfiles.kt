package io.github.golangsupport.monitor

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import io.github.golangsupport.cli.GoCli
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/** What `go test` can record about the tests without a line in their code; the flag writes the file, the tool reads it. */
enum class GoProfile(val title: String, val flag: String, val fileName: String, val tool: String) {
    NONE("None", "", "", ""),
    CPU("CPU profile", "-cpuprofile", "cpu.pprof", "pprof"),
    MEMORY("Memory profile", "-memprofile", "mem.pprof", "pprof"),
    BLOCK("Blocking profile", "-blockprofile", "block.pprof", "pprof"),
    MUTEX("Mutex contention profile", "-mutexprofile", "mutex.pprof", "pprof"),
    TRACE("Execution trace", "-trace", "trace.out", "trace");

    override fun toString(): String = title
}

object GoProfiles {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** A new directory for the files of one run: `go test` takes the profile paths as they are when they are absolute. */
    fun newDirectory(): File = Files.createTempDirectory("go-profile-${STAMP.format(LocalDateTime.now())}-").toFile()

    /** The flags of `go test` for [profile] into [directory]; empty for none. */
    fun arguments(profile: GoProfile, directory: File): List<String> =
        if (profile == GoProfile.NONE) emptyList() else listOf("${profile.flag}=${File(directory, profile.fileName).path}")

    /** What names a profile in a tab: the time of the run for a file of the plugin (`go-profile-20260930-114523-1234/cpu.pprof`), the file name otherwise. */
    fun stamp(file: File): String {
        val directory = file.parentFile?.name.orEmpty()
        val match = Regex("""^go-profile-\d{8}-(\d{2})(\d{2})(\d{2})-""").find(directory) ?: return file.name
        return match.groupValues.drop(1).joinToString(":")
    }

    /** `Serving web UI on http://localhost:53421`, the line of `go tool pprof -http`; `go tool trace` says `Parsing trace...` then the same words. */
    fun servedUrl(line: String): String? = Regex("""(?:Serving web UI on|listening on) (http://\S+)""").find(line)?.groupValues?.get(1)
}

/**
 * `go tool pprof -http` and `go tool trace` are web servers: one per opened profile, alive until the project closes. The browser is
 * opened with the address they print.
 */
@Service(Service.Level.PROJECT)
class GoProfileServers(private val project: Project) : Disposable {
    private val handlers = CopyOnWriteArrayList<OSProcessHandler>()

    /** The notification after a run: what was recorded, with the buttons to open it. */
    fun notifyReady(profile: GoProfile, directory: File) {
        val file = File(directory, profile.fileName)
        if (!file.isFile || file.length() == 0L) return GoCli.notifyError(project, profile.title, "The tests have written no ${file.name}: did they run? A profile needs a test binary that finished normally")
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Go").createNotification(
            "${profile.title} of the tests is ready", "${file.path} (${ChartFormats.bytes(file.length().toDouble())})", NotificationType.INFORMATION,
        )
        if (profile != GoProfile.TRACE && JBCefApp.isSupported()) notification.addAction(NotificationAction.createSimple("Open in IDE") { open(profile, file, inIde = true) })
        notification.addAction(NotificationAction.createSimple(if (profile == GoProfile.TRACE) "Open in go tool trace" else "Open in pprof") { open(profile, file, inIde = false) })
        notification.addAction(NotificationAction.createSimple("Show in ${RevealFileAction.getFileManagerName()}") { RevealFileAction.openFile(file) })
        notification.notify(project)
    }

    /**
     * The web UI of the tool for [file]: in a tab of the editor ([inIde], pprof only: `go tool trace` opens the browser by itself and
     * cannot be told not to), or in the browser. The server lives as long as the tab, or the project.
     */
    fun open(profile: GoProfile, file: File, inIde: Boolean, title: String = profile.title) {
        // pprof with `-http=localhost:0` says "Serving web UI on http://localhost:0" and serves on some port it never names (seen live): a port is picked here
        val port = java.net.ServerSocket(0).use { it.localPort }
        val arguments = if (profile == GoProfile.TRACE) listOf("tool", "trace", "-http=localhost:$port", file.path)
        else listOf("tool", "pprof", "-http=localhost:$port", "-no_browser", file.path)
        val commands = GoCli.commandLinesOrNotify(project, "Open ${profile.title}") { listOf(GoCli.commandLine(file.parent, *arguments.toTypedArray())) } ?: return
        val handler = OSProcessHandler(commands.first())
        handlers += handler
        handler.addProcessListener(object : ProcessListener {
            private var opened = false
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val url = GoProfiles.servedUrl(event.text) ?: return
                if (opened) return
                opened = true
                if (profile == GoProfile.TRACE) return
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    if (inIde && JBCefApp.isSupported()) {
                        // the first page of pprof is the graph, which needs graphviz and says so without it (seen live): the flame graph needs nothing
                        val editor = HTMLEditorProvider.openEditor(project, "$title: ${GoProfiles.stamp(file)}", HTMLEditorProvider.Request.url(url.trimEnd('/') + "/ui/flamegraph"))
                        // the tab is gone: the server behind it is not needed (null: no editor could be made, the browser then)
                        if (editor != null) Disposer.register(editor) { if (!handler.isProcessTerminated) handler.destroyProcess() } else BrowserUtil.browse(url)
                    } else BrowserUtil.browse(url)
                }, ModalityState.nonModal())
            }
            override fun processTerminated(event: ProcessEvent) {
                handlers -= handler
                if (!opened) GoCli.notifyError(project, "Open ${profile.title}", "go tool ${profile.tool} has exited with code ${event.exitCode}")
            }
        })
        handler.startNotify()
    }

    override fun dispose() = handlers.forEach { if (!it.isProcessTerminated) it.destroyProcess() }

    companion object {
        fun getInstance(project: Project): GoProfileServers = project.service()
    }
}

/** Go | Open Profile…: any pprof file (of a server, of CI, of a colleague) in a tab of the editor, or in the browser without JCEF. */
class GoOpenProfileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.singleFile().withTitle("Open Profile").withDescription("A profile written by runtime/pprof or go test: .pprof, .pb.gz, .prof")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        GoProfileServers.getInstance(project).open(GoProfile.CPU, File(file.path), inIde = true, title = "Profile")
    }
}
