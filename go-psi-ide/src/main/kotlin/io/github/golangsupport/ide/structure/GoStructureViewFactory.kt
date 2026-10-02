package io.github.golangsupport.ide.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.ide.navigation.GoItemPresentation
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstraintElem
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import javax.swing.Icon

/** Structure tool window and File Structure popup for Go files. */
class GoStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is GoFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = GoStructureViewModel(psiFile, editor)

            override fun isRootNodeShown(): Boolean = false
        }
    }
}

/**
 * Package-level declarations of a file in text order: types (fields, interface methods and the
 * methods declared on them in this file as children), functions, methods of types declared
 * elsewhere, vars and consts (groups flattened). Sorters: alphabetical, exported first, by kind.
 */
class GoStructureViewModel(file: GoFile, editor: Editor?) :
    StructureViewModelBase(file, editor, GoStructureViewElement(file)),
    StructureViewModel.ElementInfoProvider {

    init {
        withSorters(GoKindSorter, GoVisibilitySorter, Sorter.ALPHA_SORTER)
        withSuitableClasses(
            GoFunctionDeclaration::class.java,
            GoMethodDeclaration::class.java,
            GoTypeSpec::class.java,
            GoVarDefinition::class.java,
            GoConstDefinition::class.java,
            GoFieldDefinition::class.java,
            GoAnonymousFieldDefinition::class.java,
            GoMethodSpec::class.java,
        )
    }

    /** Declarations inside function bodies are not in the tree. */
    override fun isSuitable(element: PsiElement?): Boolean =
        super.isSuitable(element) && PsiTreeUtil.getParentOfType(element, GoBlock::class.java, GoFunctionLit::class.java) == null

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        when (val value = element.value) {
            is GoFile -> false
            is GoTypeSpec -> false
            else -> value is PsiElement
        }
}

/** One node of the Go structure tree. [topLevel] marks methods shown outside their type (receiver in the text). */
class GoStructureViewElement(element: PsiElement, private val topLevel: Boolean = false) :
    PsiTreeElementBase<PsiElement>(element), SortableTreeElement {

    override fun getPresentableText(): String? = when (val e = element) {
        is GoFile -> e.name
        is GoNamedElement -> GoItemPresentation.detailedText(e, withReceiver = topLevel)
        is GoConstraintElem -> GoItemPresentation.normalize(e.text)
        else -> null
    }

    override fun getIcon(open: Boolean): Icon? = element?.let(GoIdeIcons::forElement)

    override fun getAlphaSortKey(): String = when (val e = element) {
        is GoNamedElement -> e.name.orEmpty()
        is GoConstraintElem -> e.text
        else -> ""
    }

    override fun getChildrenBase(): Collection<StructureViewTreeElement> = when (val e = element) {
        is GoFile -> fileChildren(e)
        is GoTypeSpec -> typeChildren(e)
        else -> emptyList()
    }

    private fun fileChildren(file: GoFile): List<StructureViewTreeElement> {
        val typeNames = file.types.mapNotNullTo(HashSet()) { it.name }
        val result = mutableListOf<StructureViewTreeElement>()
        for (child in file.children) {
            when (child) {
                is GoTypeDeclaration -> child.typeSpecList.mapTo(result) { GoStructureViewElement(it) }
                is GoFunctionDeclaration -> result += GoStructureViewElement(child)
                is GoMethodDeclaration -> if (child.receiverTypeName !in typeNames) result += GoStructureViewElement(child, topLevel = true)
                is GoVarDeclaration -> child.varSpecList.flatMap { it.varDefinitionList }.mapTo(result) { GoStructureViewElement(it) }
                is GoConstDeclaration -> child.constSpecList.flatMap { it.constDefinitionList }.mapTo(result) { GoStructureViewElement(it) }
            }
        }
        return result
    }

    private fun typeChildren(spec: GoTypeSpec): List<StructureViewTreeElement> {
        val result = mutableListOf<StructureViewTreeElement>()
        when (val type = spec.type) {
            is GoStructType -> for (field in type.fieldDeclarationList) {
                field.fieldDefinitionList.mapTo(result) { GoStructureViewElement(it) }
                field.anonymousFieldDefinition?.let { result += GoStructureViewElement(it) }
            }
            // Method specs and embedded interfaces/type sets, in text order.
            is GoInterfaceType -> type.children
                .filter { it is GoMethodSpec || it is GoConstraintElem }
                .mapTo(result) { GoStructureViewElement(it) }
        }
        // Only package-level specs reach this node, so methods of the same file belong here.
        val name = spec.name ?: return result
        (spec.containingFile as? GoFile)?.methods
            ?.filter { it.receiverTypeName == name }
            ?.mapTo(result) { GoStructureViewElement(it) }
        return result
    }
}

/** Exported declarations before unexported ones. */
object GoVisibilitySorter : Sorter {
    const val ID: String = "GO_VISIBILITY_SORTER"

    override fun getComparator(): Comparator<*> = Comparator<Any> { a, b -> rank(a).compareTo(rank(b)) }

    override fun isVisible(): Boolean = true

    override fun getPresentation(): ActionPresentation =
        ActionPresentationData("Sort by Visibility", null, AllIcons.ObjectBrowser.VisibilitySort)

    override fun getName(): String = ID

    private fun rank(node: Any?): Int {
        val element = (node as? StructureViewTreeElement)?.value as? GoNamedElement ?: return 1
        return if (element.isPublic()) 0 else 1
    }
}

/** Types, then consts, vars, functions and methods; fields before interface methods inside a type. */
object GoKindSorter : Sorter {
    const val ID: String = "GO_KIND_SORTER"

    override fun getComparator(): Comparator<*> = Comparator<Any> { a, b -> rank(a).compareTo(rank(b)) }

    override fun isVisible(): Boolean = true

    override fun getPresentation(): ActionPresentation =
        ActionPresentationData("Sort by Kind", null, AllIcons.ObjectBrowser.SortByType)

    override fun getName(): String = ID

    private fun rank(node: Any?): Int = when ((node as? StructureViewTreeElement)?.value) {
        is GoTypeSpec -> 0
        is GoFieldDefinition, is GoAnonymousFieldDefinition -> 1
        is GoConstraintElem, is GoMethodSpec -> 2
        is GoConstDefinition -> 3
        is GoVarDefinition -> 4
        is GoFunctionDeclaration -> 5
        is GoMethodDeclaration -> 6
        else -> 7
    }
}
