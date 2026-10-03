package io.github.golangsupport.ide.hierarchy

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstraintElem
import io.github.golangsupport.lang.psi.GoConstraintTerm
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import javax.swing.JPanel
import javax.swing.JTree

/**
 * Type relations of Go named types for the type hierarchy. Go has no class tree, so the hierarchy is built from two
 * relations, both shown one level at a time and expanded lazily:
 *  - implementation: a concrete type is a subtype of every project or library interface its value or pointer method set
 *    satisfies ([GoImplementations.implementedInterfaces] / [GoImplementations.implementingTypes], the stub-index
 *    machinery of Go to Implementation);
 *  - embedding: a struct is a subtype of the types embedded in it (`struct{ Base; io.Reader }`), an interface of the
 *    interfaces it embeds (`interface{ io.Reader; Close() error }`). Embedders are found by [ReferencesSearch] on the
 *    embedded type spec, keeping the references that stand in an embedding position of another type spec.
 * Interfaces that are satisfied by a larger interface without embedding it are not linked (no method-set comparison of
 * interfaces), and type parameters and aliases are not followed.
 */
object GoTypeRelations {

    fun isInterface(spec: GoTypeSpec): Boolean = GoImplementations.isInterface(spec)

    /** Embedded types, then the interfaces a concrete type implements; [spec] itself never. */
    fun supertypes(spec: GoTypeSpec, scope: GlobalSearchScope): List<GoTypeSpec> {
        val result = LinkedHashSet(embedded(spec))
        if (!isInterface(spec)) result += GoImplementations.implementedInterfaces(spec, scope)
        result -= spec
        return result.toList()
    }

    /** For an interface its implementations, then the types embedding it; for any other type the structs embedding it. */
    fun subtypes(spec: GoTypeSpec, scope: GlobalSearchScope): List<GoTypeSpec> {
        val result = LinkedHashSet<GoTypeSpec>()
        if (isInterface(spec)) result += GoImplementations.implementingTypes(spec, scope)
        result += embedders(spec, scope)
        result -= spec
        return result.toList()
    }

    /** The type specs embedded directly in [spec]'s own struct or interface type (not in nested literal types). */
    fun embedded(spec: GoTypeSpec): List<GoTypeSpec> {
        val semantic = GoSemanticService.getInstance(spec.project)
        val references: List<GoTypeReferenceExpression> = when (val type = spec.type) {
            is GoStructType -> PsiTreeUtil.getChildrenOfTypeAsList(type, GoFieldDeclaration::class.java).mapNotNull { it.anonymousFieldDefinition?.typeReferenceExpression }
            is GoInterfaceType -> PsiTreeUtil.getChildrenOfTypeAsList(type, GoConstraintElem::class.java).mapNotNull(::embeddedInterface)
            else -> emptyList()
        }
        return references.mapNotNull { semantic.resolve(it) as? GoTypeSpec }.distinct()
    }

    /** `I` or `pkg.I` as the single, non-`~` term of an interface element. */
    private fun embeddedInterface(element: GoConstraintElem): GoTypeReferenceExpression? {
        val term = PsiTreeUtil.getChildrenOfTypeAsList(element, GoConstraintTerm::class.java).singleOrNull() ?: return null
        if (term.text.startsWith("~")) return null
        val reference = PsiTreeUtil.findChildOfType(term, GoTypeReferenceExpression::class.java) ?: return null
        // The whole term must be the name: `[]I` or `func() I` embed nothing.
        return reference.takeIf { term.text == it.text }
    }

    /** Type specs whose own struct or interface type embeds [spec]. */
    fun embedders(spec: GoTypeSpec, scope: GlobalSearchScope): List<GoTypeSpec> {
        val result = LinkedHashSet<GoTypeSpec>()
        ReferencesSearch.search(spec, scope).forEach(Processor { reference ->
            ProgressManager.checkCanceled()
            val element = reference.element as? GoTypeReferenceExpression ?: return@Processor true
            embedderOf(element)?.let(result::add)
            true
        })
        return result.toList()
    }

    private fun embedderOf(reference: GoTypeReferenceExpression): GoTypeSpec? {
        val anonymous = reference.parent as? GoAnonymousFieldDefinition
        val owner: PsiElement = if (anonymous != null) {
            val struct = (anonymous.parent as? GoFieldDeclaration)?.parent as? GoStructType ?: return null
            struct
        } else {
            val term = PsiTreeUtil.getParentOfType(reference, GoConstraintTerm::class.java) ?: return null
            val element = term.parent as? GoConstraintElem ?: return null
            if (embeddedInterface(element) != reference) return null
            element.parent as? GoInterfaceType ?: return null
        }
        val spec = PsiTreeUtil.getParentOfType(owner, GoTypeSpec::class.java) ?: return null
        return spec.takeIf { it.type == owner }
    }

    /** `pkg.Name` for the hierarchy's title. */
    fun qualifiedName(spec: GoTypeSpec): String {
        val pkg = (spec.containingFile as? GoFile)?.packageName
        return if (pkg.isNullOrEmpty()) spec.name.orEmpty() else "$pkg.${spec.name}"
    }
}

/**
 * Type Hierarchy (Ctrl+H) for a Go named type. The root is always the type itself: the "Supertypes" view lists what it
 * embeds and implements, the "Subtypes" view what implements or embeds it, and the "Type" view (Java's class tree) is the
 * subtypes view, since there is no single chain of supertypes to put above it. An interface opens on its subtypes, any
 * other type on its supertypes (the interfaces it satisfies are what a reader of a struct asks for).
 * When [GoIdeFeature.NAVIGATION] is off the target is null, so the platform asks the next provider registered for Go.
 */
class GoTypeHierarchyProvider : HierarchyProvider {

    override fun getTarget(dataContext: DataContext): PsiElement? {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project)) return null
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        if (editor == null) return typeOf(CommonDataKeys.PSI_ELEMENT.getData(dataContext))
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) as? GoFile ?: return null
        val referenced = TargetElementUtil.findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)
        if (referenced is GoTypeSpec) return referenced
        val at = file.findElementAt(editor.caretModel.offset) ?: return null
        return typeOf(PsiTreeUtil.getParentOfType(at, GoTypeSpec::class.java, GoMethodDeclaration::class.java))
    }

    /** A type spec itself, or the receiver type of a method. */
    private fun typeOf(element: PsiElement?): GoTypeSpec? = when (element) {
        is GoTypeSpec -> element
        is GoMethodDeclaration -> GoImplementations.receiverTypeSpec(element)
        else -> null
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = GoTypeHierarchyBrowser(target.project, target as GoTypeSpec)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        val browser = hierarchyBrowser as GoTypeHierarchyBrowser
        browser.changeView(if (browser.isInterface) TypeHierarchyBrowserBase.getSubtypesHierarchyType() else TypeHierarchyBrowserBase.getSupertypesHierarchyType())
    }
}

class GoTypeHierarchyBrowser(project: Project, spec: GoTypeSpec) : TypeHierarchyBrowserBase(project, spec) {

    override fun createTrees(trees: MutableMap<in String, in JTree>) = createTreeAndSetupCommonActions(trees, IdeActions.GROUP_TYPE_HIERARCHY_POPUP)

    override fun isInterface(element: PsiElement): Boolean = element is GoTypeSpec && GoTypeRelations.isInterface(element)

    override fun canBeDeleted(element: PsiElement?): Boolean = false

    override fun getQualifiedName(element: PsiElement?): String = (element as? GoTypeSpec)?.let(GoTypeRelations::qualifiedName).orEmpty()

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun createLegendPanel(): JPanel? = null

    override fun isApplicableElement(element: PsiElement): Boolean = element is GoTypeSpec

    override fun createHierarchyTreeStructure(type: String, element: PsiElement): HierarchyTreeStructure? {
        val spec = element as? GoTypeSpec ?: return null
        return when (type) {
            getSupertypesHierarchyType() -> GoTypeTreeStructure(myProject, spec, currentScopeType, supertypes = true)
            getSubtypesHierarchyType(), getTypeHierarchyType() -> GoTypeTreeStructure(myProject, spec, currentScopeType, supertypes = false)
            else -> null
        }
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> = GoHierarchyNodeDescriptor.comparator(myProject)
}

/** The type at the root, its supertypes or subtypes below, each expanding the same way until the path repeats. */
class GoTypeTreeStructure(project: Project, spec: GoTypeSpec, private val scopeType: String, private val supertypes: Boolean) :
    HierarchyTreeStructure(project, GoHierarchyNodeDescriptor(project, null, spec, true)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> = ReadAction.compute<Array<Any>, RuntimeException> {
        val spec = descriptor.psiElement as? GoTypeSpec
        if (spec == null || (descriptor !== myBaseDescriptor && GoHierarchyNodeDescriptor.isRecursive(descriptor))) return@compute emptyArray()
        val base = myBaseDescriptor.psiElement ?: spec
        val scope = GoHierarchyNodeDescriptor.globalScope(myProject, getSearchScope(scopeType, base.containingFile ?: base))
        val related = if (supertypes) GoTypeRelations.supertypes(spec, scope) else GoTypeRelations.subtypes(spec, scope)
        related.map { GoHierarchyNodeDescriptor(myProject, descriptor, it, false) }.toTypedArray()
    }
}
