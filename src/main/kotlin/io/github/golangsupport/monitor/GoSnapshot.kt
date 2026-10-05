package io.github.golangsupport.monitor

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SearchTextField
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.debugger.DapConnection
import io.github.golangsupport.debugger.DelveProcess
import io.github.golangsupport.debugger.json
import io.github.golangsupport.debugger.objects
import io.github.golangsupport.debugger.string
import io.github.golangsupport.run.DlvDap
import io.github.golangsupport.settings.GoSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel

/** One goroutine as delve names it: `[Go 18] runtime.gopark` with the `*` of the current one. */
class GoroutineInfo(val id: Int, val name: String) {
    /** `runtime.gopark`: the function at the top of its stack. */
    val function: String get() = name.substringAfter("] ").substringBefore(" (").trim()
}

/**
 * A look inside a running Go program with nothing in its code: delve attaches (the process stands still for a moment), lists the
 * goroutines and detaches. Where they wait is what a leak or a deadlock looks like; the runtime does not print this itself.
 */
object GoSnapshot {
    fun goroutines(project: Project, pid: Long, title: String) {
        val delve = GoTool.DELVE.find() ?: return GoTool.DELVE.offerInstallation(project, "Goroutines")
        object : Task.Backgroundable(project, "Goroutines of $title", true) {
            override fun run(indicator: ProgressIndicator) {
                val list = try {
                    attachAndList(delve.path, pid)
                } catch (e: Exception) {
                    GoCli.notifyError(project, "Goroutines of $title", e.message ?: e.javaClass.simpleName)
                    return
                }
                ApplicationManager.getApplication().invokeLater({ GoroutinesDialog(project, title, list).show() }, project.disposed)
            }
        }.queue()
    }

    /** `dlv dap`, `attach` to [pid], `threads`, `disconnect` without ending the process. */
    fun attachAndList(delve: String, pid: Long): List<GoroutineInfo> = attached(delve, pid) { connection ->
        val threads = connection.request("threads").get(15, TimeUnit.SECONDS).objects("threads")
        threads.mapNotNull { thread -> GoroutineInfo(thread.get("id")?.asInt ?: return@mapNotNull null, thread.string("name").orEmpty()) }
    }

    /** The same attach, with the stack of every goroutine: Dump Goroutines where there is no SIGQUIT (Windows). */
    fun attachAndDump(delve: String, pid: Long, depth: Int): List<GoGoroutine> = attached(delve, pid) { connection ->
        GoGoroutineDump.collect(depth) { command, arguments -> connection.request(command, arguments).get(15, TimeUnit.SECONDS) }
    }

    /** `dlv dap` attached to [pid] and stopped, [body] with the connection, then `disconnect` without ending the process. */
    private fun <T> attached(delve: String, pid: Long, body: (DapConnection) -> T): T {
        val process = DelveProcess(GoCli.toolCommandLine(delve, null, *DlvDap.arguments(log = false, GoSettings.getInstance().debugAnyGoVersion).toTypedArray()), null)
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val connection = DapConnection(process.input, process.output, object : DapConnection.Listener {
            override fun event(event: String, body: JsonObject) { events.put(event to body) }
        })
        try {
            connection.start("dlv snapshot")
            connection.request("initialize", json("clientID" to "intellij", "adapterID" to "go", "linesStartAt1" to true, "columnsStartAt1" to true)).get(10, TimeUnit.SECONDS)
            // `stopOnEntry`: without it `configurationDone` lets the program run on, and `threads` of a running program is one line, "Current" (seen live)
            val attached = connection.request("attach", json("mode" to "local", "processId" to pid, "stopOnEntry" to true))
            waitFor(events, "initialized", "delve has not attached to process $pid")
            connection.request("configurationDone").get(10, TimeUnit.SECONDS)
            attached.get(15, TimeUnit.SECONDS)
            waitFor(events, "stopped", "the program has not stopped for the snapshot")
            return body(connection)
        } finally {
            runCatching { connection.request("disconnect", json("terminateDebuggee" to false), 5_000).get() }
            connection.close()
            process.stop()
        }
    }

    private fun waitFor(events: LinkedBlockingQueue<Pair<String, JsonObject>>, name: String, failure: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val (event, _) = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (event == name) return
        }
        throw IllegalStateException(failure)
    }

    /** `runtime.gopark` for 40 goroutines, `main.worker` for 3: what the program is doing, in one glance. */
    fun summary(goroutines: List<GoroutineInfo>): List<Pair<String, Int>> =
        goroutines.groupingBy { it.function }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/** The goroutines of a snapshot: a list with a search, the functions with the most goroutines on top. */
class GoroutinesDialog(project: Project, title: String, private val goroutines: List<GoroutineInfo>) : DialogWrapper(project, false) {
    private val search = SearchTextField(false)
    private val model = DefaultListModel<String>()
    private val list = JBList(model)

    init {
        this.title = "Goroutines of $title"
        // a window to keep open next to the editor; modal it would block the IDE (seen live: a run could not start behind it)
        isModal = false
        setOKButtonText("Close")
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = fill()
        })
        fill()
        init()
    }

    private fun fill() {
        val text = search.text.trim()
        model.clear()
        GoSnapshot.summary(goroutines).filter { text.isEmpty() || it.first.contains(text, ignoreCase = true) }.forEach { (function, count) -> model.addElement("$count × $function") }
        model.addElement("")
        goroutines.filter { text.isEmpty() || it.name.contains(text, ignoreCase = true) }.forEach { model.addElement(it.name) }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(search, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        add(JBLabel("${goroutines.size} goroutines; the program ran on while delve was detached").apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() }, BorderLayout.SOUTH)
        preferredSize = Dimension(JBUI.scale(640), JBUI.scale(480))
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)
    override fun getPreferredFocusedComponent(): JComponent = search
}
