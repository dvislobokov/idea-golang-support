package io.github.golangsupport.monitor

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
        // `-o`: with a profile go test keeps the test binary, by default as `pkg.test` in the directory of the package (seen live)
        if (profile == GoProfile.NONE) emptyList()
        else listOf("${profile.flag}=${File(directory, profile.fileName).path}", "-o", File(directory, "pkg.test" + if (com.intellij.openapi.util.SystemInfo.isWindows) ".exe" else "").path)

    /** `Serving web UI on http://localhost:53421`, the line of `go tool pprof -http`; `go tool trace` says `Parsing trace...` then the same words. */
    fun servedUrl(line: String): String? = Regex("""(?:Serving web UI on|listening on) (http://\S+)""").find(line)?.groupValues?.get(1)
}

/**
 * The profiles the IDE has: pprof ones in tabs of Go Monitor; in the browser, `go tool pprof -http` and `go tool trace` are web servers,
 * one per opened profile, alive until the project closes.
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
        notification.addAction(NotificationAction.createSimple(if (profile == GoProfile.TRACE) "Open in go tool trace" else "Open") { open(file, profile.title, profile == GoProfile.TRACE, "the tests") })
        notification.addAction(NotificationAction.createSimple("Show in ${RevealFileAction.getFileManagerName()}") { RevealFileAction.openFile(file) })
        notification.notify(project)
    }

    /** A pprof profile opens in a tab of Go Monitor; an execution trace is not pprof and only `go tool trace` shows it, in the browser. */
    fun open(file: File, title: String, trace: Boolean, source: String) =
        if (trace) openInBrowser(file, title, trace = true) else GoProfileLoader.open(project, file, title, "$title of $source")

    /**
     * [file] in `go tool trace` when [trace], in `go tool pprof -http` otherwise; the browser opens when the tool says where it serves.
     * pprof starts on its flame graph: its default view, Graph, needs Graphviz (`Could not execute dot`, seen live).
     */
    fun openInBrowser(file: File, title: String, trace: Boolean) {
        // pprof with `-http=localhost:0` says "Serving web UI on http://localhost:0" and serves on some port it never names (seen live): a port is picked here
        val port = java.net.ServerSocket(0).use { it.localPort }
        val arguments = if (trace) listOf("tool", "trace", "-http=localhost:$port", file.path)
        else listOf("tool", "pprof", "-http=localhost:$port", "-no_browser", file.path)
        val commands = GoCli.commandLinesOrNotify(project, "Open $title") { listOf(GoCli.commandLine(file.parent, *arguments.toTypedArray())) } ?: return
        val handler = OSProcessHandler(commands.first())
        handlers += handler
        handler.addProcessListener(object : ProcessListener {
            private var opened = false
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val url = GoProfiles.servedUrl(event.text) ?: return
                if (opened) return
                opened = true
                // `go tool trace` opens the browser by itself; pprof is told not to
                if (!trace) ApplicationManager.getApplication().invokeLater { BrowserUtil.browse(url.trimEnd('/') + "/ui/flamegraph") }
            }
            override fun processTerminated(event: ProcessEvent) {
                handlers -= handler
                if (!opened) GoCli.notifyError(project, "Open $title", "go tool ${if (trace) "trace" else "pprof"} has exited with code ${event.exitCode}")
            }
        })
        handler.startNotify()
    }

    override fun dispose() = handlers.forEach { if (!it.isProcessTerminated) it.destroyProcess() }

    companion object {
        fun getInstance(project: Project): GoProfileServers = project.service()
    }
}
