package io.github.golangsupport.ide.editor

import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.intentions.GoIntentionText
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.ide.intentions.GoZeroValues
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Surround With (Ctrl+Alt+T) over whole statements: the selection grows to the statements of one list (block or case body) that it
 * touches; a selection inside one line of one statement is left to [GoExpressionSurroundDescriptor].
 */
class GoStatementSurroundDescriptor : SurroundDescriptor {
    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> =
        (file as? GoFile)?.let { GoSurroundSelection.statements(it, startOffset, endOffset)?.toTypedArray<PsiElement>() } ?: PsiElement.EMPTY_ARRAY

    override fun getSurrounders(): Array<Surrounder> = arrayOf(
        GoStatementSurrounder("if", "if  {", caretInHeader = 3),
        GoStatementSurrounder("if / else", "if  {", caretInHeader = 3, withElse = true),
        GoStatementSurrounder("for", "for  {", caretInHeader = 4),
        GoStatementSurrounder("func() { ... }()", "func() {", "()"),
        GoStatementSurrounder("go func() { ... }()", "go func() {", "()"),
        GoStatementSurrounder("defer func() { ... }()", "defer func() {", "()"),
        GoStatementSurrounder("{ ... }", "{"),
    )

    override fun isExclusive(): Boolean = false
}

/** Surround With over one selected expression: `(expr)`, `!(expr)`, `if err != nil` after a call, `for range` over a rangeable value. */
class GoExpressionSurroundDescriptor : SurroundDescriptor {
    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> =
        (file as? GoFile)?.let { GoSurroundSelection.expression(it, startOffset, endOffset) }?.let { arrayOf<PsiElement>(it) } ?: PsiElement.EMPTY_ARRAY

    override fun getSurrounders(): Array<Surrounder> = arrayOf(GoParenthesesSurrounder(false), GoParenthesesSurrounder(true), GoIfErrSurrounder(), GoForRangeSurrounder())

    override fun isExclusive(): Boolean = false
}

internal object GoSurroundSelection {

    /** The selection without the whitespace, inserted semicolons and comments at its ends; null when nothing is left. */
    private fun trim(file: GoFile, start: Int, end: Int): TextRange? {
        var s = start.coerceIn(0, file.textLength)
        var e = end.coerceIn(s, file.textLength)
        while (s < e) {
            val leaf = file.findElementAt(s) ?: break
            if (!GoEditText.isBlank(leaf) && leaf !is PsiComment && !leaf.text.isBlank()) break
            s = leaf.textRange.endOffset
        }
        while (e > s) {
            val leaf = file.findElementAt(e - 1) ?: break
            if (!GoEditText.isBlank(leaf) && leaf !is PsiComment && !leaf.text.isBlank()) break
            e = leaf.textRange.startOffset
        }
        return if (s < e) TextRange(s, e) else null
    }

    fun statements(file: GoFile, start: Int, end: Int): List<GoStatement>? {
        val range = trim(file, start, end) ?: return null
        val firstChain = GoEditText.listStatements(file.findElementAt(range.startOffset) ?: return null)
        val lastChain = GoEditText.listStatements(file.findElementAt(range.endOffset - 1) ?: return null)
        for (first in firstChain) {
            val last = lastChain.firstOrNull { it.parent == first.parent } ?: continue
            if (first.textRange.startOffset > last.textRange.startOffset) return null
            val text = file.node.chars
            // a piece of one line of one statement is an expression to surround, not the statement
            if (first == last && first.textRange != range && text.subSequence(range.startOffset, range.endOffset).none { it == '\n' }) return null
            if (!GoEditText.startsLine(text, first.textRange.startOffset)) return null
            val siblings = GoEditText.statements(first.parent)
            return siblings.subList(siblings.indexOf(first), siblings.indexOf(last) + 1)
        }
        return null
    }

    fun expression(file: GoFile, start: Int, end: Int): GoExpression? {
        val range = trim(file, start, end) ?: return null
        var element: PsiElement? = file.findElementAt(range.startOffset)
        var found: GoExpression? = null
        while (element != null && element !is PsiFile && range.contains(element.textRange)) {
            if (element is GoExpression && element.textRange == range) found = element
            element = element.parent
        }
        return found
    }
}

/** [header] opens the block put around the statements, [tail] follows its `}`; the caret goes [caretInHeader] into the header, or after the whole. */
class GoStatementSurrounder(
    private val title: String,
    private val header: String,
    private val tail: String = "",
    private val caretInHeader: Int? = null,
    private val withElse: Boolean = false,
) : Surrounder {
    override fun getTemplateDescription(): String = title

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.isNotEmpty() && elements.all { it is GoStatement }

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val file = elements.first().containingFile
        val text = file.node.chars
        val start = elements.first().textRange.startOffset
        var end = elements.last().textRange.endOffset
        // a trailing comment of the last statement goes into the block with it
        if (GoEditText.endsLine(text, end)) end = GoEditText.lineEnd(text, end)
        val indent = GoEditText.indentOf(text, start)
        val body = GoEditText.shift(file, GoEditText.lineStart(text, start), end, 1).trimEnd()
        val elseBranch = if (withElse) " else {\n$indent}" else ""
        val result = "$header\n$body\n$indent}$tail$elseBranch"
        GoEditText.apply(file, listOf(GoEditPlan.Edit(GoEditText.lineStart(text, start), end, indent + result)))
        editor.selectionModel.removeSelection()
        val caret = when {
            caretInHeader != null -> start + caretInHeader
            else -> start + result.length
        }
        return TextRange(caret, caret)
    }
}

/** `(expr)`, or `!(expr)` for a boolean (or not yet typed) expression; a parenthesized one is negated as it is. */
class GoParenthesesSurrounder(private val negate: Boolean) : Surrounder {
    override fun getTemplateDescription(): String = if (negate) "!(expr)" else "(expr)"

    override fun isApplicable(elements: Array<out PsiElement>): Boolean {
        val expression = elements.singleOrNull() as? GoExpression ?: return false
        if (!negate) return true
        val type = GoSemanticService.getInstance(expression.project).typeOf(expression).underlying()
        return type == GoUnknownType || type is GoBasicType && (type.kind.isBoolean || type.kind == GoBasicKind.INVALID)
    }

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val expression = elements.single()
        val range = expression.textRange
        val wrapped = when {
            negate && expression is GoParenthesesExpr -> "!${expression.text}"
            negate -> "!(${expression.text})"
            else -> "(${expression.text})"
        }
        GoEditText.apply(expression.containingFile, listOf(GoEditPlan.Edit(range.startOffset, range.endOffset, wrapped)))
        editor.selectionModel.removeSelection()
        val caret = range.startOffset + wrapped.length
        return TextRange(caret, caret)
    }
}

/**
 * `if err != nil { return …, err }` after a call whose last result is an `error`: a call standing alone gets its results named
 * (`v, err := f()`, or `if err := f(); err != nil` when it returns only the error); a call inside a statement whose results are a value
 * and an error moves before it as `v, err := f()` and the check, `v` taking its place. The `return` has the zero values of the
 * enclosing function's results, the error last.
 */
class GoIfErrSurrounder : Surrounder {
    override fun getTemplateDescription(): String = "if err != nil { ... }"

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = (elements.singleOrNull() as? GoCallExpr)?.let { plan(it) } != null

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val call = elements.single() as? GoCallExpr ?: return null
        val file = call.containingFile as GoFile
        val plan = plan(call) ?: return null
        GoEditText.apply(file, plan.edits)
        val document = GoEditText.document(file) ?: return null
        val caret = document.createRangeMarker(plan.caret, plan.caret)
        for (path in plan.imports) GoImportInserter.addImport(file, document, path)
        GoEditText.apply(file, emptyList())
        editor.selectionModel.removeSelection()
        return TextRange(caret.startOffset, caret.startOffset).also { caret.dispose() }
    }

    private class Plan(val edits: List<GoEditPlan.Edit>, val imports: Collection<String>, val caret: Int)

    private fun plan(call: GoCallExpr): Plan? {
        val results = GoSemanticService.getInstance(call.project).calleeSignature(call)?.results ?: return null
        if (results.isEmpty() || !GoZeroValues.isError(results.last().type)) return null
        val file = call.containingFile as? GoFile ?: return null
        val statement = GoEditText.listStatement(call) ?: return null
        val text = file.node.chars
        if (!GoEditText.startsLine(text, statement.textRange.startOffset)) return null
        val indent = GoEditText.indentOf(text, statement.textRange.startOffset)
        val source = GoSourceText(file)
        val check = "if err != nil {\n$indent\t${GoIntentionText.returnStatement(GoIntentionText.enclosingSignature(statement), "err", source)}\n$indent}"
        val start = statement.textRange.startOffset
        if (GoEditText.expressionStatement(call) == statement) {
            val replacement = if (results.size == 1) "if err := ${call.text}; err != nil" + check.removePrefix("if err != nil")
            else "${freshNames(statement, results.size - 1).joinToString(", ")}, err := ${call.text}\n$indent$check"
            return Plan(listOf(GoEditPlan.Edit(start, statement.textRange.endOffset, replacement)), source.imports, start + replacement.length)
        }
        // moving the call out of its statement must not change when or how often it runs
        if (results.size != 2 || statement is GoForStatement || GoPsiUtil.functionOwner(call) != GoPsiUtil.functionOwner(statement)) return null
        val name = freshNames(statement, 1).single()
        val before = "$name, err := ${call.text}\n$indent$check\n$indent"
        val edits = listOf(GoEditPlan.Edit(start, start, before), GoEditPlan.Edit(call.textRange.startOffset, call.textRange.endOffset, name))
        return Plan(edits, source.imports, start + before.length - indent.length - 1)
    }

    /** `v` (or `v1`, `v2`… for several) not spelled anywhere in the enclosing function. */
    private fun freshNames(statement: GoStatement, count: Int): List<String> {
        val owner = GoPsiUtil.functionOwner(statement) ?: statement
        val taken = PsiTreeUtil.collectElements(owner) { it.node.elementType == GoTypes.IDENTIFIER }.mapTo(HashSet()) { it.text }
        val names = ArrayList<String>()
        var i = if (count == 1) 0 else 1
        while (names.size < count) {
            val name = if (i == 0) "v" else "v$i"
            if (name !in taken) names += name
            i++
        }
        return names
    }
}

/** `for … := range expr { }` around an expression standing alone as a statement, the variables chosen by what is ranged over. */
class GoForRangeSurrounder : Surrounder {
    override fun getTemplateDescription(): String = "for range"

    override fun isApplicable(elements: Array<out PsiElement>): Boolean {
        val expression = elements.singleOrNull() as? GoExpression ?: return false
        return GoEditText.expressionStatement(expression) != null && variables(expression) != null
    }

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val expression = elements.single() as? GoExpression ?: return null
        val statement = GoEditText.expressionStatement(expression) ?: return null
        val variables = variables(expression) ?: return null
        val text = expression.containingFile.node.chars
        val indent = GoEditText.indentOf(text, statement.textRange.startOffset)
        val head = if (variables.isEmpty()) "for range ${expression.text} {" else "for $variables := range ${expression.text} {"
        val result = "$head\n$indent\t\n$indent}"
        val start = statement.textRange.startOffset
        GoEditText.apply(expression.containingFile, listOf(GoEditPlan.Edit(start, statement.textRange.endOffset, result)))
        editor.selectionModel.removeSelection()
        val caret = start + head.length + 1 + indent.length + 1
        return TextRange(caret, caret)
    }

    /** The range variables for the type of [expression] ("" for none), or null when it cannot be ranged over. */
    private fun variables(expression: GoExpression): String? = when (val type = GoSemanticService.getInstance(expression.project).typeOf(expression).underlying()) {
        is GoSliceType, is GoArrayType -> "_, v"
        is GoPointerType -> if (type.elem.underlying() is GoArrayType) "_, v" else null
        is GoMapType -> "k, v"
        is GoChanType -> "v"
        is GoBasicType -> when {
            type.kind.isString -> "_, r"
            type.kind.isInteger -> "i"
            else -> null
        }
        // iterator functions: func(yield func(…) bool)
        is GoSignatureType -> (type.params.singleOrNull()?.type?.underlying() as? GoSignatureType)?.takeIf { type.results.isEmpty() }?.let { yield ->
            when (yield.params.size) {
                0 -> ""
                1 -> "v"
                2 -> "k, v"
                else -> null
            }
        }
        else -> null
    }
}
