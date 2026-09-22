package io.github.golangsupport.monitor

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.debugger.GoAttachProfile
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class GoMonitorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GoMonitorPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }

    companion object {
        const val ID = "Go Monitor"
    }
}

/** Go | Monitor Go Process */
class ShowGoMonitorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(GoMonitorToolWindowFactory.ID)?.activate(null)
    }
}

/**
 * A narrow panel on the right, as the Monitoring of Rider and the .NET sibling of this plugin: the process on top, the charts one under
 * another. CPU and memory come from the operating system for any process; the heap, the collections and the scheduler come from the
 * runtime of a program the IDE started with "Collect runtime telemetry" in its run configuration. Goroutines are a snapshot with delve.
 */
class GoMonitorPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
    private val processes = ComboBox<MonitorTarget>()
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var session: GoMonitorSession? = null
    private var updatingList = false

    /** Off by default: the list needs `go version -m` of every executable of the machine the first time. */
    private val allProcesses = JBCheckBox("All Go processes", PropertiesComponent.getInstance().getBoolean(ALL_PROCESSES_KEY, false)).apply {
        toolTipText = "Also list the Go programs of the machine that were not started from this IDE (found by their build info)"
        addActionListener {
            PropertiesComponent.getInstance().setValue(ALL_PROCESSES_KEY, isSelected, false)
            reloadProcesses(select = null)
        }
    }

    private val cpu = TimeSeriesChart("CPU", ChartFormats::percent, fixedMax = 100.0, "of ${Runtime.getRuntime().availableProcessors()} cores" to CPU_COLOR)
    private val memory = TimeSeriesChart("Memory", ChartFormats::bytes, null, "committed" to MEMORY_COLOR, "working set" to CLIENT_COLOR, "live heap" to HEAP_COLOR).apply { scale = ChartFormats::niceMaxBytes }
    private val heap = TimeSeriesChart("Heap", ChartFormats::bytes, null, "before GC" to HEAP_COLOR, "goal" to CLIENT_COLOR).apply { scale = ChartFormats::niceMaxBytes }
    private val gcPause = TimeSeriesChart("GC pauses", { ChartFormats.number(it) + " ms" }, null, "wall clock per second" to GC_COLOR)
    private val gcCount = TimeSeriesChart("GC collections", { ChartFormats.number(it) + "/s" }, null, "collections" to GC_COLOR, "CPU in GC %" to ERROR_COLOR)
    private val threads = TimeSeriesChart("Threads", ChartFormats::number, null, "threads" to CPU_COLOR, "idle" to CLIENT_COLOR)
    private val scheduler = TimeSeriesChart("Scheduler", ChartFormats::number, null, "runnable goroutines" to REQUEST_COLOR, "idle procs" to CLIENT_COLOR)
    private val charts = listOf(cpu, memory, heap, gcPause, gcCount, threads, scheduler)

    init {
        Disposer.register(parent, this)
        val refresh = JButton("Refresh").apply { addActionListener { reloadProcesses(select = null) } }
        val chooser = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(processes, BorderLayout.CENTER)
            add(refresh, BorderLayout.EAST)
        }
        val goroutines = JButton("Goroutines").apply {
            toolTipText = "All goroutines and where they wait (delve attaches for a moment): leaks and deadlocks"
            addActionListener { session?.let { GoSnapshot.goroutines(project, it.applicationPid, it.target.title) } }
        }
        val attach = JButton("Debug").apply {
            toolTipText = "Attach the debugger to the process"
            addActionListener { session?.let { attach(it.applicationPid, it.target.title) } }
        }
        val snapshots = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(goroutines)
            add(attach)
            add(allProcesses)
        }
        val top = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(6, 8, 2, 8)
            add(chooser, BorderLayout.NORTH)
            add(snapshots, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val column = JPanel(GridLayout(0, 1, 0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            charts.forEach { add(it) }
        }
        // NORTH: the charts keep their height instead of stretching over a tall panel
        val content = JPanel(BorderLayout()).apply { add(column, BorderLayout.NORTH) }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(content, true).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)

        processes.addActionListener { if (!updatingList) (processes.selectedItem as? MonitorTarget)?.let(::monitor) }
        RunningGoProcesses.getInstance(project).subscribe(this) { started ->
            ApplicationManager.getApplication().invokeLater({ reloadProcesses(select = started) }, project.disposed)
        }
        reloadProcesses(select = RunningGoProcesses.getInstance(project).targets().firstOrNull())
    }

    /** The programs started from the IDE; with "All Go processes" the other Go programs of the machine after them. */
    private fun reloadProcesses(select: MonitorTarget?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val own = RunningGoProcesses.getInstance(project).targets()
            val ownPids = own.flatMap { target -> ProcessSampler.tree(target.pid).map { it.pid() } }.toSet()
            val go = GoCli.findExecutable()
            val others = (if (allProcesses.isSelected && go != null) GoProcesses.list(go) else emptyList())
                .filter { it.pid.toLong() !in ownPids }
                .map { MonitorTarget(it.pid.toLong(), it.toString(), withChildren = false, process = it) }
            ApplicationManager.getApplication().invokeLater({
                val current = select ?: session?.target ?: processes.selectedItem as? MonitorTarget
                updatingList = true
                processes.model = DefaultComboBoxModel((own + others).toTypedArray())
                processes.selectedItem = current?.takeIf { it in own + others }
                updatingList = false
                val selected = processes.selectedItem as? MonitorTarget
                if (selected == null) status.text = when {
                    own.isEmpty() && others.isEmpty() && !allProcesses.isSelected -> "Run a Go configuration, or tick All Go processes"
                    own.isEmpty() && others.isEmpty() -> "No Go processes"
                    else -> "Choose a process above"
                }
                if (selected != null && selected != session?.target) monitor(selected)
            }, project.disposed)
        }
    }

    private fun monitor(target: MonitorTarget) {
        if (session?.target == target) return
        session?.dispose()
        charts.forEach { it.clear() }
        status.text = target.process?.info?.describe() ?: if (target.runtime == null) "CPU and memory only: tick \"Collect runtime telemetry\" in the run configuration for the heap, GC and scheduler" else ""
        val started = GoMonitorSession(
            target,
            onSample = { sample -> ApplicationManager.getApplication().invokeLater({ show(target, sample) }, project.disposed) },
            onEnd = { ApplicationManager.getApplication().invokeLater({ if (session?.target == target) { status.text = "${target.title} has exited"; session = null } }, project.disposed) },
        )
        session = started
        started.start()
    }

    private fun show(target: MonitorTarget, sample: MonitorSample) {
        if (session?.target != target) return
        val runtime = sample.runtime
        cpu.add(sample.cpuPercent)
        memory.add(sample.privateBytes.toDouble(), sample.workingSetBytes.toDouble(), runtime?.liveHeapMb?.let { it * MEGABYTE })
        heap.add(runtime?.heapBeforeMb?.let { it * MEGABYTE }, runtime?.goalMb?.let { it * MEGABYTE })
        gcPause.add(runtime?.pauseMs)
        gcCount.add(runtime?.collectionsPerSecond?.toDouble(), runtime?.gcCpuPercent?.toDouble())
        threads.add(runtime?.threads?.toDouble() ?: sample.threads?.toDouble(), runtime?.idleThreads?.toDouble())
        scheduler.add(runtime?.queued?.toDouble(), runtime?.idleProcs?.toDouble())
        if (runtime != null) {
            status.text = listOfNotNull(
                "${sample.processes} process${if (sample.processes == 1) "" else "es"}",
                "${runtime.collections} GCs",
                runtime.procs?.let { "GOMAXPROCS $it" },
            ).joinToString(", ")
        }
    }

    private fun attach(pid: Long, title: String) {
        val profile = GoAttachProfile(pid.toInt(), title)
        val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), profile).build()
        ExecutionManager.getInstance(project).restartRunProfile(environment)
    }

    override fun dispose() {
        session?.dispose()
        session = null
    }

    private companion object {
        const val ALL_PROCESSES_KEY = "io.github.golangsupport.monitor.allProcesses"
        /** The runtime prints megabytes of 2^20 bytes. */
        const val MEGABYTE = 1024.0 * 1024
        val CPU_COLOR = JBColor(Color(0x3574F0), Color(0x548AF7))
        val MEMORY_COLOR = JBColor(Color(0x208A3C), Color(0x5FAD65))
        val HEAP_COLOR = JBColor(Color(0xC77D00), Color(0xF2C55C))
        val GC_COLOR = JBColor(Color(0x834DF0), Color(0xA571E6))
        val REQUEST_COLOR = JBColor(Color(0x0D7F91), Color(0x24A3B8))
        val CLIENT_COLOR = JBColor(Color(0x6C707E), Color(0x9DA0A8))
        val ERROR_COLOR = JBColor(Color(0xDB3B4B), Color(0xF75464))
    }
}
