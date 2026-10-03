package io.github.golangsupport.lsp

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import io.github.golangsupport.GoIcons
import io.github.golangsupport.monitor.ProcessSampler
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The running gopls of a project: its process, what it has said of itself and what it costs. The cost is measured every few seconds
 * for as long as the process lives, off the UI thread: the status bar shows it without a click.
 */
@Service(Service.Level.PROJECT)
class GoplsServerState(private val project: Project) : Disposable {
    @Volatile var process: ProcessHandle? = null
        private set

    /** `serverInfo.version` of gopls: its whole build info as JSON (seen live). */
    @Volatile var buildInfo: String? = null

    @Volatile var sample: GoplsUsage.Sample? = null
        private set

    private val usage = GoplsUsage()
    private var measuring: ScheduledFuture<*>? = null

    val isRunning: Boolean get() = process?.isAlive == true

    @Synchronized
    fun started(handle: ProcessHandle) {
        process = handle
        buildInfo = null
        sample = null
        measuring?.cancel(false)
        measuring = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ measure() }, 0, PERIOD_MS, TimeUnit.MILLISECONDS)
        GoplsStatusWidgetFactory.refresh(project)
    }

    /** On a restart the new server may be there before the old one is gone: only the end of the current process counts. */
    @Synchronized
    fun stopped(handle: ProcessHandle) {
        if (process != handle) return
        measuring?.cancel(false)
        measuring = null
        process = null
        sample = null
        GoplsStatusWidgetFactory.refresh(project)
    }

    private fun measure() {
        // an exception would end the schedule
        sample = runCatching { usage.sample(process) }.getOrNull()
        GoplsStatusWidgetFactory.repaint(project)
    }

    override fun dispose() {
        measuring?.cancel(false)
    }

    companion object {
        const val PERIOD_MS = 3_000L

        fun getInstance(project: Project): GoplsServerState = project.service()
    }
}

/**
 * gopls in the status bar for as long as its process lives, with its memory and CPU next to the icon, as the sibling plugin shows its
 * C# server. The row of the platform in the widget of language services is switched off ([GoplsIntegrationProvider]): it names the
 * server by `serverInfo.version`, which for gopls is a page of JSON (seen live), and is there only next to a Go file.
 */
class GoplsStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Go Language Server (gopls)"
    override fun isAvailable(project: Project): Boolean = GoProjectPresence.hasGoFiles(project) && GoplsServerState.getInstance(project).isRunning
    override fun createWidget(project: Project): StatusBarWidget = GoplsStatusWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "Go.Gopls.Status"

        /** The server has started or stopped: the widget comes or goes. */
        fun refresh(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                project.service<StatusBarWidgetsManager>().updateWidget(GoplsStatusWidgetFactory::class.java)
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
            }, ModalityState.any())
        }

        /** New numbers: the text of the widget changes. */
        fun repaint(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
            }, ModalityState.any())
        }
    }
}

class GoplsStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    private val state get() = GoplsServerState.getInstance(project)

    override fun ID(): String = GoplsStatusWidgetFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() = Unit

    override fun getIcon(): Icon = GoIcons.Gopls
    override fun getSelectedValue(): String = GoplsStatusText.widget(state.sample)
    override fun getTooltipText(): String = GoplsStatusText.tooltip(state.buildInfo, state.sample)
    override fun getPopup(): JBPopup = GoplsStatusPopup(project).create()
}

/** What a click on the widget shows: the server in words, its numbers (updated while the popup is open) and what can be done with it. */
class GoplsStatusPopup(private val project: Project) {
    private val state get() = GoplsServerState.getInstance(project)
    private val cpu = JBLabel(GoplsStatusText.MEASURING)
    private val memory = JBLabel(GoplsStatusText.MEASURING)
    private val process = JBLabel()

    fun create(): JBPopup {
        var popup: JBPopup? = null
        fun action(id: String) {
            popup?.cancel()
            val action = ActionManager.getInstance().getAction(id) ?: return
            ActionUtil.invokeAction(action, SimpleDataContext.getProjectContext(project), ActionPlaces.STATUS_BAR_PLACE, null, null)
        }
        val content: JComponent = panel {
            row { label(GoplsStatusText.title(state.buildInfo)).bold() }
            GoplsStatusText.builtWith(state.buildInfo)?.let { row("Built with:") { label(it) } }
            row("CPU:") { cell(cpu) }
            row("Memory:") { cell(memory) }
            row("Process:") { cell(process) }
            separator()
            row {
                link("Restart") { action("Go.RestartLanguageServer") }
                link("Log") { action("Go.Gopls.ShowLog") }
                link("Statistics") { action("Go.Gopls.Statistics") }
                link("Settings...") { action("Go.Gopls.Settings") }
            }
        }.apply { border = JBUI.Borders.empty(10, 14) }

        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(true).setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true).createPopup()
        popup = created
        refresh()
        val timer = Timer(REFRESH_MS) { refresh() }.apply { start() }
        Disposer.register(created) { timer.stop() }
        return created
    }

    /** The numbers are the ones the state has measured for the status bar: the popup reads, it does not measure. */
    private fun refresh() {
        val sample = state.sample
        cpu.text = if (sample == null) GoplsStatusText.STOPPED.takeIf { !state.isRunning } ?: GoplsStatusText.MEASURING else GoplsStatusText.cpu(sample.cpuPercent)
        memory.text = sample?.let { GoplsStatusText.memoryWithCommitted(it.memoryBytes, it.committedBytes) } ?: if (state.isRunning) GoplsStatusText.MEASURING else GoplsStatusText.STOPPED
        process.text = sample?.let { GoplsStatusText.process(it.pid, it.processes, it.uptime) } ?: if (state.isRunning) GoplsStatusText.MEASURING else GoplsStatusText.STOPPED
    }

    private companion object {
        const val REFRESH_MS = 1_000
    }
}

/** The CPU and the memory of the server and of what it has started (`go list`, `go mod`): the tree of its process. */
class GoplsUsage {
    class Sample(
        val pid: Long, val processes: Int, val memoryBytes: Long, val committedBytes: Long,
        /** Null for the first sample: a share needs two. */
        val cpuPercent: Double?, val uptime: Duration?,
    )

    private var previousCpu: Duration? = null
    private var previousAt = 0L
    private var previousPid = -1L

    @Synchronized
    fun sample(root: ProcessHandle?): Sample? {
        if (root == null || !root.isAlive) return null
        val tree = ProcessSampler.tree(root.pid())
        if (tree.isEmpty()) return null
        val now = System.nanoTime()
        val usage = ProcessSampler.sample(tree)
        val before = previousCpu.takeIf { previousPid == root.pid() }
        val percent = before?.let { ProcessSampler.cpuPercent(it, usage.cpuTime, now - previousAt) }
        previousCpu = usage.cpuTime
        previousAt = now
        previousPid = root.pid()
        val started = root.info().startInstant().orElse(null)
        return Sample(root.pid(), usage.processes, usage.workingSetBytes, usage.privateBytes, percent, started?.let { Duration.between(it, Instant.now()) })
    }
}

/** The words of the widget and of its popup; pure, for the tests. */
object GoplsStatusText {
    const val MEASURING = "measuring..."
    const val STOPPED = "not running"

    /** Next to the icon in the status bar: `312 MB · 2%`; whole percents, so that the width does not change every few seconds. */
    fun widget(sample: GoplsUsage.Sample?): String {
        if (sample == null) return "gopls"
        val percent = sample.cpuPercent ?: return memory(sample.memoryBytes)
        return memory(sample.memoryBytes) + " · " + String.format(Locale.ROOT, "%.0f%%", percent)
    }

    fun tooltip(buildInfo: String?, sample: GoplsUsage.Sample?): String = listOfNotNull(
        title(buildInfo),
        sample?.let { "memory " + memory(it.memoryBytes) },
        sample?.cpuPercent?.let { "CPU " + cpu(it) },
        sample?.uptime?.let { "running " + uptime(it) },
    ).joinToString(", ")

    /** `gopls v0.23.0`, out of the build info the server gives for its version. */
    fun title(buildInfo: String?): String = listOf("gopls", GoplsLogLines.version(buildInfo)).filter { it.isNotEmpty() }.joinToString(" ")

    /** `go1.26.8`: the toolchain the server was built with, which is the newest Go it understands. */
    fun builtWith(buildInfo: String?): String? = GoplsLogLines.goVersion(buildInfo)

    /** A share of the whole machine, as the task manager of the system shows it. */
    fun cpu(percent: Double?): String = if (percent == null) MEASURING else String.format(Locale.ROOT, "%.1f %%", percent)

    fun memory(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return if (megabytes < 1024) String.format(Locale.ROOT, "%.0f MB", megabytes) else String.format(Locale.ROOT, "%.2f GB", megabytes / 1024)
    }

    /** What is committed is told when the system tells it apart: a Go heap of untouched pages is there and not in the working set. */
    fun memoryWithCommitted(bytes: Long, committed: Long): String = memory(bytes) + if (committed > 0 && committed != bytes) " (committed ${memory(committed)})" else ""

    fun uptime(uptime: Duration): String {
        val seconds = uptime.seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        }
    }

    fun process(pid: Long, processes: Int, uptime: Duration?): String = listOfNotNull(
        "PID $pid",
        if (processes > 1) "$processes processes" else null,
        uptime?.let { "running " + uptime(it) },
    ).joinToString(", ")
}
