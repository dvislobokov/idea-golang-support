package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.icons.AllIcons
import com.intellij.ide.util.ChooseElementsDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.refactoring.GoAddInterfaceMethod
import io.github.golangsupport.ide.refactoring.GoSafeDeleteProcessor
import io.github.golangsupport.ide.refactoring.GoSignatureHierarchy
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import org.jetbrains.annotations.TestOnly
import javax.swing.Icon

/** A method that implements the removed interface method: [type].[name] in [file]; [used] when something other than itself calls it. */
class GoRemoveMethodCandidate(val method: SmartPsiElementPointer<GoMethodDeclaration>, val type: String, val name: String, val file: String, val used: Boolean) {
    override fun toString(): String = "$type.$name"
}

/** The pure part of "Remove method from interface and all its implementations": the methods to offer and the text to delete. */
object GoRemoveInterfaceMethod {

    /** The method spec [element] stands in, when it is one of a package-level interface of the project outside generated files. */
    fun specAt(element: PsiElement): GoMethodSpec? {
        val spec = PsiTreeUtil.getParentOfType(element, GoMethodSpec::class.java, false) ?: return null
        val iface = GoImplementations.interfaceSpecOf(spec) ?: return null
        if (GoAddInterfaceMethod.interfaceAt(spec) != iface || !GoImplementations.isInProject(iface) || GoSignatureHierarchy.isGenerated(iface.containingFile)) return null
        return spec
    }

    /**
     * The methods of the project types implementing the interface of [spec] that are declared by those types (not promoted from an
     * embedded one, which other code still needs), outside generated files. Read action.
     */
    fun candidates(spec: GoMethodSpec): List<GoRemoveMethodCandidate> {
        val iface = GoImplementations.interfaceSpecOf(spec) ?: return emptyList()
        val project = spec.project
        val types = GoImplementations.implementingTypes(iface, GlobalSearchScope.projectScope(project)).toSet()
        val pointers = SmartPointerManager.getInstance(project)
        return GoImplementations.implementingMethods(spec, GlobalSearchScope.projectScope(project)).distinct()
            .filter { m -> GoImplementations.isInProject(m) && !GoSignatureHierarchy.isGenerated(m.containingFile) && GoImplementations.receiverTypeSpec(m) in types }
            .map { m -> GoRemoveMethodCandidate(pointers.createSmartPsiElementPointer(m), m.receiverTypeName ?: "?", m.name ?: "?", m.containingFile.name, isUsed(m)) }
            .sortedWith(compareBy({ it.file }, { it.type }))
    }

    /** Whether a reference to [method] stands outside it (a recursive call does not count). */
    fun isUsed(method: GoMethodDeclaration): Boolean =
        ReferencesSearch.search(method, GlobalSearchScope.projectScope(method.project)).anyMatch { !PsiTreeUtil.isAncestor(method, it.element, false) }

    /** The text ranges to delete, by file: [spec] and [methods], each with its line (and doc comment lines) when it stands alone on it. */
    fun deletions(spec: GoMethodSpec, methods: List<GoMethodDeclaration>): Map<PsiFile, List<TextRange>> {
        val result = LinkedHashMap<PsiFile, MutableList<TextRange>>()
        for (element in listOf<PsiElement>(spec) + methods) {
            val file = element.containingFile
            val text = file.viewProvider.contents
            val start = docStart(text, element.textRange.startOffset)
            result.getOrPut(file) { ArrayList() } += GoSafeDeleteProcessor.lines(text, TextRange(start, element.textRange.endOffset))
        }
        return result
    }

    /** [offset] moved up over the `//` lines directly above it (its doc comment), when it begins its line. */
    private fun docStart(text: CharSequence, offset: Int): Int {
        var lineStart = offset
        while (lineStart > 0 && (text[lineStart - 1] == ' ' || text[lineStart - 1] == '\t')) lineStart--
        if (lineStart > 0 && text[lineStart - 1] != '\n') return offset
        var start = offset
        var line = lineStart
        while (line > 0) {
            val previous = text.lastIndexOf('\n', line - 2) + 1
            val content = text.subSequence(previous, line - 1).trim()
            if (!content.startsWith("//")) break
            start = previous + text.subSequence(previous, line).indexOfFirst { it != ' ' && it != '\t' }
            line = previous
        }
        return start
    }

    /** Deletes [spec] and [methods] in one command named [title]. */
    fun apply(project: Project, spec: GoMethodSpec, methods: List<GoMethodDeclaration>, title: String) {
        val deletions = deletions(spec, methods)
        WriteCommandAction.writeCommandAction(project, *deletions.keys.toTypedArray()).withName(title).run<RuntimeException> {
            val documents = PsiDocumentManager.getInstance(project)
            for ((file, ranges) in deletions) {
                val document = documents.getDocument(file) ?: continue
                documents.doPostponedOperationsAndUnblockDocument(document)
                // merged from the end: the offsets of the ranges before stay right
                for (r in ranges.distinct().sortedByDescending { it.startOffset }) document.deleteString(r.startOffset, r.endOffset)
                documents.commitDocument(document)
            }
        }
    }
}

/**
 * Alt+Enter on a method of an interface of the project: the method goes from the interface and, after a chooser listing the
 * methods of the implementing types (all ticked), the ticked ones go too, unless something calls them: those stay and are named
 * in a hint. One command, one undo.
 */
class GoRemoveInterfaceMethodIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = TEXT

    override fun getText(): String = TEXT

    override fun startInWriteAction(): Boolean = false

    // The action asks in a dialog: the preview must not run it on a copy.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean =
        element.containingFile is GoFile && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, project) && GoRemoveInterfaceMethod.specAt(element) != null

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val spec = GoRemoveInterfaceMethod.specAt(element) ?: return
        val candidates = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<GoRemoveMethodCandidate>, RuntimeException> { ReadAction.compute<List<GoRemoveMethodCandidate>, RuntimeException> { GoRemoveInterfaceMethod.candidates(spec) } },
                "Finding Implementations of ${spec.name}", true, project,
            )
        } catch (_: ProcessCanceledException) {
            return
        }
        val chosen = if (candidates.isEmpty()) emptyList() else choose(project, candidates) ?: return
        val (used, unused) = chosen.partition { it.used }
        GoRemoveInterfaceMethod.apply(project, spec, unused.mapNotNull { it.method.element }, TEXT.replaceFirstChar { it.uppercaseChar() })
        if (used.isNotEmpty() && editor != null && !ApplicationManager.getApplication().isUnitTestMode) {
            HintManager.getInstance().showInformationHint(editor, "Kept because they are called elsewhere: ${used.joinToString(", ")}")
        }
    }

    private fun choose(project: Project, candidates: List<GoRemoveMethodCandidate>): List<GoRemoveMethodCandidate>? {
        chooser?.let { return it(candidates) }
        if (ApplicationManager.getApplication().isUnitTestMode) return candidates
        val dialog = object : ChooseElementsDialog<GoRemoveMethodCandidate>(project, candidates, "Remove Implementations", "Methods to remove with the interface method:", true) {
            override fun getItemText(item: GoRemoveMethodCandidate): String = "${item.type}.${item.name}" + if (item.used) " (called elsewhere: kept)" else ""
            override fun getItemIcon(item: GoRemoveMethodCandidate): Icon = AllIcons.Nodes.Method
            override fun getItemLocation(item: GoRemoveMethodCandidate): String = item.file
            override fun isElementMarkedByDefault(element: GoRemoveMethodCandidate): Boolean = !element.used
        }
        return if (dialog.showAndGet()) dialog.markedElements else null
    }

    companion object {
        const val TEXT: String = "Remove method from interface and all its implementations"

        /** What the chooser answers in tests (the candidates in, the ticked ones out); null: all of them. */
        @TestOnly
        @JvmStatic
        var chooser: ((List<GoRemoveMethodCandidate>) -> List<GoRemoveMethodCandidate>)? = null
    }
}
