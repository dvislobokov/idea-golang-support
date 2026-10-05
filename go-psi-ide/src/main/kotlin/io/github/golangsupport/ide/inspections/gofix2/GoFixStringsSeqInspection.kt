package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause

/**
 * modernize `stringsseq` (go1.24): ranging over `strings.Split` / `strings.Fields` (and the `bytes` ones) without the index builds a
 * slice only to walk it: → `SplitSeq` / `FieldsSeq`. Shapes: `for _, x := range strings.Split(s, sep)` (or `for range`), and
 * `parts := strings.Split(…)` right before `for _, p := range parts` when `parts` is used nowhere else.
 */
class GoFixStringsSeqInspection : GoFix2InspectionBase() {
    override val minVersion = "1.24"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoCallExpr) return null
        val callee = element.expression as? GoReferenceExpression ?: return null
        val name = callee.identifier.text
        if (name != "Split" && name != "Fields") return null
        val seq = name + "Seq"
        val direct = (element.parent as? GoRangeClause)?.takeIf { it.expression === element }
        val (clause, moves) = if (direct != null) direct to listOf(GoFixEdit.replace(callee.identifier, seq)) else viaVariable(element, seq) ?: return null
        val rangeVars = rangeVarsEdits(clause) ?: return null
        val key = GoFixPsi.callee(element, name) ?: return null
        if (key != "strings.$name" && key != "bytes.$name") return null
        return GoFixFinding(element, "Ranging over $seq is more efficient", listOf("Replace $name with $seq" to { _ -> moves + rangeVars }),
            callee.identifier.textRange.shiftLeft(element.textRange.startOffset))
    }

    /** `parts := strings.Split(…)` + `for … := range parts`: the declaration goes and the call (renamed) replaces `parts` in the loop header. */
    private fun viaVariable(call: GoCallExpr, seq: String): Pair<GoRangeClause, List<GoFixEdit>>? {
        val declaration = call.parent as? GoShortVarDeclaration ?: return null
        val variable = declaration.varDefinitionList.singleOrNull() ?: return null
        if (declaration.expressionList.singleOrNull() !== call || !GoFixPsi.isStatementList(declaration.parent)) return null
        val loop = generateSequence(declaration.nextSibling) { it.nextSibling }.firstOrNull { it is GoStatement } as? GoForStatement ?: return null
        val clause = loop.rangeClause ?: return null
        val ranged = clause.expression as? GoReferenceExpression ?: return null
        val body = GoFixPsi.enclosingBody(declaration) ?: return null
        if (GoFixPsi.references(body, variable).singleOrNull() !== ranged) return null
        val identifier = (call.expression as GoReferenceExpression).identifier.textRange.shiftLeft(call.textRange.startOffset)
        val renamed = call.text.replaceRange(identifier.startOffset, identifier.endOffset, seq)
        return clause to listOf(GoFixPsi.deleteStatement(declaration), GoFixEdit.replace(ranged, renamed))
    }

    /** The edits making the range variables fit a sequence (`_, x :=` → `x :=`); null when the index is used. */
    private fun rangeVarsEdits(clause: GoRangeClause): List<GoFixEdit>? {
        if (clause.assign != null) return null
        val defs = clause.varDefinitionList
        return when {
            defs.isEmpty() && clause.define == null -> emptyList()
            defs.size == 2 && defs[0].name == "_" && defs[1].name != "_" -> listOf(GoFixEdit.delete(TextRange(defs[0].textRange.startOffset, defs[1].textRange.startOffset)))
            else -> null
        }
    }
}
