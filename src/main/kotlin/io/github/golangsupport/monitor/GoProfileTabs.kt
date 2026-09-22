package io.github.golangsupport.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.ToolTipManager
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** The tabs of the Go Monitor tool window after "Live": profiles and goroutine dumps, closeable. */
object GoMonitorTabs {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun open(project: Project, title: String, component: JComponent) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(GoMonitorToolWindowFactory.ID) ?: return
        val manager = window.contentManager
        val content = manager.factory.createContent(component, "$title ${TIME.format(LocalTime.now())}", false).apply {
            isCloseable = true
            if (component is Disposable) setDisposer(component)
        }
        manager.addContent(content)
        manager.setSelectedContent(content)
        window.activate(null)
    }

    /** A frame of a profile or of a goroutine stack in the editor; the file may be outside the project (the module cache, GOROOT). */
    fun navigate(project: Project, file: String, line: Int) {
        if (file.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.replace('\\', '/')) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ OpenFileDescriptor(project, virtualFile, (line - 1).coerceAtLeast(0), 0).navigate(true) }, project.disposed)
        }
    }
}

/**
 * A pprof profile in the IDE: the flame graph and Top, as `go tool pprof -http` shows them, without Graphviz and without a browser.
 * The sample type is chosen above (heap: in use or allocated, space or objects).
 */
class GoProfileView(private val project: Project, private val profile: PprofProfile, source: String, private val openInBrowser: () -> Unit) : JPanel(BorderLayout()), Disposable {
    private val types = ComboBox(profile.types.toTypedArray()).apply { selectedIndex = profile.defaultIndex }
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Function" }
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val flame = GoFlameGraph { GoMonitorTabs.navigate(project, it.file, it.line) }
    private val topModel = TopModel()
    private val top = JBTable(topModel).apply {
        setShowGrid(false)
        autoCreateRowSorter = true
        val numbers = NumberRenderer()
        for (column in 0 until 5) columnModel.getColumn(column).apply { cellRenderer = numbers; preferredWidth = JBUI.scale(70); maxWidth = JBUI.scale(110) }
        columnModel.getColumn(5).preferredWidth = JBUI.scale(500)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val row = rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                topModel.rows[convertRowIndexToModel(row)].let { GoMonitorTabs.navigate(project, it.file, it.line) }
            }
        })
    }

    init {
        types.isVisible = profile.types.size > 1
        types.addActionListener { refill() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                flame.highlight = search.text.trim()
                topModel.filter = search.text.trim()
            }
        })
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(types)
            add(search)
            add(ActionLink("Open in Browser") { openInBrowser() }.apply { toolTipText = "go tool pprof -http: the flame graph, Top, Source and Peek of pprof itself" })
        }
        val north = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 4, 0, 4)
            add(header, BorderLayout.NORTH)
            add(JBLabel(source).apply { border = JBUI.Borders.emptyLeft(8); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
            add(summary.apply { border = JBUI.Borders.emptyLeft(8) }, BorderLayout.SOUTH)
        }
        val tabs = JBTabbedPane().apply {
            addTab("Flame Graph", ScrollPaneFactory.createScrollPane(flame, true))
            addTab("Top", ScrollPaneFactory.createScrollPane(top, true))
        }
        add(north, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        refill()
    }

    private fun refill() {
        val index = types.selectedIndex.coerceAtLeast(0)
        val unit = profile.types.getOrNull(index)?.unit ?: "count"
        val total = profile.total(index)
        summary.text = if (total == 0L) "No samples of this type: the program did nothing it records while the profile was taken"
        else "Total ${GoPprofViews.format(total, unit)}" + (profile.duration?.let { " in $it s" } ?: "") + ". Click a frame to zoom in, double-click to open the source"
        flame.show(GoPprofViews.flame(profile, index), unit)
        topModel.show(GoPprofViews.top(profile, index), total, unit)
    }

    override fun dispose() = Unit

    private inner class TopModel : AbstractTableModel() {
        private var all: List<PprofTopRow> = emptyList()
        private var sums: Map<PprofTopRow, Long> = emptyMap()
        var rows: List<PprofTopRow> = emptyList()
        var total = 0L
        var unit = "count"
        var filter = ""
            set(value) { field = value; apply() }

        fun show(top: List<PprofTopRow>, total: Long, unit: String) {
            all = top
            var sum = 0L
            sums = top.associateWith { sum += it.flat; sum }
            this.total = total
            this.unit = unit
            apply()
        }

        private fun apply() {
            rows = if (filter.isEmpty()) all else all.filter { it.function.contains(filter, true) }
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 5) String::class.java else java.lang.Double::class.java
        override fun getValueAt(row: Int, column: Int): Any {
            val it = rows[row]
            return when (column) {
                0 -> it.flat.toDouble()
                1 -> percent(it.flat)
                2 -> percent(sums[it] ?: 0)
                3 -> it.cum.toDouble()
                4 -> percent(it.cum)
                else -> it.function
            }
        }

        private fun percent(value: Long) = if (total == 0L) 0.0 else value * 100.0 / total
    }

    /** Values in the unit of the profile, percents with one digit; right-aligned. */
    private inner class NumberRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.RIGHT }
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int) =
            super.getTableCellRendererComponent(table, value, selected, focused, row, column).also {
                val number = value as? Double ?: 0.0
                val model = table.convertColumnIndexToModel(column)
                text = if (model == 0 || model == 3) GoPprofViews.format(number.toLong(), topModel.unit) else String.format(java.util.Locale.ROOT, "%.1f%%", number)
            }
    }

    private companion object {
        val COLUMNS = arrayOf("Flat", "Flat%", "Sum%", "Cum", "Cum%", "Function")
    }
}

/**
 * The flame graph of pprof: the root on top, each function under its caller, as wide as its share. A click zooms into a frame (a frame
 * above the zoomed one zooms out), a double click opens its source. The program's own code is warm, the standard library cool.
 */
class GoFlameGraph(private val onNavigate: (PprofFrame) -> Unit) : JComponent() {
    private class Box(val node: PprofFlameNode, val path: List<PprofFlameNode>, val x: Int, val y: Int, val width: Int)

    private var root = PprofFlameNode("all", null)
    private var unit = "count"
    private var zoom: List<PprofFlameNode> = listOf(root)
    private var boxes: List<Box> = emptyList()
    private var depth = 1
    var highlight = ""
        set(value) { field = value; repaint() }

    init {
        ToolTipManager.sharedInstance().registerComponent(this)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val box = boxAt(e) ?: return
                if (e.clickCount == 2) box.node.frame?.let(onNavigate)
                else if (e.clickCount == 1) { zoom = box.path; revalidate(); repaint() }
            }
        })
    }

    fun show(root: PprofFlameNode, unit: String) {
        this.root = root
        this.unit = unit
        zoom = listOf(root)
        depth = depthOf(root)
        revalidate()
        repaint()
    }

    private fun depthOf(node: PprofFlameNode): Int = 1 + (node.children.values.maxOfOrNull(::depthOf) ?: 0)

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(300), (depth + 1) * rowHeight())

    private fun rowHeight() = JBUI.scale(18)

    private fun layout(width: Int): List<Box> {
        val result = ArrayList<Box>()
        val row = rowHeight()
        // the zoomed frame and its callers above it, each the full width
        zoom.forEachIndexed { index, node -> result += Box(node, zoom.subList(0, index + 1), 0, index * row, width) }
        fun children(node: PprofFlameNode, path: List<PprofFlameNode>, x: Double, level: Int, scale: Double) {
            var left = x
            for (child in node.sortedChildren()) {
                val w = child.value * scale
                if (w >= 1.0) {
                    val childPath = path + child
                    result += Box(child, childPath, left.toInt(), level * row, (left + w).toInt() - left.toInt())
                    children(child, childPath, left, level + 1, scale)
                }
                left += w
            }
        }
        val zoomed = zoom.last()
        if (zoomed.value > 0) children(zoomed, zoom, 0.0, zoom.size, width.toDouble() / zoomed.value)
        return result
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)
            if (root.value == 0L) {
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString("No samples", JBUI.scale(8), JBUI.scale(16))
                return
            }
            boxes = layout(width)
            val row = rowHeight()
            val metrics = g2.fontMetrics
            val zoomedDepth = zoom.size - 1
            for (box in boxes) {
                val matches = highlight.isNotEmpty() && box.node.function.contains(highlight, true)
                val color = when {
                    matches -> MATCH
                    box.node.frame == null -> ROOT
                    GoGoroutineDump.isLibrary(box.node.function) -> LIBRARY
                    else -> OWN
                }
                // with a search, what does not match fades; the callers above a zoomed frame fade too
                val faded = (highlight.isNotEmpty() && !matches) || box.y / row < zoomedDepth
                g2.color = if (faded) blend(color, UIUtil.getPanelBackground()) else color
                g2.fillRect(box.x, box.y, (box.width - 1).coerceAtLeast(1), row - 1)
                if (box.width > JBUI.scale(24)) {
                    g2.color = JBColor.foreground()
                    val text = fit(shortName(box.node.function), box.width - JBUI.scale(6), metrics)
                    if (text.isNotEmpty()) g2.drawString(text, box.x + JBUI.scale(3), box.y + (row + metrics.ascent - metrics.descent) / 2)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val box = boxAt(event) ?: return null
        val node = box.node
        val share = GoPprofViews.percent(node.value, root.value)
        val where = node.frame?.takeIf { it.file.isNotEmpty() }?.let { "<br>${escape(it.file)}:${it.line}" }.orEmpty()
        return "<html><b>${escape(node.function)}</b><br>${GoPprofViews.format(node.value, unit)} ($share)$where</html>"
    }

    private fun boxAt(event: MouseEvent): Box? = boxes.lastOrNull { event.x >= it.x && event.x < it.x + it.width && event.y >= it.y && event.y < it.y + rowHeight() }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        val OWN = JBColor(Color(0xF5B971), Color(0x9A6B2F))
        val LIBRARY = JBColor(Color(0xA9C7EE), Color(0x3D5A80))
        val ROOT = JBColor(Color(0xCFD3DB), Color(0x4E5157))
        val MATCH = JBColor(Color(0xE88AD0), Color(0xA3448E))

        /** `github.com/x/y.(*T).M` → `y.(*T).M`: the package path is in the tooltip. */
        fun shortName(function: String) = function.substring(function.lastIndexOf('/', function.indexOf('.').let { if (it < 0) function.length else it }) + 1)

        fun fit(text: String, width: Int, metrics: java.awt.FontMetrics): String {
            if (metrics.stringWidth(text) <= width) return text
            var end = text.length
            while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > width) end--
            return if (end < 2) "" else text.substring(0, end) + "…"
        }

        fun blend(color: Color, background: Color) = Color((color.red + background.red * 2) / 3, (color.green + background.green * 2) / 3, (color.blue + background.blue * 2) / 3)
    }
}

/** The goroutines from pprof in a tab: the groups by stack on the left, the stack of the chosen one on the right; the program is not stopped. */
class GoroutineGroupsPanel(private val project: Project, private val groups: List<GoGoroutineDump.Group>, total: Int?, source: String) : JPanel(BorderLayout(0, JBUI.scale(4))) {
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Function or file" }
    private val model = DefaultListModel<GoGoroutineDump.Group>()
    private val list = JBList(model)
    private val stack = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, UIUtil.getLabelFont().size)
        toolTipText = "Double-click a line to open the source"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val frames = list.selectedValue?.frames ?: return
                // two lines per frame: the function, then the location
                val frame = frames.getOrNull(getLineOfOffset(viewToModel2D(e.point)) / 2) ?: return
                val match = Regex("""^(.*):(\d+)$""").find(frame.location) ?: return
                GoMonitorTabs.navigate(project, match.groupValues[1], match.groupValues[2].toInt())
            }
        })
    }

    init {
        border = JBUI.Borders.empty(4)
        list.cellRenderer = SimpleListCellRenderer.create("") { it.title }
        list.addListSelectionListener { show(list.selectedValue) }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = fill()
        })
        val count = total ?: groups.sumOf { it.count }
        add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            add(search, BorderLayout.NORTH)
            add(JBLabel("$count goroutines in ${groups.size} groups of $source; the program was not stopped").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(Splitter(true, 0.5f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(list)
            secondComponent = ScrollPaneFactory.createScrollPane(stack)
        }, BorderLayout.CENTER)
        fill()
    }

    private fun fill() {
        val text = search.text.trim()
        model.clear()
        groups.filter { group -> text.isEmpty() || group.frames.any { it.function.contains(text, true) || it.location.contains(text, true) } }.forEach(model::addElement)
        if (model.size() > 0) list.selectedIndex = 0 else stack.text = ""
    }

    private fun show(group: GoGoroutineDump.Group?) {
        stack.text = group?.frames?.joinToString("\n") { "${it.function}\n    ${it.location}" }.orEmpty()
        stack.caretPosition = 0
    }
}

/** Parses a pprof file with `go tool pprof -raw` off the EDT and opens it in a tab; a file pprof cannot read is reported. */
object GoProfileLoader {
    fun open(project: Project, file: File, title: String, source: String) {
        object : com.intellij.openapi.progress.Task.Backgroundable(project, "Reading $title", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val commandLine = io.github.golangsupport.cli.GoCli.commandLinesOrNotify(project, "Open $title") {
                    listOf(io.github.golangsupport.cli.GoCli.commandLine(file.parent, "tool", "pprof", "-raw", file.path))
                }?.first() ?: return
                val output = io.github.golangsupport.cli.GoCli.execute(commandLine, 120_000)
                if (output.exitCode != 0 || output.isTimeout) {
                    return io.github.golangsupport.cli.GoCli.notifyError(project, "Open $title", output.stderr.ifBlank { "go tool pprof -raw has exited with code ${output.exitCode}" })
                }
                val profile = GoPprofRaw.parse(output.stdout)
                ApplicationManager.getApplication().invokeLater({
                    val view = GoProfileView(project, profile, source) { GoProfileServers.getInstance(project).openInBrowser(file, title, trace = false) }
                    GoMonitorTabs.open(project, title, view)
                }, project.disposed)
            }
        }.queue()
    }
}
