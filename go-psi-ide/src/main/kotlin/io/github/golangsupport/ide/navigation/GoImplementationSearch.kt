package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.codeInsight.TargetElementUtilBase
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * `definitionsScopedSearch` executor behind Go to Implementation (Ctrl+Alt+B): an interface type
 * spec yields the concrete types implementing it, an interface method spec the implementing methods.
 */
class GoImplementationSearch : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {

    override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, parameters.project)) return
        val element = parameters.element
        val scope = parameters.scope as? GlobalSearchScope ?: GlobalSearchScope.allScope(parameters.project)
        val targets: List<PsiElement> = when (element) {
            is GoTypeSpec -> GoImplementations.implementingTypes(element, scope)
            is GoMethodSpec -> GoImplementations.implementingMethods(element, scope)
            else -> return
        }
        for (target in targets) {
            if (!consumer.process(target)) return
        }
    }
}

/**
 * Target element tweaks for Go: Go to Implementation on an interface or an interface method lists
 * only the implementations, not the interface itself.
 *
 * The platform takes a single `targetElementEvaluator` per language (`forLanguage`, the first registered), so this one is
 * registered first and, when the gate is closed, hands the questions another source overrides to the next evaluator for Go.
 */
class GoTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun includeSelfInGotoImplementation(element: PsiElement): Boolean {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, element.project)) return next()?.includeSelfInGotoImplementation(element) ?: true
        return when (element) {
            is GoMethodSpec -> false
            is GoTypeSpec -> !GoImplementations.isInterface(element)
            else -> true
        }
    }

    override fun getNamedElement(element: PsiElement): PsiElement? =
        if (GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, element.project)) null else next()?.getNamedElement(element)

    /** The evaluator registered for Go after this one, if any. */
    private fun next(): TargetElementEvaluatorEx2? =
        TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR.allForLanguage(GoLanguage).asSequence().filterIsInstance<TargetElementEvaluatorEx2>().firstOrNull { it !== this }
}
