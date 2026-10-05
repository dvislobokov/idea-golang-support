package io.github.golangsupport.ide.duplicates

import com.intellij.dupLocator.DefaultDuplocatorState
import com.intellij.dupLocator.ExternalizableDuplocatorState
import com.intellij.dupLocator.MultilanguageDuplocatorSettings
import com.intellij.dupLocator.PsiElementRole
import com.intellij.dupLocator.treeHash.DuplicatesProfileBase
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition

/**
 * Analyze | Locate Duplicates for Go (`duplicates.profile`, a module of the commercial IDEs): the tree hasher of the platform over
 * the Go PSI. Statements cost 2 and expressions 1, so the lower bound of the dialog (its "tolerance") counts roughly half-statements;
 * the names of variables, fields and functions and the literals are anonymized as the dialog's checkboxes say, so two functions that
 * differ only in their identifiers are duplicates.
 */
class GoDuplicatesProfile : DuplicatesProfileBase() {
    override fun isMyLanguage(language: Language): Boolean = language.isKindOf(GoLanguage)

    override fun getNodeCost(element: PsiElement): Int = when (element) {
        is GoBlock, is GoSimpleStatement, is GoFunctionOrMethodDeclaration -> 0
        is GoStatement -> 2
        is GoExpression -> 1
        else -> 0
    }

    override fun getLiterals(): TokenSet = LITERALS

    override fun getRole(element: PsiElement): PsiElementRole? = roleOf(element)

    override fun getDuplocatorState(language: Language): ExternalizableDuplocatorState = state(language)

    companion object {
        val LITERALS: TokenSet = TokenSet.create(GoTypes.INT, GoTypes.FLOAT, GoTypes.IMAG, GoTypes.CHAR, GoTypes.STRING, GoTypes.RAW_STRING)

        /** The defaults of a language without settings yet: names and literals anonymized, fragments of at least five statements. */
        fun defaultState(): DefaultDuplocatorState = DefaultDuplocatorState().apply {
            DISTINGUISH_VARIABLES = false
            DISTINGUISH_FUNCTIONS = true
            DISTINGUISH_LITERALS = false
            LOWER_BOUND = 10
            DISCARD_COST = 0
        }

        /** What the Locate Duplicates dialog edits for Go; the defaults where the settings service of the commercial IDEs is absent. */
        fun state(language: Language): ExternalizableDuplocatorState {
            val settings = runCatching { ApplicationManager.getApplication()?.getService(MultilanguageDuplocatorSettings::class.java) }.getOrNull() ?: return FALLBACK
            return settings.getState(language) ?: defaultState().also { settings.registerState(language, it) }
        }

        private val FALLBACK = defaultState()

        /** The role of an identifier for the anonymizing checkboxes: a variable, a field or a function name; null for other tokens. */
        fun roleOf(element: PsiElement): PsiElementRole? {
            // the token, not its owner: the qualifier of `a.b` and the body of a function are still compared
            if (element.elementType != GoTypes.IDENTIFIER) return null
            return when (val named = element.parent) {
                is GoVarDefinition, is GoParamDefinition -> PsiElementRole.VARIABLE_NAME
                is GoFieldDefinition -> PsiElementRole.FIELD_NAME
                is GoFunctionOrMethodDeclaration -> PsiElementRole.FUNCTION_NAME
                is GoReferenceExpression -> when {
                    (named.parent as? GoCallExpr)?.expression == named -> PsiElementRole.FUNCTION_NAME
                    named.expression != null -> PsiElementRole.FIELD_NAME
                    else -> PsiElementRole.VARIABLE_NAME
                }
                else -> null
            }
        }
    }
}
