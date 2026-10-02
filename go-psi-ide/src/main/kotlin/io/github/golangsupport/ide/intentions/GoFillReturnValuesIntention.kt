package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoDiagnosticClasses
import io.github.golangsupport.ide.inspections.GoDiagnosticsCache
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Fill return values: a `return` with fewer values than the enclosing function has results (also where the checker reports
 * "not enough return values") gets the missing ones, where they belong by type: a variable of exactly that type in scope before the
 * statement (the nearest; for `error` the one named `err` first), otherwise the zero value. The values written stay, in order.
 *
 * Inside a function whose body ends without a `return` (the checker's "missing return") the same is offered as "Add missing return":
 * the statement before the closing brace.
 *
 * Not offered for a bare `return` with named results (valid Go) or for `return f()` that forwards a call with all the results.
 */
class GoFillReturnValuesIntention : GoCodeActionIntention() {
    override val defaultText: String = "Fill return values"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val statement = PsiTreeUtil.getParentOfType(leaf, GoReturnStatement::class.java, false)
        // not the `return` around a function literal the caret is in
        if (statement != null && GoPsiUtil.functionOwner(statement) == GoPsiUtil.functionOwner(leaf)) return fill(file, statement)
        return missingReturn(file, leaf)
    }

    private fun fill(file: GoFile, statement: GoReturnStatement): GoEditPlan? {
        val signature = GoIntentionText.enclosingSignature(statement) ?: return null
        val results = signature.results
        val written = statement.expressionList
        if (written.size >= results.size) return null
        if (written.isEmpty() && results.any { it.name != null }) return null
        val service = GoSemanticService.getInstance(file.project)
        val types = written.map { service.typeOf(it) }
        if (written.size == 1 && types[0] is GoTupleType) return null
        val source = GoSourceText(file)
        val used = written.map { it.text }.toHashSet()
        // Each written value takes the first result it fits, in order (or the slot it must take for the rest to fit); the results skipped are filled.
        val values = ArrayList<String>()
        var next = 0
        for (result in results) {
            val writtenLeft = written.size - next
            if (writtenLeft > 0 && (results.size - values.size == writtenLeft || fits(types[next], result.type))) {
                values += written[next++].text
            } else {
                values += valueFor(result.type, statement, source, used)
            }
        }
        val edit = if (written.isEmpty()) {
            val end = statement.`return`.textRange.endOffset
            GoEditPlan.Edit(end, end, " " + values.joinToString(", "))
        } else {
            GoEditPlan.Edit(written.first().textRange.startOffset, written.last().textRange.endOffset, values.joinToString(", "))
        }
        return GoEditPlan(listOf(edit), source.imports)
    }

    private fun fits(value: GoType, target: GoType): Boolean = value is GoUnknownType || GoTypePredicates.assignable(value, target)

    private fun missingReturn(file: GoFile, leaf: PsiElement): GoEditPlan? {
        val owner = GoPsiUtil.functionOwner(leaf) ?: return null
        val body: GoBlock = when (owner) {
            is GoFunctionOrMethodDeclaration -> owner.block
            is GoFunctionLit -> owner.block
            else -> null
        } ?: return null
        val rbrace = body.rbrace ?: return null
        if (!body.textRange.contains(leaf.textRange)) return null
        val signature = GoIntentionText.enclosingSignature(leaf) ?: return null
        if (signature.results.isEmpty() || body.statementList.lastOrNull() is GoReturnStatement) return null
        val missing = GoDiagnosticsCache.diagnostics(file).any { d ->
            d.code in GoDiagnosticClasses.MISSING_RETURN && body.textRange.contains(d.range.startOffset) &&
                file.findElementAt(d.range.startOffset)?.let(GoPsiUtil::functionOwner) == owner
        }
        if (!missing) return null
        val source = GoSourceText(file)
        val used = HashSet<String>()
        val values = signature.results.map { valueFor(it.type, rbrace, source, used) }
        val text = file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, rbrace.textRange.startOffset)
        val edit = GoIntentionText.insertBefore(text, rbrace.textRange.startOffset, indent, listOf("\treturn " + values.joinToString(", ")))
        return GoEditPlan(listOf(edit), source.imports, text = "Add missing return")
    }

    companion object {
        /** A local of exactly [type] before [place] not returned yet (for `error` the one named `err` first), else the zero value. */
        internal fun valueFor(type: GoType, place: PsiElement, source: GoSourceText, used: MutableSet<String>): String {
            val candidates = GoScopeValues.ofType(place, type).mapNotNull { it.name }.filter { it !in used }
            val name = if (GoZeroValues.isError(type)) candidates.firstOrNull { it == "err" } ?: candidates.firstOrNull() else candidates.firstOrNull()
            if (name != null) {
                used += name
                return name
            }
            return source.zero(type)
        }
    }
}
