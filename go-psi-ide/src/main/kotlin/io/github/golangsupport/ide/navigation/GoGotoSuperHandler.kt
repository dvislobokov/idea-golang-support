package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.lang.CodeInsightActions
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * Go to Super Method (Ctrl+U): from a method to the interface methods it implements, from a
 * concrete type spec to the interfaces it implements. Interfaces are searched in project and
 * libraries.
 */
class GoGotoSuperHandler : LanguageCodeInsightActionHandler {

    override fun isValidFor(editor: Editor, file: PsiFile): Boolean {
        if (file !is GoFile) return false
        if (GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, file.project)) return true
        val next = next(file) ?: return false
        return next !is LanguageCodeInsightActionHandler || next.isValidFor(editor, file)
    }

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project)) {
            next(file)?.invoke(project, editor, file)
            return
        }
        val targets = findTargets(file.findElementAt(editor.caretModel.offset) ?: return)
        when (targets.size) {
            0 -> return
            1 -> (targets[0] as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
            else -> PsiTargetNavigator(targets).navigate(editor, "Choose Super Method or Interface")
        }
    }

    /**
     * The handler registered for Go after this one, if any: the platform takes a single `codeInsight.gotoSuper` per language
     * (`forLanguage`, the first registered), so this one is registered first and defers when the gate is closed.
     */
    private fun next(file: PsiFile): CodeInsightActionHandler? = CodeInsightActions.GOTO_SUPER.allForLanguage(file.language).firstOrNull { it !== this }

    companion object {
        /** Super targets for the declaration around [element]: a method's interface methods, or a type's interfaces. */
        @JvmStatic
        fun findTargets(element: PsiElement): List<PsiElement> {
            val scope = GlobalSearchScope.allScope(element.project)
            val owner = PsiTreeUtil.getParentOfType(element, GoMethodDeclaration::class.java, GoTypeSpec::class.java) ?: return emptyList()
            return when (owner) {
                is GoMethodDeclaration -> GoImplementations.superMethods(owner, scope)
                is GoTypeSpec -> GoImplementations.implementedInterfaces(owner, scope)
                else -> emptyList()
            }
        }
    }
}
