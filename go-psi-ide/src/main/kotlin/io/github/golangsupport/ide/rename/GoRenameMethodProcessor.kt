package io.github.golangsupport.ide.rename

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec

/**
 * Renaming methods that take part in interface implementation.
 *
 * - On a method that implements interface methods declared in the project, the user is asked
 *   (like GoLand) whether to rename the interface method and all its implementations; "Yes"
 *   substitutes the interface method spec, "No" renames this method only.
 * - Renaming an interface method spec renames every implementing method in the project, and,
 *   transitively, other project interface methods those implementations satisfy (so the group
 *   stays consistent).
 *
 * Library interfaces and implementations are never renamed. Stands down while [GoIdeFeature.RENAME]
 * is off (the platform's default processor renames the one element then).
 */
class GoRenameMethodProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean =
        (element is GoMethodDeclaration || element is GoMethodSpec) && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        val method = element as? GoMethodDeclaration ?: return element
        val supers = GoImplementations.superMethods(method, GlobalSearchScope.projectScope(method.project))
        if (supers.isEmpty()) return element
        val names = supers.joinToString { s -> (GoImplementations.interfaceSpecOf(s)?.name?.let { "$it." } ?: "") + s.name }
        val answer = Messages.showYesNoCancelDialog(
            method.project,
            "Method ${method.name} implements $names.\nRename the interface method and all its implementations?",
            "Rename Interface Method",
            "Rename Interface Method",
            "Rename Only This Method",
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        return when (answer) {
            Messages.YES -> supers.first()
            Messages.NO -> element
            else -> null
        }
    }

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        if (element !is GoMethodSpec) return
        val projectScope = GlobalSearchScope.projectScope(element.project)
        val queue = ArrayDeque<PsiElement>().apply { add(element) }
        val seen = HashSet<PsiElement>().apply { add(element) }
        while (queue.isNotEmpty()) {
            val next: List<PsiElement> = when (val current = queue.removeFirst()) {
                is GoMethodSpec -> GoImplementations.implementingMethods(current, projectScope)
                is GoMethodDeclaration -> GoImplementations.superMethods(current, projectScope)
                else -> emptyList()
            }
            for (e in next) {
                if (!e.isWritable || !seen.add(e)) continue
                allRenames[e] = newName
                queue.add(e)
            }
        }
    }
}
