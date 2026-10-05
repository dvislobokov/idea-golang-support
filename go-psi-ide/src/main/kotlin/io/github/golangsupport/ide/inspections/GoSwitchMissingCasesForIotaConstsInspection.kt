package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEnumConstants
import io.github.golangsupport.ide.intentions.GoSwitchCases
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile

/**
 * GoLand's "Missing 'case' statements for 'iota' consts in 'switch'" (`GoSwitchMissingCasesForIotaConsts`): an expression `switch` over a
 * named type without `default` that does not handle every constant of that type declared in a const block using `iota` (the whole block:
 * a spec without `iota` counts too, as GoLand's description says). Reported on the `switch` keyword with GoLand's text. Constants with
 * equal values are one member, unexported constants of another package are not members, bit flags (`1 << iota`) count too (seen live on GoLand).
 * Non-`iota` enums and type switches over interfaces are left to Fill Switch: GoLand reports neither. Fixes: one `case` clause with the
 * missing values, or an empty `default`.
 */
class GoSwitchMissingCasesForIotaConstsInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoExprSwitchStatement) return
        val missing = missing(element, file) ?: return
        // bit flags (`1 << iota`) are reported too, seen live on GoLand 2026.2.3
        if (missing.cases.isEmpty() || missing.hasDefault) return
        holder.registerProblem(element.switch, MESSAGE, GoCreateCaseClauseFix(), GoCreateDefaultClauseFix())
    }

    companion object {
        const val MESSAGE: String = "Missing 'case' statements for 'iota' consts in 'switch'"

        fun missing(switch: PsiElement, file: GoFile): GoSwitchCases.Missing? = GoSwitchCases.of(switch, file, constantFilter = GoEnumConstants::inIotaBlock)
    }
}

/** Inserts one `case X:` clause per missing constant before the closing brace (as GoLand's "Create missing iota clauses"), computed again at apply time. */
class GoCreateCaseClauseFix : LocalQuickFix {
    override fun getFamilyName(): String = "Create missing iota clauses"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val switch = descriptor.psiElement?.parent ?: return
        val file = switch.containingFile as? GoFile ?: return
        val missing = GoSwitchMissingCasesForIotaConstsInspection.missing(switch, file) ?: return
        val plan = GoSwitchCases.plan(file, switch, missing) ?: return
        GoSwitchCases.apply(file, plan)
    }
}

/** Adds `default:` with `panic("unhandled default case")` before the closing brace of the switch, as GoLand does. */
class GoCreateDefaultClauseFix : LocalQuickFix {
    override fun getFamilyName(): String = "Create 'default' clause"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val switch = descriptor.psiElement?.parent ?: return
        val file = switch.containingFile as? GoFile ?: return
        val plan = GoSwitchCases.defaultPlan(file, switch, body = listOf("	panic(\"unhandled default case\")")) ?: return
        GoSwitchCases.apply(file, plan)
    }
}
