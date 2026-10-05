package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause

/**
 * `x := x` copying a range variable at the top level of the loop body (go1.22; modernize `forvar`, GoLand's `GoFixForVar`): since Go 1.22
 * every iteration has its own variables. Only range loops that declare their variables with `:=`; every name of the statement must
 * copy a variable of that range clause.
 */
class GoFixForVarInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.22"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoShortVarDeclaration) return null
        val statement = element.parent?.takeIf { it is GoSimpleStatement } ?: element
        val body = statement.parent as? GoBlock ?: return null
        val range = (body.parent as? GoForStatement)?.rangeClause ?: return null
        if (range.define == null) return null
        val loopVars = range.varDefinitionList
        val defs = element.varDefinitionList
        val values = element.expressionList
        if (defs.isEmpty() || defs.size != values.size || GoFixPsi.hasComments(statement)) return null
        for ((def, value) in defs.zip(values)) {
            if (def.name == "_" || GoFixPsi.name(value) != def.name) return null
            val target = GoFixPsi.target(value)
            if (target == null || loopVars.none { it == target }) return null
        }
        return GoFixPlan("copying variable is unneeded", "Remove the redundant copy", listOf(GoFixPsi.replaceStatement(statement, "")))
    }
}
