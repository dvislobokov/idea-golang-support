package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec

/**
 * Fixes for `declared and not used`:
 *
 * - a single-variable statement (`x := v`, `var x T = v`, `var x T`) whose values have no side
 *   effects (no calls, no receives) is removed;
 * - with side effects it becomes `_ = v`;
 * - one variable of several (`a, x := f()`, `var a, x = ...`, `for i, x := range`) is renamed to
 *   `_` when another variable of the statement keeps a name.
 *
 * `if`/`for`/`switch` header declarations and type-switch variables get no fix.
 */
object GoUnusedVariableFixes {

    fun forDefinition(element: PsiElement): List<LocalQuickFix> {
        val def = element as? GoVarDefinition ?: PsiTreeUtil.getParentOfType(element, GoVarDefinition::class.java, false) ?: return emptyList()
        val name = def.name ?: return emptyList()
        return when (val parent = def.parent) {
            is GoShortVarDeclaration -> {
                val defs = parent.varDefinitionList
                when {
                    defs.size == 1 && isStatementLevel(parent) -> listOf(singleFix(name, parent.expressionList))
                    defs.size > 1 && hasOtherNamed(defs, def) -> listOf(RenameToBlank(name))
                    else -> emptyList()
                }
            }
            is GoVarSpec -> {
                val defs = parent.varDefinitionList
                val declaration = parent.parent as? GoVarDeclaration
                when {
                    defs.size == 1 && declaration != null && declaration.varSpecList.size == 1 && isStatementLevel(declaration) ->
                        listOf(singleFix(name, parent.expressionList))
                    defs.size > 1 -> listOf(RenameToBlank(name))
                    else -> emptyList()
                }
            }
            is GoRangeClause -> if (parent.varDefinitionList.size > 1 && hasOtherNamed(parent.varDefinitionList, def)) listOf(RenameToBlank(name)) else emptyList()
            else -> emptyList()
        }
    }

    private fun singleFix(name: String, values: List<GoExpression>): LocalQuickFix =
        if (values.all(::isPure)) RemoveDeclaration(name) else ReplaceWithBlankAssignment(name)

    private fun hasOtherNamed(defs: List<GoVarDefinition>, def: GoVarDefinition) = defs.any { it != def && it.name != "_" }

    private fun isStatementLevel(statement: PsiElement): Boolean = when (statement.parent) {
        is GoBlock, is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause -> true
        else -> false
    }

    /** No calls (conversions included, conservatively) and no channel receives. */
    private fun isPure(expr: GoExpression): Boolean {
        if (PsiTreeUtil.findChildOfType(expr, GoCallExpr::class.java, false) != null) return false
        return PsiTreeUtil.collectElements(expr) { it is LeafPsiElement && it.text == "<-" }.isEmpty()
    }

    /** The statement declaring [def] (a short variable declaration or a var declaration). */
    private fun statementOf(def: GoVarDefinition): PsiElement? = when (val p = def.parent) {
        is GoShortVarDeclaration -> p
        is GoVarSpec -> p.parent as? GoVarDeclaration
        else -> null
    }

    private fun definition(descriptor: ProblemDescriptor): GoVarDefinition? =
        descriptor.psiElement?.let { it as? GoVarDefinition ?: PsiTreeUtil.getParentOfType(it, GoVarDefinition::class.java, false) }

    class RemoveDeclaration(private val name: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Remove unused variable"
        override fun getName(): String = "Remove variable '$name'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val statement = definition(descriptor)?.let(::statementOf) ?: return
            val file = statement.containingFile
            val document = GoImportEdits.document(file) ?: return
            val text = document.charsSequence
            val range = statement.textRange
            var start = range.startOffset
            while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            var end = range.endOffset
            while (end < text.length && (text[end] == ' ' || text[end] == '\t' || text[end] == ';')) end++
            val removed = if ((start == 0 || text[start - 1] == '\n') && (end == text.length || text[end] == '\n')) {
                TextRange(start, minOf(end + 1, text.length))
            } else {
                range
            }
            document.deleteString(removed.startOffset, removed.endOffset)
            GoImportEdits.commit(file, document)
        }
    }

    class ReplaceWithBlankAssignment(private val name: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Replace unused variable with '_ ='"
        override fun getName(): String = "Replace '$name' with '_ ='"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val def = definition(descriptor) ?: return
            val statement = statementOf(def) ?: return
            val values = when (val p = def.parent) {
                is GoShortVarDeclaration -> p.expressionList
                is GoVarSpec -> p.expressionList
                else -> return
            }
            if (values.isEmpty()) return
            val file = statement.containingFile
            val document = GoImportEdits.document(file) ?: return
            val text = document.charsSequence
            val valuesText = text.subSequence(values.first().textRange.startOffset, values.last().textRange.endOffset)
            document.replaceString(statement.textRange.startOffset, statement.textRange.endOffset, "_ = $valuesText")
            GoImportEdits.commit(file, document)
        }
    }

    class RenameToBlank(private val name: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Rename unused variable to '_'"
        override fun getName(): String = "Rename '$name' to '_'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val def = definition(descriptor) ?: return
            val file = def.containingFile
            val document = GoImportEdits.document(file) ?: return
            document.replaceString(def.textRange.startOffset, def.textRange.endOffset, "_")
            GoImportEdits.commit(file, document)
        }
    }
}
