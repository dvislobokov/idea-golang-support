package io.github.golangsupport.ide.hierarchy

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.PopupHandler
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import javax.swing.JTree

/**
 * Call Hierarchy (Ctrl+Alt+H) for a Go function, method or interface method; callers and callees come from [GoCalls].
 * When [GoIdeFeature.NAVIGATION] is off the target is null, so the platform asks the next provider registered for Go.
 */
class GoCallHierarchyProvider : HierarchyProvider {

    override fun getTarget(dataContext: DataContext): PsiElement? {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project)) return null
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        if (editor == null) return CommonDataKeys.PSI_ELEMENT.getData(dataContext)?.takeIf(GoCalls::isCallable)
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) as? GoFile ?: return null
        val referenced = TargetElementUtil.findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)
        if (GoCalls.isCallable(referenced)) return referenced
        // Anywhere else in a body: the function around the caret, as the Java hierarchy takes the enclosing method.
        return PsiTreeUtil.getParentOfType(file.findElementAt(editor.caretModel.offset), GoFunctionOrMethodDeclaration::class.java, GoMethodSpec::class.java)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = GoCallHierarchyBrowser(target.project, target)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as GoCallHierarchyBrowser).changeView(CallHierarchyBrowserBase.getCallerType())
    }
}

class GoCallHierarchyBrowser(project: Project, element: PsiElement) : CallHierarchyBrowserBase(project, element) {

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        val actions = ActionManager.getInstance()
        val group = actions.getAction(IdeActions.GROUP_CALL_HIERARCHY_POPUP) as ActionGroup
        for (type in listOf(getCalleeType(), getCallerType())) {
            val tree = createTree(false)
            PopupHandler.installPopupMenu(tree, group, ActionPlaces.CALL_HIERARCHY_VIEW_POPUP)
            BaseOnThisMethodAction().registerCustomShortcutSet(actions.getAction(IdeActions.ACTION_CALL_HIERARCHY).shortcutSet, tree)
            trees[type] = tree
        }
    }

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun isApplicableElement(element: PsiElement): Boolean = GoCalls.isCallable(element)

    override fun createHierarchyTreeStructure(type: String, element: PsiElement): HierarchyTreeStructure? = when (type) {
        getCallerType() -> GoCallerTreeStructure(myProject, element, currentScopeType)
        getCalleeType() -> GoCalleeTreeStructure(myProject, element)
        else -> null
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> = GoHierarchyNodeDescriptor.comparator(myProject)
}

/** Callers of the base function; each caller expands into its own callers until the path repeats. */
class GoCallerTreeStructure(project: Project, element: PsiElement, private val scopeType: String) :
    HierarchyTreeStructure(project, GoHierarchyNodeDescriptor(project, null, element, true)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> = ReadAction.compute<Array<Any>, RuntimeException> {
        val element = descriptor.psiElement
        if (!GoCalls.isCallable(element) || (descriptor !== myBaseDescriptor && GoHierarchyNodeDescriptor.isRecursive(descriptor))) return@compute emptyArray()
        val base = myBaseDescriptor.psiElement ?: element!!
        val scope = getSearchScope(scopeType, base.containingFile ?: base)
        GoCalls.callers(element!!, scope).map { GoHierarchyNodeDescriptor(myProject, descriptor, it.element, false, it.usages, it.via) }.toTypedArray()
    }
}

/** Functions called by the base function (in any scope: the calls are all in its body); each callee expands into its own callees until the path repeats. */
class GoCalleeTreeStructure(project: Project, element: PsiElement) :
    HierarchyTreeStructure(project, GoHierarchyNodeDescriptor(project, null, element, true)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> = ReadAction.compute<Array<Any>, RuntimeException> {
        val element = descriptor.psiElement
        if (element !is GoFunctionOrMethodDeclaration || (descriptor !== myBaseDescriptor && GoHierarchyNodeDescriptor.isRecursive(descriptor))) {
            return@compute emptyArray()
        }
        GoCalls.callees(element).map { GoHierarchyNodeDescriptor(myProject, descriptor, it.element, false, it.usages) }.toTypedArray()
    }
}
