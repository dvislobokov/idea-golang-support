package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFallthroughStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoTypePredicates

/** PSI and text helpers of the `if` / `switch` intentions. */
internal object GoIfText {

    /** The `if` whose header (from `if` to its `{`, or its `else` keyword) holds the caret; null inside a body. */
    fun ifAt(file: GoFile, offset: Int): GoIfStatement? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        var e: PsiElement? = leaf
        while (e != null && e !is PsiFile) {
            when {
                e is GoIfStatement -> {
                    val lbrace = e.block?.lbrace ?: return null
                    return e.takeIf { offset >= it.textRange.startOffset && offset <= lbrace.textRange.endOffset }
                }
                e is GoElseStatement && leaf == e.`else` -> return e.parent as? GoIfStatement
                e is GoBlock && leaf != e.lbrace -> return null
                e is GoFunctionLit || e is GoExprCaseClause -> return null
            }
            e = e.parent
        }
        return null
    }

    /** The statements of [block] moved by [delta] levels; text on the `{` line goes at [contentIndent]. */
    fun body(block: GoBlock, delta: Int, contentIndent: String): String? {
        val rbrace = block.rbrace ?: return null
        return GoEditText.body(block.containingFile, block.lbrace.textRange.endOffset, rbrace.textRange.startOffset, delta, contentIndent)
    }

    /** `{`, [body] and `}` at [indent]. */
    fun braces(body: String, indent: String): String = if (body.isEmpty()) "{\n$indent}" else "{\n$body\n$indent}"

    fun unparen(expression: GoExpression): GoExpression {
        var e = expression
        while (e is GoParenthesesExpr) e = e.inner as? GoExpression ?: return e
        return e
    }

    /** Binding strength of [expression] as an operand: `||` 1, `&&` 2, comparisons 3, other binary operators 4, the rest 5. */
    private fun precedence(expression: GoExpression): Int = when (expression) {
        is GoOrExpr -> 1
        is GoAndExpr -> 2
        is GoConditionalExpr -> 3
        is GoBinaryExpr -> 4
        else -> 5
    }

    /** [expression] as an operand of an operator of [context] precedence: parenthesized when it binds weaker. */
    fun operand(expression: GoExpression, context: Int): String = if (precedence(expression) < context) "(${expression.text})" else expression.text

    /** The operands of a chain of the same operator (`a && b && c` → a, b, c); parenthesized ones stay whole. */
    fun chain(expression: GoExpression): List<GoExpression> {
        if (expression !is GoAndExpr && expression !is GoOrExpr) return listOf(expression)
        val binary = expression as GoBinaryExpr
        val right = binary.right ?: return listOf(expression)
        fun part(e: GoExpression) = if (e.javaClass == expression.javaClass) chain(e) else listOf(e)
        return part(binary.left) + part(right)
    }

    /**
     * The negation of [expression] as an operand of [context] precedence: `!x` → `x`, comparisons flipped (`<` → `>=` only for integers
     * and strings: with NaN `!(a < b)` is not `a >= b`), `true` ↔ `false`, De Morgan one level on a top-level `&&` / `||` chain.
     */
    fun negate(expression: GoExpression, context: Int = 0): String {
        val e = unparen(expression)
        return when {
            e is GoUnaryExpr && e.not != null && e.expression != null -> operand(unparen(e.expression!!), context)
            e is GoConditionalExpr && e.right != null -> flipped(e)?.let { "${e.left.text} $it ${e.right!!.text}" } ?: "!(${e.text})"
            context == 0 && (e is GoAndExpr || e is GoOrExpr) -> {
                val joined = if (e is GoAndExpr) 1 else 2
                chain(e).joinToString(if (e is GoAndExpr) " || " else " && ") { negate(it, joined) }
            }
            e is GoReferenceExpression && e.expression == null && e.text == "true" -> "false"
            e is GoReferenceExpression && e.expression == null && e.text == "false" -> "true"
            e is GoBinaryExpr -> "!(${e.text})"
            else -> "!${e.text}"
        }
    }

    private fun flipped(e: GoConditionalExpr): String? = when {
        e.eql != null -> "!="
        e.neq != null -> "=="
        !ordered(e) -> null
        e.lss != null -> ">="
        e.leq != null -> ">"
        e.gtr != null -> "<="
        e.geq != null -> "<"
        else -> null
    }

    /** Both operands are integers or strings: ordering comparisons invert exactly. */
    private fun ordered(e: GoConditionalExpr): Boolean {
        val service = GoSemanticService.getInstance(e.project)
        return e.expressionList.all { operand ->
            val kind = (service.typeOf(operand).underlying() as? GoBasicType)?.kind
            kind != null && (kind.isInteger || kind.isString)
        }
    }

    /** An unlabeled `break` in [element] that targets a statement outside it (it would bind to a new `switch`, or lose its `switch`). */
    fun hasOuterBreak(element: PsiElement): Boolean {
        fun visit(e: PsiElement): Boolean {
            if (e is GoBreakStatement) return e.labelRef == null
            if (e is GoForStatement || e is GoExprSwitchStatement || e is GoTypeSwitchStatement || e is GoSelectStatement || e is GoFunctionLit) return false
            return e.children.any(::visit)
        }
        return element.children.any(::visit)
    }

    /** A comment standing directly in [element] (between its parts, not inside a block): text rewrites would drop it. */
    fun hasOwnComment(element: PsiElement): Boolean = generateSequence(element.firstChild) { it.nextSibling }.any { it is PsiComment }

    /** A variable, a field or a package member: the same value however often it is read. */
    fun isPlainReference(expression: GoExpression?): Boolean =
        expression is GoReferenceExpression && (expression.expression == null || isPlainReference(expression.expression))
}

/** Invert 'if' condition: `if a { X } else { Y }` → `if !a { Y } else { X }`; also offered on the `else` keyword (flip the branches). */
class GoInvertIfIntention : GoCodeActionIntention() {
    override val defaultText: String = "Invert 'if' condition"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val statement = GoIfText.ifAt(file, offset) ?: return null
        val condition = statement.condition ?: return null
        val then = statement.block ?: return null
        val otherwise = statement.elseStatement?.statement as? GoBlock ?: return null
        if (then.rbrace == null || otherwise.rbrace == null) return null
        val edits = listOf(
            GoEditPlan.Edit(condition.textRange.startOffset, condition.textRange.endOffset, GoIfText.negate(condition)),
            GoEditPlan.Edit(then.textRange.startOffset, then.textRange.endOffset, otherwise.text),
            GoEditPlan.Edit(otherwise.textRange.startOffset, otherwise.textRange.endOffset, then.text),
        )
        return GoEditPlan(edits)
    }
}

/**
 * Invert 'if' with early exit: `if a { … }` as the last statement of a function without results → `if !a { return }` and the body one
 * level up; the last statement of a loop body gets `continue`. Not offered with an init statement (its names would leave the body)
 * or when a name the body declares is declared next to the `if` already.
 */
class GoInvertIfEarlyExitIntention : GoCodeActionIntention() {
    override val defaultText: String = "Invert 'if' with early exit"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val statement = GoIfText.ifAt(file, offset) ?: return null
        if (statement.elseStatement != null || statement.initStatement != null) return null
        val condition = statement.condition ?: return null
        val block = statement.block ?: return null
        val list = statement.parent as? GoBlock ?: return null
        if (GoEditText.statements(list).lastOrNull() != statement || block.statementList.isEmpty()) return null
        val owner = list.parent
        val exit = when (owner) {
            is GoFunctionOrMethodDeclaration, is GoFunctionLit -> "return".takeIf { GoIntentionText.enclosingSignature(statement)?.results?.isEmpty() == true }
            is GoForStatement -> "continue"
            else -> null
        } ?: return null
        val inner = block.statementList.flatMap(GoPsiUtil::declarationsOf).mapNotNull { it.name }.toSet()
        val outer = GoEditText.statements(list).filter { it != statement }.flatMap(GoPsiUtil::declarationsOf).mapNotNull { it.name }.toMutableSet()
        if (owner !is GoForStatement) {
            outer += PsiTreeUtil.findChildrenOfAnyType(owner, GoParamDefinition::class.java, GoReceiver::class.java)
                .filter { it.textRange.startOffset < list.textRange.startOffset }.mapNotNull { it.name }
        }
        if (inner.any { it != "_" && it in outer }) return null
        val text = file.viewProvider.contents
        val indent = GoEditText.indentOf(text, statement.textRange.startOffset)
        val body = GoIfText.body(block, -1, indent) ?: return null
        val replacement = "if ${GoIfText.negate(condition)} {\n$indent\t$exit\n$indent}\n$body"
        return GoEditPlan(listOf(GoEditPlan.Edit(statement.textRange.startOffset, statement.textRange.endOffset, replacement)),
            text = "Invert 'if' with early $exit")
    }
}

/** Merge nested 'if': `if a { if b { … } }` (no `else`, nothing else in the outer body) → `if a && b { … }`. */
class GoMergeNestedIfIntention : GoCodeActionIntention() {
    override val defaultText: String = "Merge nested 'if'"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val at = GoIfText.ifAt(file, offset) ?: return null
        val (outer, inner) = pairOf(at) ?: (at.parent?.parent as? GoIfStatement)?.let(::pairOf)?.takeIf { it.second == at } ?: return null
        if (outer.elseStatement != null || inner.elseStatement != null || inner.initStatement != null) return null
        val outerCondition = outer.condition ?: return null
        val innerCondition = inner.condition ?: return null
        val outerBlock = outer.block ?: return null
        if (outerBlock.rbrace == null || GoIfText.hasOwnComment(outerBlock) || GoIfText.hasOwnComment(inner)) return null
        val indent = GoEditText.indentOf(file.viewProvider.contents, outer.textRange.startOffset)
        val body = GoIfText.body(inner.block ?: return null, -1, "$indent\t") ?: return null
        val condition = GoIfText.operand(GoIfText.unparen(outerCondition), 2) + " && " + GoIfText.operand(GoIfText.unparen(innerCondition), 2)
        val replacement = "$condition ${GoIfText.braces(body, indent)}"
        return GoEditPlan(listOf(GoEditPlan.Edit(outerCondition.textRange.startOffset, outer.textRange.endOffset, replacement)))
    }

    /** [outer] and the `if` that is the only statement of its body. */
    private fun pairOf(outer: GoIfStatement): Pair<GoIfStatement, GoIfStatement>? {
        val inner = outer.block?.statementList?.singleOrNull() as? GoIfStatement ?: return null
        return outer to inner
    }
}

/** Split 'if' condition: `if a && b { … }` (no `else`) → `if a { if b { … } }`, split at the `&&` under the caret or the last one. */
class GoSplitIfConditionIntention : GoCodeActionIntention() {
    override val defaultText: String = "Split 'if' condition"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val statement = GoIfText.ifAt(file, offset) ?: return null
        if (statement.elseStatement != null) return null
        val condition = statement.condition as? GoAndExpr ?: return null
        val block = statement.block ?: return null
        val operands = GoIfText.chain(condition)
        if (operands.size < 2) return null
        val leaf = GoIntentionText.leafAt(file, offset)
        val chosen = operands.indices.firstOrNull { i ->
            i < operands.lastIndex && leaf != null && leaf.text == "&&" &&
                leaf.textRange.startOffset >= operands[i].textRange.endOffset && leaf.textRange.endOffset <= operands[i + 1].textRange.startOffset
        } ?: (operands.lastIndex - 1)
        val text = file.viewProvider.contents
        val left = text.subSequence(operands[0].textRange.startOffset, operands[chosen].textRange.endOffset)
        val right = text.subSequence(operands[chosen + 1].textRange.startOffset, operands.last().textRange.endOffset)
        val indent = GoEditText.indentOf(text, statement.textRange.startOffset)
        val body = GoIfText.body(block, 1, "$indent\t\t") ?: return null
        val replacement = "$left {\n$indent\tif $right ${GoIfText.braces(body, "$indent\t")}\n$indent}"
        return GoEditPlan(listOf(GoEditPlan.Edit(condition.textRange.startOffset, statement.textRange.endOffset, replacement)))
    }
}

/**
 * Convert 'if' to 'switch': `if x == 1 {…} else if x == 2 || x == 3 {…} else {…}` over one variable or field → `switch x { case 1: …
 * case 2, 3: … default: … }`. Only `==` against the same plain reference, at least two `if`s, an init statement only on the first one,
 * no duplicate values, no `break` that would bind to the new `switch`, a comparable (and switchable) operand type.
 */
class GoIfToSwitchIntention : GoCodeActionIntention() {
    override val defaultText: String = "Convert 'if' to 'switch'"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        var head = GoIfText.ifAt(file, offset) ?: return null
        while (true) head = (head.parent as? GoElseStatement)?.parent as? GoIfStatement ?: break
        val branches = ArrayList<Pair<GoIfStatement, List<GoExpression>>>()
        var subject: GoExpression? = null
        var otherwise: GoBlock? = null
        var current: GoIfStatement? = head
        while (current != null) {
            if (current != head && current.initStatement != null) return null
            if (GoIfText.hasOwnComment(current) || current.elseStatement?.let(GoIfText::hasOwnComment) == true) return null
            val comparisons = GoIfText.chain(GoIfText.unparen(current.condition ?: return null)).map(GoIfText::unparen)
            if (comparisons.isEmpty() || comparisons.any { it !is GoConditionalExpr || it.eql == null || it.right == null }) return null
            val values = comparisons.map { c ->
                c as GoConditionalExpr
                val left = c.left
                val right = c.right!!
                if (subject == null) subject = if (GoIfText.isPlainReference(left)) left else right.takeIf(GoIfText::isPlainReference) ?: return null
                when (subject!!.text) {
                    left.text -> right
                    right.text -> left
                    else -> return null
                }
            }
            branches += current to values
            if (GoIfText.hasOuterBreak(current.block ?: return null)) return null
            when (val next = current.elseStatement?.statement) {
                is GoIfStatement -> current = next
                is GoBlock -> { otherwise = next; current = null }
                null -> current = null
                else -> return null
            }
        }
        if (branches.size < 2) return null
        val texts = branches.flatMap { it.second }.map { it.text }
        if (texts.size != texts.toSet().size) return null
        otherwise?.let { if (GoIfText.hasOuterBreak(it)) return null }
        val type = GoSemanticService.getInstance(file.project).typeOf(subject!!)
        if (!GoTypePredicates.isKnown(type) || !GoTypePredicates.comparable(type)) return null
        val indent = GoEditText.indentOf(file.viewProvider.contents, head.textRange.startOffset)
        val out = StringBuilder("switch ")
        head.initStatement?.let { out.append(it.text).append("; ") }
        out.append(subject!!.text).append(" {\n")
        for ((statement, values) in branches) {
            out.append(indent).append("case ").append(values.joinToString(", ") { it.text }).append(":\n")
            clause(out, GoIfText.body(statement.block!!, 0, "$indent\t") ?: return null)
        }
        otherwise?.let {
            out.append(indent).append("default:\n")
            clause(out, GoIfText.body(it, 0, "$indent\t") ?: return null)
        }
        out.append(indent).append("}")
        return GoEditPlan(listOf(GoEditPlan.Edit(head.textRange.startOffset, head.textRange.endOffset, out.toString())))
    }

    private fun clause(out: StringBuilder, body: String) {
        if (body.isNotEmpty()) out.append(body).append('\n')
    }
}

/**
 * Convert 'switch' to 'if': an expression switch over a plain reference (or without a tag) → `if x == 1 || x == 2 {…} else if … else {…}`,
 * `default` last as `else` (it runs only when no case matches, wherever it stands). Not offered with `fallthrough` or with a `break`
 * that leaves the switch, or for a tag that is a call (it would be evaluated per comparison).
 */
class GoSwitchToIfIntention : GoCodeActionIntention() {
    override val defaultText: String = "Convert 'switch' to 'if'"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val statement = switchAt(file, offset) ?: return null
        val tag = statement.tag
        if (tag != null && !GoIfText.isPlainReference(tag)) return null
        if (GoIfText.hasOwnComment(statement)) return null
        val clauses = statement.exprCaseClauseList
        val cases = clauses.filter { it.default == null }
        if (cases.isEmpty() || clauses.count { it.default != null } > 1) return null
        for (clause in clauses) {
            if (clause.colon == null || GoIfText.hasOuterBreak(clause)) return null
            if (clause.statementList.any { it is GoFallthroughStatement }) return null
        }
        val indent = GoEditText.indentOf(file.viewProvider.contents, statement.textRange.startOffset)
        val out = StringBuilder()
        for ((i, clause) in cases.withIndex()) {
            val values = clause.expressionList.ifEmpty { return null }
            val condition = if (tag != null) values.joinToString(" || ") { "${tag.text} == ${GoIfText.operand(it, 4)}" }
            else values.joinToString(" || ") { GoIfText.operand(it, 1) }
            out.append(if (i == 0) "if " else " else if ")
            if (i == 0) statement.initStatement?.let { out.append(it.text).append("; ") }
            out.append(condition).append(' ').append(GoIfText.braces(body(clause, indent) ?: return null, indent))
        }
        clauses.firstOrNull { it.default != null }?.let { default ->
            val body = body(default, indent) ?: return null
            if (body.isNotEmpty()) out.append(" else ").append(GoIfText.braces(body, indent))
        }
        return GoEditPlan(listOf(GoEditPlan.Edit(statement.textRange.startOffset, statement.textRange.endOffset, out.toString())))
    }

    private fun body(clause: GoExprCaseClause, indent: String): String? {
        val colon = clause.colon ?: return null
        return GoEditText.body(clause.containingFile, colon.textRange.endOffset, GoEditText.contentEnd(clause), 0, "$indent\t")
    }

    /** The expression switch whose header (from `switch` to `{`) holds the caret. */
    private fun switchAt(file: GoFile, offset: Int): GoExprSwitchStatement? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        var e: PsiElement? = leaf
        while (e != null && e !is PsiFile) {
            if (e is GoExprSwitchStatement) {
                val lbrace = e.lbrace ?: return null
                return e.takeIf { offset >= it.textRange.startOffset && offset <= lbrace.textRange.endOffset }
            }
            if (e is GoExprCaseClause || e is GoBlock || e is GoFunctionLit) return null
            e = e.parent
        }
        return null
    }
}
