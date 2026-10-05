package io.github.golangsupport.ide.imports

import com.intellij.codeInsight.daemon.ReferenceImporter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.inspections.GoAddImportFix
import io.github.golangsupport.ide.inspections.GoDiagnosticsCache
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoFile
import java.util.function.BooleanSupplier

/**
 * "Add unambiguous imports on the fly": the platform's `ShowAutoImportPass` asks every error of the visible part of the file; an
 * undefined package qualifier (`strings` of `strings.ToUpper`) with exactly one package that fits gets its import, the way Java does it.
 * While the caret is on the name it is still being typed and nothing is imported (unless the platform allows it).
 */
class GoReferenceImporter : ReferenceImporter {
    override fun isAddUnambiguousImportsOnTheFlyEnabled(file: PsiFile): Boolean =
        file is GoFile && GoIdeOptions.getInstance().importUnambiguousOnTheFly && GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, file.project)

    override fun computeAutoImportAtOffset(editor: Editor, file: PsiFile, offset: Int, allowCaretNearReference: Boolean): BooleanSupplier? {
        val go = file as? GoFile ?: return null
        val caret = editor.caretModel.offset
        val path = unambiguousImport(go, offset, if (allowCaretNearReference) null else caret) ?: return null
        val stamp = editor.document.modificationStamp
        return BooleanSupplier {
            if (!go.isValid || editor.document.modificationStamp != stamp) return@BooleanSupplier false
            WriteCommandAction.runWriteCommandAction(go.project, "Add Import", null, { GoImportEdits.addImport(go, path) }, go)
            true
        }
    }

    companion object {
        /**
         * The one import path that makes the undefined qualifier at [offset] resolve; null when there is no such qualifier, when several
         * packages fit or none, and when [caret] touches the name (it may be half-typed). Read action.
         */
        fun unambiguousImport(file: GoFile, offset: Int, caret: Int?): String? {
            val undefined = GoDiagnosticsCache.diagnostics(file).firstOrNull { it.code == "undefined" && offset >= it.range.startOffset && offset < it.range.endOffset }
                ?: return null
            if (caret != null && caret >= undefined.range.startOffset && caret <= undefined.range.endOffset + 1) return null
            return GoAddImportFix.candidates(file, undefined.range, 2)?.second?.singleOrNull()
        }
    }
}
