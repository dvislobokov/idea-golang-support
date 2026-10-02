package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoSwitchCases
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement

/**
 * `switch` over an enum (a named type with constants of that type in its package) or a type switch over an interface of the project
 * that does not name every member (`exhaustive` analyzer rules): reported on the `switch` keyword when there is no `default` (the
 * option also reports switches with one). Constants with equal values are one member, unexported constants of another package are
 * not members, bit-flag enums are skipped. Interfaces of libraries and the standard library are skipped: their implementations in the
 * project are never the whole set. Fix: "Add missing cases" (the cases of Fill Switch).
 */
class GoExhaustiveSwitchInspection : GoAnalysisInspectionBase() {
    @JvmField
    var reportWithDefault: Boolean = false

    override fun getOptionsPane(): OptPane = OptPane.pane(OptPane.checkbox("reportWithDefault", "Report switches that have a 'default' case"))

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val keyword = when (element) {
            is GoExprSwitchStatement -> element.switch
            is GoTypeSwitchStatement -> element.switch
            else -> return
        }
        val missing = GoSwitchCases.of(element, file) { isProjectType(it) } ?: return
        if (missing.cases.isEmpty() || missing.flags || (missing.hasDefault && !reportWithDefault)) return
        holder.registerProblem(keyword, message(missing), GoAddMissingCasesFix())
    }

    private fun isProjectType(spec: GoTypeSpec): Boolean {
        val file = spec.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(spec.project).isInContent(file)
    }

    companion object {
        /** `Missing cases in switch of type Color: Red, Green, Blue and 2 more`. */
        fun message(missing: GoSwitchCases.Missing): String {
            val shown = missing.cases.take(3).joinToString(", ")
            val more = missing.cases.size - 3
            return "Missing cases in switch of type ${missing.typeName}: $shown" + if (more > 0) " and $more more" else ""
        }
    }
}

/** Inserts the missing cases before `default` (or the closing brace), computed again at apply time. */
class GoAddMissingCasesFix : LocalQuickFix {
    override fun getFamilyName(): String = "Add missing cases"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val switch = descriptor.psiElement?.parent ?: return
        val file = switch.containingFile as? GoFile ?: return
        val missing = GoSwitchCases.of(switch, file) ?: return
        val plan = GoSwitchCases.plan(file, switch, missing) ?: return
        GoSwitchCases.apply(file, plan)
    }
}
