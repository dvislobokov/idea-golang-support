package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.completion.GoScopeCandidates
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/** Values in scope at a place: the local variables, parameters and receivers declared before it (the walk of completion's [GoScopeCandidates]). */
object GoScopeValues {

    /** Variables, parameters and the receiver visible at [place], nearest first; a name hidden by an inner declaration is left out, so is `_`. */
    fun locals(place: PsiElement): List<GoNamedElement> {
        val seen = HashSet<String>()
        val result = ArrayList<GoNamedElement>()
        GoScopeCandidates.walkLocals(place) { e, _ ->
            val name = e.name
            if (name.isNullOrEmpty() || name == "_" || !seen.add(name)) return@walkLocals
            if (e is GoVarDefinition || e is GoParamDefinition || e is GoReceiver) result += e
        }
        return result
    }

    /** Names visible at [place] as locals (any kind of declaration): what a new variable must not be called. */
    fun localNames(place: PsiElement): Set<String> {
        val names = HashSet<String>()
        GoScopeCandidates.walkLocals(place) { e, _ -> e.name?.let(names::add) }
        return names
    }

    /** The locals of exactly [type] at [place], nearest first. */
    fun ofType(place: PsiElement, type: GoType): List<GoNamedElement> {
        val service = GoSemanticService.getInstance(place.project)
        return locals(place).filter { GoTypePredicates.identical(service.declarationType(it), type) }
    }
}
