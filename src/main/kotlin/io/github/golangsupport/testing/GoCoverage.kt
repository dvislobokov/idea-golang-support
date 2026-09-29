package io.github.golangsupport.testing

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModulesService
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** One block of a coverage profile: [file] is the import path of the package with the file name; lines and columns are one-based. */
data class GoCoverageBlock(val file: String, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val statements: Int, val count: Int)

enum class GoLineCoverage { COVERED, UNCOVERED, PARTIAL }

class GoCoverageData(val mode: String, val blocks: List<GoCoverageBlock>) {
    val byFile: Map<String, List<GoCoverageBlock>> = blocks.groupBy { it.file }

    /** Statements run at least once, out of all, in the files [selected]; null when nothing was measured there. */
    fun percent(selected: (String) -> Boolean = { true }): Double? {
        val chosen = blocks.filter { selected(it.file) }
        val total = chosen.sumOf { it.statements }
        if (total == 0) return null
        return chosen.filter { it.count > 0 }.sumOf { it.statements } * 100.0 / total
    }

    /** Zero-based line -> how it went; a line no block covers (a declaration, a brace, a comment) is absent. */
    fun lines(file: String): Map<Int, GoLineCoverage> {
        val covered = HashMap<Int, Boolean>()
        val partial = HashSet<Int>()
        for (block in byFile[file].orEmpty()) for (line in block.startLine..block.endLine) {
            val hit = block.count > 0
            val known = covered[line - 1]
            if (known != null && known != hit) partial += line - 1
            covered[line - 1] = known == true || hit
        }
        return covered.mapValues { (line, hit) -> if (line in partial) GoLineCoverage.PARTIAL else if (hit) GoLineCoverage.COVERED else GoLineCoverage.UNCOVERED }
    }
}

/** `mode: set` and then `example.com/app/store/order.go:12.34,15.2 3 1` per block: the profile `go test -coverprofile` writes. */
object GoCoverage {
    private val BLOCK = Regex("""^(.+):(\d+)\.(\d+),(\d+)\.(\d+) (\d+) (\d+)$""")

    fun parse(text: CharSequence): GoCoverageData {
        var mode = "set"
        val blocks = ArrayList<GoCoverageBlock>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("mode:")) mode = line.removePrefix("mode:").trim()
            val match = BLOCK.matchEntire(line) ?: continue
            val g = match.groupValues
            blocks += GoCoverageBlock(g[1], g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt())
        }
        return GoCoverageData(mode, blocks)
    }

    fun newFile(): File = File.createTempFile("go-cover-", ".out")

    fun format(percent: Double): String = String.format(java.util.Locale.ROOT, "%.1f%%", percent)
}

/**
 * The coverage of the last run that collected it: painted in the gutter of the open Go files (green, red, yellow for a line both run and
 * skipped), and shown as a percentage in the Go Tests window. One profile at a time, as one run is what the question is about.
 */
@Service(Service.Level.PROJECT)
class GoCoverageService(private val project: Project) : Disposable {
    @Volatile var data: GoCoverageData? = null
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project && data != null) paint(event.editor)
            }
        }, this)
    }

    fun onChange(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** The profile of a run that has just finished; read and painted off EDT, then announced. */
    fun load(file: File, packageDirectory: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching { file.readText() }.getOrNull()
            runCatching { file.delete() }
            if (text.isNullOrBlank()) return@executeOnPooledThread
            val parsed = GoCoverage.parse(text)
            if (parsed.blocks.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                data = parsed
                repaintAll()
                listeners.forEach { it() }
                val percent = parsed.percent()?.let(GoCoverage::format) ?: "no statements"
                NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
                    .createNotification("Coverage: $percent of the statements", "Of ${parsed.byFile.size} files, from the tests of ${File(packageDirectory).name}; the gutter shows the lines", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimpleExpiring("Hide Coverage") { clear() })
                    .notify(project)
            }, ModalityState.any())
        }
    }

    fun clear() {
        data = null
        repaintAll()
        listeners.forEach { it() }
    }

    /** The share of the statements of the package in [directory], for the Go Tests window. */
    fun percentOf(directory: VirtualFile): Double? {
        val data = data ?: return null
        val importPath = ReadAction.compute<String?, RuntimeException> { GoModulesService.getInstance(project).moduleOf(directory)?.importPath(directory) } ?: return null
        return data.percent { it.substringBeforeLast('/') == importPath }
    }

    /** The key of [file] in the profile: the import path of its package and its name. */
    private fun keyOf(file: VirtualFile): String? {
        val directory = file.parent ?: return null
        val importPath = GoModulesService.getInstance(project).moduleOf(directory)?.importPath(directory) ?: return null
        return "$importPath/${file.name}"
    }

    private fun repaintAll() {
        for (editor in EditorFactory.getInstance().allEditors) if (editor.project == project) paint(editor)
    }

    private fun paint(editor: Editor) {
        editor.getUserData(HIGHLIGHTERS)?.forEach { it.dispose() }
        editor.putUserData(HIGHLIGHTERS, null)
        val data = data ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.fileType == GoFileType } ?: return
        val key = ReadAction.compute<String?, RuntimeException> { keyOf(file) } ?: return
        val lines = data.lines(key).takeIf { it.isNotEmpty() } ?: return
        val scheme = editor.colorsScheme
        fun colour(key: com.intellij.openapi.editor.colors.TextAttributesKey, fallback: Color): Color = scheme.getAttributes(key)?.foregroundColor ?: fallback
        val colours = mapOf(
            GoLineCoverage.COVERED to colour(CodeInsightColors.LINE_FULL_COVERAGE, JBColor(0x3EA143, 0x499C54)),
            GoLineCoverage.UNCOVERED to colour(CodeInsightColors.LINE_NONE_COVERAGE, JBColor(0xE55765, 0xC75450)),
            GoLineCoverage.PARTIAL to colour(CodeInsightColors.LINE_PARTIAL_COVERAGE, JBColor(0xE8B740, 0xD9A343)),
        )
        val markup = editor.markupModel
        val highlighters = lines.filter { it.key < editor.document.lineCount }.map { (line, status) ->
            markup.addLineHighlighter(null, line, HighlighterLayer.ADDITIONAL_SYNTAX).apply { lineMarkerRenderer = GoCoverageLineMarkerRenderer(colours.getValue(status)) }
        }
        editor.putUserData(HIGHLIGHTERS, highlighters)
    }

    override fun dispose() {
        data = null
        for (editor in EditorFactory.getInstance().allEditors) editor.getUserData(HIGHLIGHTERS)?.forEach { it.dispose() }
    }

    companion object {
        private val HIGHLIGHTERS: Key<List<RangeHighlighter>> = Key.create("io.github.golangsupport.coverage.highlighters")

        fun getInstance(project: Project): GoCoverageService = project.service()
    }
}

/** A bar in the gutter of a line, the way the coverage of the platform paints it. */
class GoCoverageLineMarkerRenderer(private val colour: Color) : LineMarkerRendererEx {
    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        g.color = colour
        g.fillRect(r.x, r.y, 4, r.height)
    }

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT
}
