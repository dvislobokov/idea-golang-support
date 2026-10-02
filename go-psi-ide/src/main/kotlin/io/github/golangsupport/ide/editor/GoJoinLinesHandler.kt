package io.github.golangsupport.ide.editor

import com.intellij.codeInsight.editorActions.JoinLinesHandlerDelegate.CANNOT_JOIN
import com.intellij.codeInsight.editorActions.JoinRawLinesHandlerDelegate
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Join Lines (Ctrl+Shift+J) for Go: `var x T` + `x = v` → `x := v` (`var x T = v` when `v` alone would give `x` another type);
 * `"a" +` + `"b"` → `"ab"`; the lines of call arguments and composite literal elements join without the space after `(`/`{` and
 * without the trailing comma before `)`/`}`. Anything else is left to the platform.
 */
class GoJoinLinesHandler : JoinRawLinesHandlerDelegate {

    override fun tryJoinLines(document: Document, file: PsiFile, start: Int, end: Int): Int = CANNOT_JOIN

    override fun tryJoinRawLines(document: Document, file: PsiFile, start: Int, end: Int): Int {
        if (file !is GoFile) return CANNOT_JOIN
        val manager = PsiDocumentManager.getInstance(file.project)
        if (!manager.isCommitted(document)) manager.commitDocument(document)
        val text = document.charsSequence
        if (end > text.length || start > end) return CANNOT_JOIN
        // the line break between the two lines; the last code character of the first line and the first one of the second
        val newline = (start until end).firstOrNull { text[it] == '\n' } ?: text.lastIndexOf('\n', end - 1).takeIf { it >= 0 } ?: return CANNOT_JOIN
        var a = newline
        while (a > 0 && (text[a - 1] == ' ' || text[a - 1] == '\t' || text[a - 1] == '\r')) a--
        var b = newline + 1
        while (b < text.length && (text[b] == ' ' || text[b] == '\t')) b++
        if (a == 0 || b >= text.length) return CANNOT_JOIN
        val left = file.findElementAt(a - 1) ?: return CANNOT_JOIN
        val right = file.findElementAt(b) ?: return CANNOT_JOIN
        val edit = declarationAndAssignment(left, right) ?: concatenation(left, right) ?: list(left, right, a, b) ?: return CANNOT_JOIN
        manager.doPostponedOperationsAndUnblockDocument(document)
        document.replaceString(edit.start, edit.end, edit.text)
        return edit.caret
    }

    private class Edit(val start: Int, val end: Int, val text: String, val caret: Int)

    /** `var x T` followed by `x = v`, both in one statement list. */
    private fun declarationAndAssignment(left: PsiElement, right: PsiElement): Edit? {
        val declaration = GoEditText.listStatement(left) as? GoVarDeclaration ?: return null
        if (GoEditText.contentEnd(declaration) != left.textRange.endOffset) return null
        val joined = GoDeclarationJoin.of(declaration) ?: return null
        if (joined.next.textRange.startOffset != right.textRange.startOffset) return null
        return Edit(joined.start, joined.end, joined.text, joined.start + joined.text.length - joined.value.textLength)
    }

    /** `"a" +` at the end of the first line and `"b"` starting the second: one literal, when both are interpreted or both raw. */
    private fun concatenation(left: PsiElement, right: PsiElement): Edit? {
        if (left.node.elementType != GoTypes.ADD) return null
        val sum = left.parent as? GoAddExpr ?: return null
        val second = sum.right as? GoStringLiteral ?: return null
        if (second.textRange.startOffset != right.textRange.startOffset) return null
        val first = when (val l = sum.left) {
            is GoStringLiteral -> l
            is GoAddExpr -> l.right as? GoStringLiteral
            else -> null
        } ?: return null
        val raw = first.rawString != null
        if (raw != (second.rawString != null) || first.text.length < 2 || second.text.length < 2) return null
        val merged = first.text.dropLast(1) + second.text.drop(1)
        val start = first.textRange.startOffset
        return Edit(start, second.textRange.endOffset, merged, start + first.textLength - 1)
    }

    /** The lines of `(…)` arguments or `{…}` literal elements: nothing after the opener, no trailing comma before the closer. */
    private fun list(left: PsiElement, right: PsiElement, a: Int, b: Int): Edit? {
        val leftType = left.node.elementType
        val rightType = right.node.elementType
        val leftList = left.parent?.takeIf { it is GoArgumentList || it is GoLiteralValue }
        val rightList = right.parent?.takeIf { it is GoArgumentList || it is GoLiteralValue }
        val closes = rightList != null && (rightType == GoTypes.RPAREN || rightType == GoTypes.RBRACE)
        return when {
            leftList != null && (leftType == GoTypes.LPAREN || leftType == GoTypes.LBRACE) -> Edit(a, b, "", a)
            leftList != null && leftType == GoTypes.COMMA && closes && rightList == leftList -> Edit(a - 1, b, "", a - 1)
            leftList != null && leftType == GoTypes.COMMA -> Edit(a, b, " ", a + 1)
            closes && left.parent != null && rightList!!.textRange.contains(left.textRange) -> Edit(a, b, "", a)
            else -> null
        }
    }
}

/**
 * `var x T` followed by `x = v` as the next statement of the same list → `x := v`, or `var x T = v` when `v` alone would give `x` another
 * type (`defaultType(typeOf(v))` differs from `T`). Shared by Join Lines and the "Join declaration and assignment" intention.
 */
internal object GoDeclarationJoin {
    class Joined(val start: Int, val end: Int, val text: String, val value: GoExpression, val next: GoStatement)

    fun of(declaration: GoVarDeclaration): Joined? {
        if (declaration.lparen != null || !GoEditText.isStatementList(declaration.parent)) return null
        val spec = declaration.varSpecList.singleOrNull() ?: return null
        val variable = spec.varDefinitionList.singleOrNull() ?: return null
        val type = spec.type ?: return null
        if (spec.expressionList.isNotEmpty()) return null
        val next = PsiTreeUtil.getNextSiblingOfType(declaration, GoStatement::class.java) ?: return null
        // a comment between the two would be lost
        if (generateSequence(declaration.nextSibling) { it.nextSibling }.takeWhile { it != next }.any { !GoEditText.isBlank(it) }) return null
        val assignment = ((next as? GoSimpleStatement)?.statement ?: next) as? GoAssignmentStatement ?: return null
        if (assignment.assignOp.text != "=") return null
        val target = assignment.leftHandExprList.expressionList.singleOrNull() as? GoReferenceExpression ?: return null
        if (target.expression != null || target.identifier?.text != variable.name) return null
        val value = assignment.expressionList.singleOrNull() ?: return null
        val service = GoSemanticService.getInstance(declaration.project)
        val declared = service.declarationType(variable)
        val inferred = service.typeOf(value).let(GoTypePredicates::defaultType)
        val sameType = declared != GoUnknownType && inferred != GoUnknownType && GoTypePredicates.identical(declared, inferred)
        val joined = if (sameType) "${variable.name} := ${value.text}" else "var ${variable.name} ${type.text} = ${value.text}"
        // from the keyword: a comment bound to the declaration stays
        return Joined(declaration.`var`.textRange.startOffset, next.textRange.endOffset, joined, value, next)
    }
}
