package io.github.golangsupport.testing

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/** One line of a benchmark: `BenchmarkTotal-12  4899130  236.7 ns/op  48 B/op  1 allocs/op`, with whatever other metrics it reports (`MB/s`, custom ones). */
data class GoBenchmarkResult(val name: String, val procs: Int?, val iterations: Long, val metrics: Map<String, Double>) {
    val nsPerOp: Double? get() = metrics["ns/op"]
    val bytesPerOp: Double? get() = metrics["B/op"]
    val allocsPerOp: Double? get() = metrics["allocs/op"]
}

/** The results of one run of the benchmarks of a package, kept for the comparison with the next one. */
class GoBenchmarkRun(val packagePath: String, val time: Long, val results: List<GoBenchmarkResult>)

object GoBenchmarks {
    private val LINE = Regex("""^(Benchmark\S*?)(?:-(\d+))?\s+(\d+)\s+(.*)$""")
    private val METRIC = Regex("""(-?\d+(?:\.\d+)?)\s+(\S+)""")

    fun parseLine(line: String): GoBenchmarkResult? {
        val match = LINE.matchEntire(line.trim()) ?: return null
        val (name, procs, iterations, rest) = match.destructured
        val metrics = METRIC.findAll(rest).associate { it.groupValues[2] to it.groupValues[1].toDouble() }
        if (metrics.isEmpty()) return null
        return GoBenchmarkResult(name, procs.toIntOrNull(), iterations.toLong(), metrics)
    }

    /** The change in percent from [previous] to [current]; null without a previous value or with a zero one. */
    fun delta(current: Double?, previous: Double?): Double? =
        if (current == null || previous == null || previous == 0.0) null else (current - previous) / previous * 100

    fun format(value: Double?, unit: String): String = when {
        value == null -> ""
        unit == "ns/op" && value >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.2f ms/op", value / 1_000_000)
        unit == "ns/op" && value >= 1_000 -> String.format(java.util.Locale.ROOT, "%.2f µs/op", value / 1_000)
        value == Math.floor(value) && Math.abs(value) < 1e15 -> "${value.toLong()} $unit"
        else -> String.format(java.util.Locale.ROOT, "%.2f %s", value, unit)
    }

    fun formatDelta(delta: Double?): String = if (delta == null) "" else String.format(java.util.Locale.ROOT, "%+.1f%%", delta)
}

/**
 * The benchmark results of the last two runs of every package, in the workspace file: the table shows the last run next to the one before,
 * the way `benchstat` compares two files. Fed by the test console as the lines arrive, shown in the Go Tests window when the run ends.
 */
@Service(Service.Level.PROJECT)
@State(name = "GoBenchmarks", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GoBenchmarkResults(private val project: Project) : PersistentStateComponent<GoBenchmarkResults.State> {
    class State {
        /** Package path -> the last run; and the run before it, as JSON: the state of a component holds strings and maps well, objects less so. */
        var latest: MutableMap<String, String> = LinkedHashMap()
        var previous: MutableMap<String, String> = LinkedHashMap()
    }

    private var state = State()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun onChange(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun latest(): List<GoBenchmarkRun> = state.latest.values.mapNotNull(::decode).sortedBy { it.packagePath }
    fun previous(packagePath: String): GoBenchmarkRun? = state.previous[packagePath]?.let(::decode)

    /** A run of the benchmarks of [packagePath] has ended with [results]: the run before becomes the one to compare with. */
    fun record(packagePath: String, results: List<GoBenchmarkResult>) {
        if (results.isEmpty()) return
        state.latest[packagePath]?.let { state.previous[packagePath] = it }
        state.latest[packagePath] = GSON.toJson(GoBenchmarkRun(packagePath, System.currentTimeMillis(), results))
        listeners.forEach { it() }
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) GoBenchmarksTab.show(project) }, ModalityState.nonModal())
    }

    fun clear() {
        state = State()
        listeners.forEach { it() }
    }

    private fun decode(json: String): GoBenchmarkRun? = runCatching { GSON.fromJson(json, GoBenchmarkRun::class.java) }.getOrNull()

    companion object {
        private val GSON = Gson()
        fun getInstance(project: Project): GoBenchmarkResults = project.service()
    }
}

/** Collects the benchmark lines of one run, by package, and records them when the run is over. */
class GoBenchmarkCollector(private val project: Project) {
    private val byPackage = LinkedHashMap<String, MutableList<GoBenchmarkResult>>()
    private val pending = HashMap<String, StringBuilder>()

    /** A piece of the output of [packagePath]: `go test -json` splits a result line in two, the name with its tab, then the numbers (seen live). */
    @Synchronized fun line(packagePath: String, text: String) {
        val buffer = pending.getOrPut(packagePath, ::StringBuilder).append(text)
        while (true) {
            val end = buffer.indexOf("\n")
            if (end < 0) break
            val line = buffer.substring(0, end)
            buffer.delete(0, end + 1)
            val result = GoBenchmarks.parseLine(line) ?: continue
            byPackage.getOrPut(packagePath) { ArrayList() }.removeIf { it.name == result.name }
            byPackage.getValue(packagePath) += result
        }
    }

    @Synchronized fun finish() {
        val results = GoBenchmarkResults.getInstance(project)
        for ((packagePath, list) in byPackage) results.record(packagePath, list)
        byPackage.clear()
    }
}

/** The "Benchmarks" tab of the Go Tests tool window: made when the first results come, kept while the project is open. */
object GoBenchmarksTab {
    private const val TAB = "Benchmarks"

    fun show(project: Project) {
        val window = ToolWindowManager.getInstance(project).getToolWindow("Go Tests") ?: return
        val manager = window.contentManager
        val content = manager.contents.firstOrNull { it.tabName == TAB } ?: manager.factory.createContent(GoBenchmarksPanel(project, window.disposable), TAB, false).also {
            it.isCloseable = false
            manager.addContent(it)
        }
        manager.setSelectedContent(content)
        window.activate(null)
    }
}

/** Name, iterations, ns/op, B/op, allocs/op, each with the change from the run before; red for slower or bigger, green for the other way. */
class GoBenchmarksPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private class Row(val packagePath: String, val current: GoBenchmarkResult, val previous: GoBenchmarkResult?) {
        fun value(unit: String): Double? = current.metrics[unit]
        fun delta(unit: String): Double? = GoBenchmarks.delta(current.metrics[unit], previous?.metrics?.get(unit))
        val others: String get() = current.metrics.filterKeys { it !in STANDARD }.entries.joinToString("  ") { GoBenchmarks.format(it.value, it.key) }
    }

    private var rows: List<Row> = emptyList()
    private val model = object : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = COLUMNS.size
        override fun getColumnName(column: Int): String = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row]
    }
    private val table = JBTable(model)
    private val status = JBLabel()

    init {
        table.setDefaultRenderer(Any::class.java, object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val item = value as? Row ?: return
                when (column) {
                    0 -> append(item.packagePath.substringAfterLast('/'), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    1 -> { icon = AllIcons.Nodes.Test; append(item.current.name + (item.current.procs?.let { "-$it" } ?: "")) }
                    2 -> append(item.current.iterations.toString())
                    3, 5, 7 -> append(GoBenchmarks.format(item.value(STANDARD[(column - 3) / 2]), STANDARD[(column - 3) / 2]))
                    4, 6, 8 -> item.delta(STANDARD[(column - 4) / 2])?.let { delta ->
                        val worse = delta > 1.0
                        val better = delta < -1.0
                        append(GoBenchmarks.formatDelta(delta), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (worse) WORSE else if (better) BETTER else null))
                    }
                    9 -> append(item.others, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        })
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(260)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(status.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
        GoBenchmarkResults.getInstance(project).onChange(parent) { ApplicationManager.getApplication().invokeLater({ reload() }, ModalityState.any()) }
        reload()
    }

    private fun reload() {
        val results = GoBenchmarkResults.getInstance(project)
        val runs = results.latest()
        rows = runs.flatMap { run ->
            val before = results.previous(run.packagePath)
            run.results.map { current -> Row(run.packagePath, current, before?.results?.firstOrNull { it.name == current.name }) }
        }
        model.fireTableDataChanged()
        val compared = rows.count { it.previous != null }
        status.text = if (rows.isEmpty()) "No benchmarks have run yet: ▶ at a BenchmarkXxx function, or tick Benchmark in the run configuration"
        else "${rows.size} benchmarks of ${runs.size} packages" + (if (compared > 0) "; Δ against the run before for $compared of them" else "; the next run will show the change")
    }

    private companion object {
        val COLUMNS = listOf("Package", "Benchmark", "Iterations", "ns/op", "Δ", "B/op", "Δ", "allocs/op", "Δ", "Other")
        val STANDARD = listOf("ns/op", "B/op", "allocs/op")
        val WORSE = JBColor(0xC7222B, 0xE55765)
        val BETTER = JBColor(0x1E8449, 0x5FAD65)
    }
}
