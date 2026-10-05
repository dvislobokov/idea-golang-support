package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoInterfaceType

/** `interface{}` → `any` (go1.18; modernize `efaceany`). Only the empty interface without comments, and only where `any` is the predeclared one. */
class GoFixAnyInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.18"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoInterfaceType || element.methodSpecList.isNotEmpty() || element.constraintElemList.isNotEmpty()) return null
        if (element.rbrace == null || GoFixPsi.hasComments(element) || !GoFixPsi.isUniverse(element, "any")) return null
        return GoFixPlan("interface{} can be replaced by any", "Replace 'interface{}' with 'any'", listOf(GoFixPsi.replace(element, "any")))
    }
}
