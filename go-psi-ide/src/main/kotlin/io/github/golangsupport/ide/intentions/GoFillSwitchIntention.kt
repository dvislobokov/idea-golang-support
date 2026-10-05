package io.github.golangsupport.ide.intentions

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * Fill switch: the missing `case`s of a switch, added after the existing ones and before `default` (which stays last).
 * What is missing comes from [GoSwitchCases] (shared with the `iota` switch inspection and its "Create 'case' clause for values" fix):
 *
 * - An expression switch over a value of a named type with constants of that type in its package (an `iota` enum): a
 *   `case C:` for every constant whose value is not in a case yet (unexported ones only in their own package), in declaration order.
 * - A type switch over a value of a named interface: a case for every type of the project that implements it (`*T` when only
 *   the pointer does), generic types left out; a case naming the type with or without `*` counts as present.
 */
class GoFillSwitchIntention : GoCodeActionIntention() {
    override val defaultText: String = "Fill switch"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val switch = PsiTreeUtil.getParentOfType(leaf, GoExprSwitchStatement::class.java, GoTypeSwitchStatement::class.java) ?: return null
        if (GoPsiUtil.functionOwner(switch) != GoPsiUtil.functionOwner(leaf)) return null
        val missing = GoSwitchCases.of(switch, file) ?: return null
        return GoSwitchCases.plan(file, switch, missing)
    }
}
