package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoMulExpr
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSwitchStatement
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/** Why an inline cannot be done; the handler shows the message as an error hint. */
internal class GoInlineRefusal(message: String) : RuntimeException(message)

/** One text replacement of an inline; the edits of a refactoring are applied together, right to left per file. */
internal data class GoTextEdit(val file: PsiFile, val range: TextRange, val text: String)

/** Precedence, parentheses, side effects, name capture and text edits shared by the inline refactorings. */
internal object GoInlineSupport {

    fun refuse(message: String): Nothing = throw GoInlineRefusal(message)

    /** Go's binary precedence (1 `||` … 5 `*`), 6 for unary operators, 7 for primary expressions. */
    fun precedence(e: GoExpression): Int = when (e) {
        is GoOrExpr -> 1
        is GoAndExpr -> 2
        is GoConditionalExpr -> 3
        is GoAddExpr -> 4
        is GoMulExpr -> 5
        is GoUnaryExpr -> 6
        else -> 7
    }

    /**
     * Whether a replacement of precedence [prec] must be parenthesised where [place] stands: as an operand of a tighter (or, on the
     * right, equal) binary operator, under a unary operator (`-(-x)` must not become `--x`), or as the operand of a selector, call,
     * index or type assertion.
     */
    fun needsParens(place: GoExpression, prec: Int): Boolean = when (val parent = place.parent) {
        is GoParenthesesExpr -> false
        is GoBinaryExpr -> prec < precedence(parent) || (prec == precedence(parent) && parent.right === place)
        is GoUnaryExpr -> prec <= 6
        is GoReferenceExpression -> parent.expression === place && prec < 7
        is GoCallExpr -> parent.expression === place && prec < 7
        is GoIndexOrSliceExpr -> parent.expression === place && prec < 7
        is GoTypeAssertionExpr -> parent.expression === place && prec < 7
        else -> false
    }

    /** [text] placed at [place]: parenthesised by precedence, and when a `{` would end an `if`/`for`/`switch` header early. */
    fun placed(place: GoExpression, text: String, prec: Int): String =
        if (needsParens(place, prec) || ('{' in text && inControlHeader(place))) "($text)" else text

    /** Whether [e] stands in the header of an `if`, `for` or `switch`, where `T{` would open the body. */
    private fun inControlHeader(e: PsiElement): Boolean {
        var p: PsiElement? = e.parent
        while (p != null && p !is GoBlock && p !is GoFunctionLit && p !is PsiFile) {
            if (p is GoIfStatement || p is GoForStatement || p is GoSwitchStatement) return true
            if (p is GoExprCaseClause || p is GoTypeCaseClause || p is GoCommClause) return false
            p = p.parent
        }
        return false
    }

    /** A conversion `T(text)`; pointer, channel and function types need `(T)(text)`. */
    fun conversion(typeText: String, text: String): String {
        val t = typeText.trim()
        return if (t.startsWith("*") || t.startsWith("<-") || t.startsWith("func")) "($t)($text)" else "$t($text)"
    }

    /** Whether a value of [value] type must be converted to keep [target] where it is substituted: an untyped constant of its default type needs nothing. */
    fun needsConversion(value: GoType, target: GoType): Boolean {
        if (GoTypePredicates.identical(value, target)) return false
        if (GoTypePredicates.isUntyped(value) && GoTypePredicates.identical(GoTypePredicates.defaultType(value), target)) return false
        return true
    }

    // --- what an expression does ---

    /** A call that is not a conversion, or a channel receive: evaluating the expression twice or later is observable. */
    fun hasSideEffects(e: PsiElement): Boolean {
        val service = GoSemanticService.getInstance(e.project)
        val calls = PsiTreeUtil.findChildrenOfType(e, GoCallExpr::class.java) + listOfNotNull(e as? GoCallExpr)
        if (calls.any { !isConversion(it, service) }) return true
        return PsiTreeUtil.collectElements(e) { it is LeafPsiElement && it.text == "<-" }.isNotEmpty()
    }

    private fun isConversion(call: GoCallExpr, service: GoSemanticService): Boolean {
        var callee: GoExpression? = call.expression
        while (callee is GoParenthesesExpr) callee = PsiTreeUtil.getChildOfType(callee, GoExpression::class.java)
        val ref = callee as? GoReferenceExpression ?: return callee == null
        return service.resolve(ref).let { it.isNotEmpty() && it.all { t -> t is GoTypeSpec } }
    }

    /**
     * Whether each evaluation of [e] makes a new object that can be told apart (a slice, map or channel literal, `&…`, a function
     * literal): two copies of it are not the one value the variable held.
     */
    fun createsIdentity(e: PsiElement): Boolean {
        if (PsiTreeUtil.findChildOfType(e, GoFunctionLit::class.java, false) != null) return true
        val service = GoSemanticService.getInstance(e.project)
        val units = PsiTreeUtil.findChildrenOfType(e, GoUnaryExpr::class.java) + listOfNotNull(e as? GoUnaryExpr)
        if (units.any { it.and != null }) return true
        val literals = PsiTreeUtil.findChildrenOfType(e, GoCompositeLit::class.java) + listOfNotNull(e as? GoCompositeLit)
        return literals.any { service.typeOf(it).underlying().let { t -> t is GoSliceType || t is GoMapType || t is GoChanType } }
    }

    /** Unqualified references of [e] that name something declared outside it (struct keys and the locals of nested literals left out). */
    fun freeReferences(e: PsiElement): List<GoReferenceExpression> {
        val service = GoSemanticService.getInstance(e.project)
        val all = PsiTreeUtil.findChildrenOfType(e, GoReferenceExpression::class.java) + listOfNotNull(e as? GoReferenceExpression)
        return all.filter { ref ->
            if (ref.expression != null) return@filter false
            val targets = service.resolve(ref)
            if (ref.parent is GoKey && targets.any { it is GoFieldDefinition }) return@filter false
            targets.none { PsiTreeUtil.isAncestor(e, it, false) }
        }
    }

    /**
     * Whether every name of [refs] means the same at [place] as where it is written now: the same declaration, or an import of the
     * same path in another file. Returns the first name that does not, or null.
     */
    fun capturedName(refs: List<GoReferenceExpression>, place: PsiElement, skip: (PsiElement) -> Boolean = { false }): String? {
        val service = GoSemanticService.getInstance(place.project)
        for (ref in refs) {
            val name = ref.identifier.text
            val target = service.resolve(ref).firstOrNull() ?: return name
            if (skip(target)) continue
            val there = GoScopes.resolveName(place, name).map { it.element }
            val same = there.any { it == target || (it is GoImportSpec && target is GoImportSpec && it.path == target.path && !it.isDot && !target.isDot) }
            if (!same) return name
        }
        return null
    }

    /** Whether [ref] (through selectors and indices of non-reference types) is the target of an assignment, `++`/`--`, or a `range`/receive `=`. */
    fun isWritten(ref: GoReferenceExpression): Boolean {
        val service = GoSemanticService.getInstance(ref.project)
        var child: GoExpression = ref
        while (true) {
            when (val p = child.parent) {
                is GoParenthesesExpr -> child = p
                is GoReferenceExpression, is GoIndexOrSliceExpr -> {
                    if ((p as? GoReferenceExpression)?.expression !== child && (p as? GoIndexOrSliceExpr)?.expression !== child) return false
                    // writing a field or an element through a pointer, slice or map does not change the variable itself
                    val t = service.typeOf(child).underlying()
                    if (t is io.github.golangsupport.semantic.types.GoPointerType || t is GoSliceType || t is GoMapType) return false
                    child = p as GoExpression
                }
                is GoLeftHandExprList -> return p.parent !is io.github.golangsupport.lang.psi.GoSimpleStatement && p.parent !is io.github.golangsupport.lang.psi.GoSendStatement
                else -> return false
            }
        }
    }

    /** Whether [ref] is the operand of `&` (possibly through selectors and indices): its address escapes. */
    fun isAddressTaken(ref: GoReferenceExpression): Boolean {
        var child: GoExpression = ref
        while (true) {
            when (val p = child.parent) {
                is GoParenthesesExpr -> child = p
                is GoReferenceExpression -> if (p.expression === child) child = p else return false
                is GoIndexOrSliceExpr -> if (p.expression === child) child = p else return false
                is GoUnaryExpr -> return p.and != null
                else -> return false
            }
        }
    }

    /** Whether [ref] is the receiver operand of a method with a pointer receiver while its own type is not a pointer: the call takes `&ref`. */
    fun callsPointerMethod(ref: GoReferenceExpression): Boolean {
        val selector = ref.parent as? GoReferenceExpression ?: return false
        if (selector.expression !== ref) return false
        val service = GoSemanticService.getInstance(ref.project)
        val method = service.resolve(selector).firstOrNull() as? io.github.golangsupport.lang.psi.GoMethodDeclarationBase ?: return false
        return method.isPointerReceiver && service.typeOf(ref).underlying() !is io.github.golangsupport.semantic.types.GoPointerType
    }

    // --- edits ---

    /** [range] widened to whole lines when nothing else is on them, so removing a declaration leaves no blank line behind. */
    fun lineRange(text: CharSequence, range: TextRange): TextRange {
        var start = range.startOffset
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        var end = range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t' || text[end] == ';')) end++
        if (!((start == 0 || text[start - 1] == '\n') && (end == text.length || text[end] == '\n'))) return range
        end = minOf(end + 1, text.length)
        // a declaration between blank lines takes one of them along
        val blankBefore = start == 0 || (start >= 2 && text[start - 2] == '\n')
        if (blankBefore && end < text.length && text[end] == '\n') end++
        return TextRange(start, end)
    }

    /** Removes [element], one of a comma-separated list, with the comma and blanks that separate it from its neighbour. */
    fun listItemRange(element: PsiElement, siblings: List<PsiElement>): TextRange {
        val text = element.containingFile.viewProvider.contents
        val i = siblings.indexOf(element)
        return if (i < siblings.size - 1) TextRange(element.textRange.startOffset, siblings[i + 1].textRange.startOffset)
        else {
            var start = siblings[i - 1].textRange.endOffset
            while (start < element.textRange.startOffset && text[start] != ',') start++
            TextRange(start, element.textRange.endOffset)
        }
    }

    /** Applies [edits] in one command named [title]; ranges are taken before any change. */
    fun apply(project: Project, title: String, edits: List<GoTextEdit>) {
        val files = edits.map { it.file }.distinct()
        WriteCommandAction.writeCommandAction(project, *files.toTypedArray()).withName(title).run<RuntimeException> {
            for (file in files) {
                val document = GoImportEdits.document(file) ?: continue
                for (e in edits.filter { it.file == file }.sortedByDescending { it.range.startOffset }) {
                    document.replaceString(e.range.startOffset, e.range.endOffset, e.text)
                }
                GoImportEdits.commit(file, document)
            }
        }
    }
}
