package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoBasicType

/**
 * modernize `stringsbuilder`: a local `s := ""` / `var s string` that only grows by `s += x` inside loops and is read once after them
 * → `var s strings.Builder`, `s.WriteString(x)`, `s.String()`. Reported at the first `+=`. Any other use (`&s`, `s = …`, a read inside
 * the loop, a capture by a function literal) keeps the code as is.
 */
class GoFixStringsBuilderInspection : GoFix2InspectionBase() {
    override val minVersion = "1.10"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoAssignmentStatement || element.assignOp.addAssign == null) return null
        val target = element.leftHandExprList.expressionList.singleOrNull() as? GoReferenceExpression ?: return null
        if (target.expression != null || element.expressionList.size != 1) return null
        val variable = GoFixPsi.resolve(target) as? GoVarDefinition ?: return null
        val declaration = declarationOf(variable) ?: return null
        val body = GoFixPsi.enclosingBody(declaration) ?: return null
        val service = GoSemanticService.getInstance(file.project)
        if (service.declarationType(variable) != GoBasicType.STRING) return null
        val refs = GoFixPsi.references(body, variable)
        val (appends, reads) = refs.partition { appendOf(it) != null }
        if (appends.firstOrNull() !== target) return null
        val read = reads.singleOrNull() ?: return null
        if (!isPlainRead(read) || GoFixPsi.enclosingBody(read) != body) return null
        val statements = appends.map { appendOf(it)!! }
        for (s in statements) {
            if (GoFixPsi.enclosingBody(s) != body || s.textRange.startOffset < declaration.textRange.endOffset) return null
            val loop = PsiTreeUtil.getParentOfType(s, GoForStatement::class.java) ?: return null
            if (!body.textRange.contains(loop.textRange) || loop.textRange.contains(read.textRange) || s.textRange.startOffset > read.textRange.startOffset) return null
            val value = s.expressionList.single()
            if ((service.typeOf(value).underlying() as? GoBasicType)?.kind?.isString != true) return null
        }
        val name = variable.name ?: return null
        return GoFixFinding(element, "using string += string in a loop is inefficient", listOf("Replace string += string with strings.Builder" to { s ->
            listOf(GoFixEdit.replace(declaration, "var $name ${s.prefix("strings", "strings")}Builder"), GoFixEdit.replace(read, "$name.String()")) +
                statements.map { GoFixEdit.replace(it, "$name.WriteString(${it.expressionList.single().text})") }
        }), element.assignOp.textRange.shiftLeft(element.textRange.startOffset))
    }

    /** The statement declaring [variable] as an empty string (`s := ""`, `var s string`), alone in it, in a statement list. */
    private fun declarationOf(variable: GoVarDefinition): PsiElement? {
        val statement = when (val p = variable.parent) {
            is GoShortVarDeclaration -> p.takeIf { it.varDefinitionList.size == 1 && isEmptyString(it.expressionList.singleOrNull()) }
            is GoVarSpec -> (p.parent as? GoVarDeclaration)?.takeIf {
                it.varSpecList.size == 1 && it.lparen == null && p.varDefinitionList.size == 1 && p.type?.text == "string" && p.expressionList.isEmpty()
            }
            else -> null
        } ?: return null
        return statement.takeIf { GoFixPsi.isStatementList(it.parent) }
    }

    private fun isEmptyString(e: GoExpression?): Boolean = e is GoStringLiteral && (e.text == "\"\"" || e.text == "``")

    /** The `s += x` statement whose target is [ref]. */
    private fun appendOf(ref: GoReferenceExpression): GoAssignmentStatement? {
        val list = ref.parent as? GoLeftHandExprList ?: return null
        val statement = list.parent as? GoAssignmentStatement ?: return null
        return statement.takeIf { it.assignOp.addAssign != null && list.expressionList.singleOrNull() === ref }
    }

    private fun isPlainRead(ref: GoReferenceExpression): Boolean {
        val parent = ref.parent
        if (parent is GoLeftHandExprList) return false
        if (parent is GoUnaryExpr && parent.and != null) return false
        return true
    }
}
