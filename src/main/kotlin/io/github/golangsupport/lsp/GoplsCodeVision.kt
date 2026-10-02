package io.github.golangsupport.lsp

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.testing.GoTests
import java.util.concurrent.ConcurrentHashMap

/**
 * How many usages gopls knows of a declaration, and how many declarations on the other side of "implements": implementations of an
 * interface or of its method, interfaces (interface methods) a type or a method satisfies. Null: not asked.
 */
class GoplsCounts(val usages: Int?, val implementations: Int?)

/**
 * The numbers behind "3 usages" and "2 implementations" above the declarations. gopls has no such code lens, and the platform counts
 * usages through PSI references, which this plugin has none of; so every declaration of a file is asked about, in the background,
 * once per change of the file. The hints show what is known and are refreshed when the answers are in.
 */
@Service(Service.Level.PROJECT)
class GoplsCountsService(private val project: Project) {
    private class Entry(val stamp: Long, val counts: Map<String, GoplsCounts>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val computing = ConcurrentHashMap.newKeySet<String>()

    /** What is known about the declarations of [file], by [key]; stale numbers are better than blinking hints while typing. */
    fun counts(file: VirtualFile, stamp: Long): Map<String, GoplsCounts> {
        val entry = entries[file.url]
        if (entry?.stamp != stamp && computing.add(file.url)) ApplicationManager.getApplication().executeOnPooledThread { compute(file, stamp) }
        return entry?.counts.orEmpty()
    }

    private fun compute(file: VirtualFile, stamp: Long) {
        try {
            val client = Gopls.client(project) ?: return
            val declarations = ReadAction.compute<List<Pair<GoDeclarationInfo, org.eclipse.lsp4j.Position>>, RuntimeException> {
                val document = FileDocumentManager.getInstance().getDocument(file)
                val psi = if (project.isDisposed || !file.isValid) null else PsiManager.getInstance(project).findFile(file) as? GoFile
                if (document == null || psi == null || document.modificationStamp != stamp) emptyList()
                else GoDeclarationInfo.all(psi).filter { isCounted(it, file.name) }.take(MAX_DECLARATIONS).map { it to Gopls.position(document, it.nameRange.startOffset) }
            }
            if (declarations.isEmpty()) return
            val counts = HashMap<String, GoplsCounts>()
            for ((declaration, position) in declarations) {
                if (project.isDisposed) return
                val usages = Gopls.references(client, file, position, TIMEOUT_MS).size
                val implementations = if (hasImplementations(declaration)) Gopls.implementations(client, file, position, TIMEOUT_MS).size else null
                counts[key(declaration)] = GoplsCounts(usages, implementations)
            }
            entries[file.url] = Entry(stamp, counts)
            // a restart that lands in the highlighting of the next test of the shared project is an assertion there (seen in the test run)
            if (ApplicationManager.getApplication().isUnitTestMode) return
            ApplicationManager.getApplication().invokeLater({
                if (file.isValid) PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
            }, project.disposed)
        } finally {
            computing.remove(file.url)
        }
    }

    companion object {
        private const val MAX_DECLARATIONS = 300
        private const val TIMEOUT_MS = 5000

        /** Names, not offsets: they survive the typing that moves everything below it. */
        fun key(declaration: GoDeclarationInfo): String = "${declaration.kind}:${declaration.receiver.orEmpty()}.${declaration.name}"

        /** Entry points are used by the toolchain, not by code: "no usages" above `main` or a test would be noise. */
        fun isCounted(declaration: GoDeclarationInfo, fileName: String): Boolean = when (declaration.kind) {
            GoDeclarationKind.FUNCTION -> declaration.name != "main" && declaration.name != "init" && GoTests.kindOf(declaration, fileName) == null
            GoDeclarationKind.METHOD, GoDeclarationKind.STRUCT, GoDeclarationKind.INTERFACE, GoDeclarationKind.TYPE, GoDeclarationKind.INTERFACE_METHOD -> true
            else -> false
        }

        /** What `textDocument/implementation` has an answer for; a plain function or a field gets an error instead. */
        fun hasImplementations(declaration: GoDeclarationInfo): Boolean = isInterfaceSide(declaration.kind) ||
            declaration.kind == GoDeclarationKind.STRUCT || declaration.kind == GoDeclarationKind.TYPE || declaration.kind == GoDeclarationKind.METHOD

        fun isInterfaceSide(kind: GoDeclarationKind): Boolean = kind == GoDeclarationKind.INTERFACE || kind == GoDeclarationKind.INTERFACE_METHOD

        fun usagesText(count: Int): String = if (count == 0) "no usages" else if (count == 1) "1 usage" else "$count usages"
        fun implementationsText(count: Int): String = if (count == 1) "1 implementation" else "$count implementations"
    }
}

/** The hints of one kind above the declarations of a Go file; a click does what the action of the IDE does with the caret on the name. */
abstract class GoplsCodeVisionProvider(private val actionId: String) : DaemonBoundCodeVisionProvider {
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Default

    protected abstract fun text(declaration: GoDeclarationInfo, counts: GoplsCounts): String?

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (GoFeatures.native(GoFeature.CODE_VISION, file.project)) return emptyList()
        val virtualFile = (file as? GoFile)?.virtualFile ?: return emptyList()
        if (Gopls.client(file.project) == null) return emptyList()
        val counts = file.project.service<GoplsCountsService>().counts(virtualFile, editor.document.modificationStamp)
        return GoDeclarationInfo.all(file as GoFile).mapNotNull { declaration ->
            val text = counts[GoplsCountsService.key(declaration)]?.let { text(declaration, it) } ?: return@mapNotNull null
            val offset = declaration.nameRange.startOffset
            declaration.range to ClickableTextCodeVisionEntry(text, id, { _, clickedEditor -> invoke(clickedEditor, offset) }, null, text, "", emptyList())
        }
    }

    private fun invoke(editor: Editor, offset: Int) {
        editor.caretModel.moveToOffset(offset)
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, "GoCodeVision", true)
    }
}

class GoplsUsagesCodeVisionProvider : GoplsCodeVisionProvider("ShowUsages") {
    override val id: String get() = "go.usages"
    override val name: String get() = "Go usages"

    /** The group of the platform: Settings | Editor | Inlay Hints | Code vision | Usages switches these too. */
    override val groupId: String get() = "references"
    override fun text(declaration: GoDeclarationInfo, counts: GoplsCounts): String? = counts.usages?.let(GoplsCountsService::usagesText)
}

class GoplsImplementationsCodeVisionProvider : GoplsCodeVisionProvider("GotoImplementation") {
    override val id: String get() = "go.implementations"
    override val name: String get() = "Go implementations"
    override val groupId: String get() = "inheritors"
    /** For interfaces only: what a type implements is said by the icon in the gutter, a number of interfaces above a struct reads oddly. */
    override fun text(declaration: GoDeclarationInfo, counts: GoplsCounts): String? =
        counts.implementations?.takeIf { it > 0 && GoplsCountsService.isInterfaceSide(declaration.kind) }?.let(GoplsCountsService::implementationsText)
}
