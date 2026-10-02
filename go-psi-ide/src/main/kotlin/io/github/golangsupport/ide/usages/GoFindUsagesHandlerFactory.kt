package io.github.golangsupport.ide.usages

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec

/**
 * Find Usages of a method that implements interface methods: calls through the interface resolve
 * to the interface method spec, so the user is offered to search the interface methods too (like
 * GoLand's "find usages of the base method").
 */
class GoFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean = element is GoMethodDeclaration

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val method = element as? GoMethodDeclaration ?: return null
        if (forHighlightUsages) return object : FindUsagesHandler(method) {}
        return GoMethodFindUsagesHandler(method)
    }
}

class GoMethodFindUsagesHandler(private val method: GoMethodDeclaration) : FindUsagesHandler(method) {

    override fun getPrimaryElements(): Array<PsiElement> {
        val supers = computeSupers()
        if (supers.isEmpty()) return arrayOf(method)
        val names = supers.joinToString { describe(it) }
        val answer = Messages.showYesNoCancelDialog(
            project,
            "Method ${method.name} implements $names.\nDo you want to find usages of the interface method${if (supers.size > 1) "s" else ""} as well?",
            "Find Usages of Interface Method",
            "Include Interface Methods",
            "Only This Method",
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        return when (answer) {
            Messages.YES -> arrayOf<PsiElement>(method) + supers
            Messages.NO -> arrayOf(method)
            else -> PsiElement.EMPTY_ARRAY
        }
    }

    private fun computeSupers(): List<GoMethodSpec> {
        var result: List<GoMethodSpec> = emptyList()
        ApplicationManager.getApplication().runReadAction {
            result = GoImplementations.superMethods(method, GlobalSearchScope.allScope(project))
        }
        return result
    }

    private fun describe(spec: GoMethodSpec): String {
        val iface = GoImplementations.interfaceSpecOf(spec)?.name
        return if (iface != null) "$iface.${spec.name}" else spec.name.orEmpty()
    }
}
