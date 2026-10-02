package io.github.golangsupport.ide.editor.paste

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.ide.util.ChooseElementsDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.icons.AllIcons
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import javax.swing.Icon

/**
 * `copyPastePostProcessor`: imports follow pasted Go code. On copy from a Go file the qualifiers of the copied ranges (`json` of
 * `json.Marshal`, also in types) are recorded with the import they resolved to; on paste into another Go file the imports the pasted
 * qualifiers miss are added, with the alias they had, at the place [GoImportInserter] (and later the import sorter) gives them.
 * A qualifier that already means something in the target (an import of that name, a variable) is left alone, and so is a path the
 * target imports under another name: the pasted code would have to be rewritten for it.
 *
 * Text without the copy record (from outside the IDE or another language) is resolved by [GoPasteImportResolver]s, for the
 * qualifiers that resolve to nothing. Settings | Editor | General | Auto Import, "Insert imports on paste" decides: ask, all, none.
 */
class GoPasteImportsProcessor : CopyPastePostProcessor<GoPasteImportsData>() {

    override fun collectTransferableData(file: PsiFile, editor: Editor, startOffsets: IntArray, endOffsets: IntArray): List<GoPasteImportsData> {
        if (file !is GoFile || DumbService.isDumb(file.project)) return emptyList()
        val imports = LinkedHashMap<String, GoCopiedImport>()
        for (i in startOffsets.indices) {
            for (q in qualifiers(file, TextRange(startOffsets[i], endOffsets[i]))) {
                val name = q.identifier.text
                if (name in imports) continue
                val spec = resolve(q) as? GoImportSpec ?: continue
                if (spec.isDot || spec.isBlank) continue
                imports[name] = GoCopiedImport(name, spec.path, spec.alias)
            }
        }
        return listOf(GoPasteImportsData(file.virtualFile?.url, imports.values.toList()))
    }

    override fun extractTransferableData(content: Transferable): List<GoPasteImportsData> {
        if (content.isDataFlavorSupported(GoPasteImportsData.FLAVOR)) {
            return listOfNotNull(runCatching { content.getTransferData(GoPasteImportsData.FLAVOR) as? GoPasteImportsData }.getOrNull())
        }
        return if (content.isDataFlavorSupported(DataFlavor.stringFlavor) && GoPasteImportResolver.EP_NAME.hasAnyExtensions()) listOf(GoPasteImportsData.EXTERNAL)
        else emptyList()
    }

    override fun processTransferableData(
        project: Project, editor: Editor, bounds: RangeMarker, caretOffset: Int, indented: Ref<in Boolean>, values: List<GoPasteImportsData>,
    ) {
        val setting = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE
        if (setting == CodeInsightSettings.NO || DumbService.isDumb(project)) return
        val data = values.singleOrNull() ?: return
        val document = editor.document
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitDocument(document)
        val file = documents.getPsiFile(document) as? GoFile ?: return
        if (!data.external && data.sourceFileUrl != null && data.sourceFileUrl == file.virtualFile?.url) return
        if (!bounds.isValid) return
        val range = bounds.textRange
        val wanted = if (data.external) external(file, range) else missing(file, range, data.imports)
        if (wanted.isEmpty()) return
        val chosen = if (setting == CodeInsightSettings.YES || ApplicationManager.getApplication().isUnitTestMode) wanted else ask(project, wanted)
        if (chosen.isEmpty()) return
        WriteCommandAction.runWriteCommandAction(project, "Add Imports on Paste", null, {
            for (import in chosen) {
                GoImportInserter.addImport(file, document, import.path, import.alias)
                documents.commitDocument(document)
            }
        }, file)
    }

    private fun ask(project: Project, imports: List<GoCopiedImport>): List<GoCopiedImport> {
        val dialog = object : ChooseElementsDialog<GoCopiedImport>(project, imports, "Select Imports to Add", "The pasted code uses these packages:", true) {
            override fun getItemText(item: GoCopiedImport): String = (item.alias?.let { "$it " } ?: "") + "\"" + item.path + "\""
            override fun getItemIcon(item: GoCopiedImport): Icon? = AllIcons.Nodes.Package
            override fun canElementsBeMarked(): Boolean = true
        }
        return if (dialog.showAndGet()) dialog.markedElements else emptyList()
    }

    companion object {
        /** The imports of [copied] that the qualifiers pasted into [range] of [file] need and do not have. */
        fun missing(file: GoFile, range: TextRange, copied: List<GoCopiedImport>): List<GoCopiedImport> {
            if (copied.isEmpty()) return emptyList()
            val byName = copied.associateBy { it.name }
            val imported = file.imports.mapTo(HashSet()) { it.path }
            val result = LinkedHashMap<String, GoCopiedImport>()
            for (q in qualifiers(file, range)) {
                val import = byName[q.identifier.text] ?: continue
                if (import.name in result || import.path in imported || resolve(q) != null) continue
                result[import.name] = import
            }
            return result.values.toList()
        }

        /** Imports for the unresolved qualifiers of [range], from the [GoPasteImportResolver]s. */
        fun external(file: GoFile, range: TextRange): List<GoCopiedImport> {
            val resolvers = GoPasteImportResolver.EP_NAME.extensionList
            if (resolvers.isEmpty()) return emptyList()
            val members = LinkedHashMap<String, MutableSet<String>>()
            for (q in qualifiers(file, range)) {
                if (resolve(q) != null) continue
                members.getOrPut(q.identifier.text) { LinkedHashSet() } += memberOf(q) ?: continue
            }
            val imported = file.imports.mapTo(HashSet()) { it.path }
            return members.mapNotNull { (name, names) ->
                val path = resolvers.firstNotNullOfOrNull { it.importPathFor(file, name, names) } ?: return@mapNotNull null
                if (path in imported) null else GoCopiedImport(name, path, null)
            }
        }

        /** Package qualifiers inside [range]: `fmt` of `fmt.Println` and of the type `http.Request`. */
        fun qualifiers(file: GoFile, range: TextRange): List<GoReferenceExpression> {
            if (range.isEmpty) return emptyList()
            val start = file.findElementAt(range.startOffset) ?: return emptyList()
            val end = file.findElementAt(maxOf(range.startOffset, range.endOffset - 1)) ?: return emptyList()
            val root: PsiElement = PsiTreeUtil.findCommonParent(start, end) ?: file
            val candidates = PsiTreeUtil.findChildrenOfType(root, GoReferenceExpression::class.java).toMutableList()
            if (root is GoReferenceExpression) candidates += root
            return candidates.filter { it.expression == null && range.contains(it.textRange) && memberOf(it) != null }
        }

        /** The name selected through the qualifier [q] (`Println` of `fmt.Println`), or null when [q] is not a qualifier. */
        private fun memberOf(q: GoReferenceExpression): String? = when (val parent = q.parent) {
            is GoReferenceExpression -> if (parent.expression === q) parent.identifier.text else null
            is GoTypeReferenceExpression -> if (parent.referenceExpression === q) parent.identifier.text else null
            else -> null
        }

        private fun resolve(q: GoReferenceExpression): PsiElement? = GoSemanticService.getInstance(q.project).resolve(q).firstOrNull()
    }
}
