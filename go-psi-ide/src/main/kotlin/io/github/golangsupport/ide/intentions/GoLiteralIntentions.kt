package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.types.GoStructType

/** Struct literals for the literal intentions. */
internal object GoLiteralText {

    /** The struct type of [literal] (through a named type), or null. */
    fun structOf(literal: GoCompositeLit): GoStructType? = GoSemanticService.getInstance(literal.project).typeOf(literal).underlying() as? GoStructType

    /** The field name of a keyed [element] (`A: 1`), or null. */
    fun keyName(element: GoElement): String? {
        if (element.colon == null) return null
        val ref = element.key?.expression as? GoReferenceExpression ?: return null
        return if (ref.expression == null) ref.identifier.text else null
    }

    /** [values] as the new content of [value]: on one line, or one per line (with a trailing comma) when the literal spans lines. */
    fun rewrite(value: GoLiteralValue, values: List<String>): GoEditPlan.Edit? {
        val rbrace = value.rbrace ?: return null
        val file = value.containingFile
        val text = file.viewProvider.contents
        val range = value.textRange
        val multiline = text.subSequence(value.lbrace.textRange.endOffset, rbrace.textRange.startOffset).contains('\n')
        val content = if (multiline && values.isNotEmpty()) {
            val indent = GoEditText.indentOf(text, rbrace.textRange.startOffset)
            "{\n" + values.joinToString("") { "$indent\t$it,\n" } + "$indent}"
        } else "{" + values.joinToString(", ") + "}"
        return GoEditPlan.Edit(range.startOffset, range.endOffset, content)
    }

    fun hasComment(element: PsiElement): Boolean = PsiTreeUtil.findChildrenOfType(element, PsiComment::class.java).isNotEmpty()

    /** Whether [element] reads the plain name [name]. */
    fun mentions(element: PsiElement, name: String): Boolean =
        (element is GoReferenceExpression && element.expression == null && element.identifier.text == name) || GoDeclarationView.mentions(element, setOf(name))
}

/**
 * Remove keys from struct literal: `T{B: "a", A: 2}` → `T{2, "a", nil}`: the values in the order of the fields, the omitted fields as
 * their zero values (GoLand's behaviour). Not for an empty or partly positional literal, nor a struct of another package with unexported fields.
 */
class GoRemoveKeysFromStructLiteralIntention : GoCodeActionIntention() {
    override val defaultText: String = "Remove keys from struct literal"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val literal = literalAt(file, offset) ?: return null
        val value = literal.literalValue ?: return null
        val elements = value.elements.ifEmpty { return null }
        if (GoLiteralText.hasComment(value)) return null
        val struct = GoLiteralText.structOf(literal) ?: return null
        val source = GoSourceText(file)
        if (struct.fields.any { !it.isExported && !source.isOwnPackage(it.declaration) }) return null
        val keyed = LinkedHashMap<String, String>()
        for (element in elements) {
            val name = GoLiteralText.keyName(element) ?: return null
            if (struct.field(name) == null || keyed.put(name, element.value?.text ?: return null) != null) return null
        }
        val values = struct.fields.map { keyed[it.name] ?: source.zero(it.type) }
        val edit = GoLiteralText.rewrite(value, values) ?: return null
        return GoEditPlan(listOf(edit), source.imports)
    }

    private fun literalAt(file: GoFile, offset: Int): GoCompositeLit? {
        var e: PsiElement? = GoIntentionText.leafAt(file, offset)
        while (e != null && e !is PsiFile) {
            if (e is GoCompositeLit) return e
            if (e is GoStatement || e is GoBlock || e is GoFunctionLit) return null
            e = e.parent
        }
        return null
    }
}

/**
 * Move field assignment to struct initialization: `s := S{A: 1}` followed by `s.B = 2` (the caret on it, the assignments between the
 * declaration and it consecutive and of the same variable) → `s := S{A: 1, B: 2}`, the assignments removed. Also `var s = S{}` and
 * `&S{}`. Not when a value reads the variable, a field is set twice or already keyed, or the field is promoted from an embedded one.
 */
class GoMoveToStructInitializationIntention : GoCodeActionIntention() {
    override val defaultText: String = "Move field assignment to struct initialization"

    private class Assignment(val statement: GoStatement, val variable: String, val field: String, val value: GoExpression)

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val statement = GoIntentionText.statementAt(leaf) ?: return null
        val at = assignmentOf(statement) ?: return null
        val statements = GoEditText.statements(statement.parent ?: return null)
        var index = statements.indexOf(statement)
        val moved = ArrayDeque(listOf(at))
        while (--index >= 0) moved.addFirst(assignmentOf(statements[index])?.takeIf { it.variable == at.variable } ?: break)
        if (index < 0) return null
        val literal = literalOf(statements[index], at.variable) ?: return null
        val value = literal.literalValue ?: return null
        if (GoLiteralText.hasComment(value)) return null
        val struct = GoLiteralText.structOf(literal) ?: return null
        val existing = value.elements
        val names = HashSet<String>()
        for (element in existing) names += GoLiteralText.keyName(element) ?: return null
        for (a in moved) {
            if (struct.field(a.field) == null || !names.add(a.field) || GoLiteralText.mentions(a.value, a.variable)) return null
        }
        val values = existing.map { it.text } + moved.map { "${it.field}: ${it.value.text}" }
        val edits = listOfNotNull(GoLiteralText.rewrite(value, values)) + moved.map { GoEditText.replaceStatement(it.statement, "") }
        return GoEditPlan(edits)
    }

    /** `v.F = value` (one plain variable, one field, plain `=`). */
    private fun assignmentOf(statement: GoStatement): Assignment? {
        val assignment = ((statement as? GoSimpleStatement)?.statement ?: statement) as? GoAssignmentStatement ?: return null
        if (assignment.assignOp.assign == null) return null
        val target = assignment.leftHandExprList.expressionList.singleOrNull() as? GoReferenceExpression ?: return null
        val variable = target.expression as? GoReferenceExpression ?: return null
        if (variable.expression != null) return null
        val value = assignment.expressionList.singleOrNull() ?: return null
        return Assignment(statement, variable.identifier.text, target.identifier.text, value)
    }

    /** The struct literal [statement] initializes [variable] with: `v := T{…}`, `var v = T{…}`, either with `&`. */
    private fun literalOf(statement: GoStatement, variable: String): GoCompositeLit? {
        val value = when (val s = (statement as? GoSimpleStatement)?.statement ?: statement) {
            is GoShortVarDeclaration -> s.expressionList.singleOrNull()?.takeIf { s.varDefinitionList.singleOrNull()?.name == variable }
            is GoVarDeclaration -> s.varSpecList.singleOrNull()?.takeIf { it.varDefinitionList.singleOrNull()?.name == variable }?.expressionList?.singleOrNull()
            else -> null
        } ?: return null
        val inner = GoIfText.unparen(value)
        return ((inner as? GoUnaryExpr)?.takeIf { it.and != null }?.expression ?: inner) as? GoCompositeLit
    }
}
