package io.github.golangsupport.ide.hierarchy

import com.intellij.ide.IdeBundle
import com.intellij.ide.hierarchy.HierarchyBrowserManager
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.navigation.GoItemPresentation
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement

/**
 * A node of the Go call and type hierarchies: `Type.Method` / `func` / `Type`, then the usage count and the interface a
 * call goes through (callers only), then the package location as [GoItemPresentation] shows it (`store (store.go)`).
 *
 * [usages] are the call sites behind the node (calls inside the caller, or calls of the callee); navigation goes to the
 * first of them, so a double click lands on the call, not on the declaration, as in the Java call hierarchy.
 */
class GoHierarchyNodeDescriptor(
    project: Project,
    parent: NodeDescriptor<*>?,
    element: PsiElement,
    isBase: Boolean,
    usages: List<PsiElement> = emptyList(),
    private val via: String? = null,
) : HierarchyNodeDescriptor(project, parent, element, isBase), Navigatable {

    private val usagePointers: List<SmartPsiElementPointer<PsiElement>> =
        usages.map { SmartPointerManager.getInstance(project).createSmartPsiElementPointer(it) }

    val usageCount: Int get() = usagePointers.size

    override fun update(): Boolean {
        var changes = super.update()
        val old = myHighlightedText
        myHighlightedText = CompositeAppearance()
        val element = psiElement
        if (element == null) {
            myHighlightedText.ending.addText(IdeBundle.message("node.hierarchy.invalid"), getInvalidPrefixAttributes())
            return true
        }
        GoIdeIcons.forElement(element)?.let { installIcon(it, changes) }
        myHighlightedText.ending.addText(text(element))
        if (usageCount > 1) myHighlightedText.ending.addText(" ($usageCount usages)", getUsageCountPrefixAttributes())
        if (via != null) myHighlightedText.ending.addText(" via $via", getUsageCountPrefixAttributes())
        GoItemPresentation.locationString(element)?.let { myHighlightedText.ending.addText("  $it", getPackageNameAttributes()) }
        myName = myHighlightedText.text
        if (old != myHighlightedText) changes = true
        return changes
    }

    private fun target(): Navigatable? = (usagePointers.firstNotNullOfOrNull { it.element } ?: psiElement) as? Navigatable

    override fun navigate(requestFocus: Boolean) {
        target()?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = target()?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = target()?.canNavigateToSource() == true

    companion object {
        /** `Recv.Method`, `Iface.Method`, `func` or the type name: [GoItemPresentation.shortText] plus the owner of interface methods. */
        @JvmStatic
        fun text(element: PsiElement): String {
            val named = element as? GoNamedElement ?: return element.text
            if (named is GoMethodSpec) {
                val owner = GoImplementations.interfaceSpecOf(named)?.name
                if (owner != null) return "$owner.${named.name}"
            }
            return GoItemPresentation.shortText(named) ?: named.text
        }

        /** Whether [descriptor]'s element is already on the path from the root: the node is shown but not expanded (recursion, embedding cycles). */
        @JvmStatic
        fun isRecursive(descriptor: HierarchyNodeDescriptor): Boolean {
            val element = descriptor.psiElement ?: return true
            var parent = descriptor.parentDescriptor
            while (parent != null) {
                if ((parent as? HierarchyNodeDescriptor)?.psiElement == element) return true
                parent = parent.parentDescriptor
            }
            return false
        }

        /** Alphabetical when the hierarchy view sorts so, otherwise in the order the nodes were found. */
        @JvmStatic
        fun comparator(project: Project): Comparator<NodeDescriptor<*>> =
            if (HierarchyBrowserManager.getSettings(project).SORT_ALPHABETICALLY) AlphaComparator.INSTANCE else compareBy { it.index }

        /** The type relations need a global scope; "This Class" (a local scope of the file) becomes the scope of its files. */
        @JvmStatic
        fun globalScope(project: Project, scope: SearchScope): GlobalSearchScope = when (scope) {
            is GlobalSearchScope -> scope
            is LocalSearchScope -> GlobalSearchScope.filesScope(project, scope.virtualFiles.toList())
            else -> GlobalSearchScope.allScope(project)
        }
    }
}
