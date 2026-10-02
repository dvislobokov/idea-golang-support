package io.github.golangsupport.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.lexer.Lexer
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.RowIcon
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.intellij.util.IncorrectOperationException
import com.intellij.util.PlatformIcons
import javax.swing.Icon

/**
 * There is no real Go parser: [GoTreeBuilder] groups the tokens into a node per declaration (function, type, field, ...), which is
 * what Structure view, breadcrumbs, folding and Go to Symbol need when there is no language server. Inside a function the tokens are
 * a flat list: enough for highlighting, commenting, brace matching and word selection. The meaning of the code comes from gopls.
 */
class GoParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GoTextLexer()
    override fun createParser(project: Project?): PsiParser = PsiParser(GoTreeBuilder::build)
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = GoTextTokens.COMMENTS
    override fun getStringLiteralElements(): TokenSet = GoTextTokens.STRINGS
    override fun createElement(node: ASTNode): PsiElement = if (GoElementTypes.kindOf(node.elementType) != null) GoDeclaration(node) else ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = GoFile(viewProvider)

    companion object {
        val FILE = IFileElementType(GoLanguage)
    }
}

/** One element type per kind of declaration: the PSI knows what it is without looking at the text again. */
object GoElementTypes {
    private val BY_KIND: Map<GoDeclarationKind, IElementType> = GoDeclarationKind.entries.associateWith { GoTextTokenType("DECLARATION_" + it.name) }
    private val KINDS: Map<IElementType, GoDeclarationKind> = BY_KIND.entries.associate { it.value to it.key }

    fun of(kind: GoDeclarationKind): IElementType = BY_KIND.getValue(kind)
    fun kindOf(type: IElementType): GoDeclarationKind? = KINDS[type]
}

object GoTreeBuilder {
    fun build(root: IElementType, builder: PsiBuilder): ASTNode {
        val starts = GoDeclarations.scan(builder.originalText).all().groupBy { it.range.startOffset }
        val file = builder.mark()
        val open = ArrayDeque<Pair<PsiBuilder.Marker, GoDeclarationInfo>>()
        while (!builder.eof()) {
            val offset = builder.currentOffset
            while (open.isNotEmpty() && open.last().second.range.endOffset <= offset) open.removeLast().let { (marker, info) -> marker.done(GoElementTypes.of(info.kind)) }
            // an outer declaration first: a marker is closed after the ones opened inside of it
            starts[offset]?.sortedByDescending { it.range.length }?.forEach { open.addLast(builder.mark() to it) }
            builder.advanceLexer()
        }
        while (open.isNotEmpty()) open.removeLast().let { (marker, info) -> marker.done(GoElementTypes.of(info.kind)) }
        file.done(root)
        return builder.treeBuilt
    }
}

/** The declarations of a file as [GoDeclarations] sees its current text; scanned once per change. */
object GoStructure {
    fun of(file: PsiFile): GoFileStructure = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(GoDeclarations.scan(file.viewProvider.contents), file)
    }
}

/** A function, a type, a field, a constant or a variable. Everything about it beyond its kind comes from [GoStructure]. */
class GoDeclaration(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner, NavigatablePsiElement {
    val kind: GoDeclarationKind get() = GoElementTypes.kindOf(node.elementType) ?: GoDeclarationKind.FUNCTION

    val info: GoDeclarationInfo?
        get() = GoStructure.of(containingFile).find(textRange.startOffset, kind)

    /** `http.Server`: the package, and for a field or a method the type it belongs to. */
    val containerName: String
        get() = listOfNotNull(GoStructure.of(containingFile).packageName, (parent as? GoDeclaration)?.name ?: info?.receiver).joinToString(".")

    override fun getName(): String? = info?.name
    override fun getNameIdentifier(): PsiElement? = info?.let { containingFile.findElementAt(it.nameRange.startOffset) }
    override fun getTextOffset(): Int = info?.nameRange?.startOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Renaming Go declarations needs the language server (gopls)")

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = info?.presentation ?: text.take(MAX_TEXT)
        override fun getLocationString(): String = listOf(containerName, containingFile.name).filter { it.isNotEmpty() }.joinToString(" in ")
        override fun getIcon(unused: Boolean): Icon = this@GoDeclaration.getIcon(0)
    }

    override fun getIcon(flags: Int): Icon = GoDeclarationIcons.of(kind, info?.isExported == true)
    /** The name needs read access, and `toString` is called from anywhere: logs, debuggers, scripts. */
    override fun toString(): String = "GoDeclaration(${kind.title}" + (if (ApplicationManager.getApplication().isReadAccessAllowed) " " + name.orEmpty() else "") + ")"

    private companion object {
        const val MAX_TEXT = 40
    }
}

object GoDeclarationIcons {
    /** Exported or not is all the visibility Go has. */
    fun of(kind: GoDeclarationKind, exported: Boolean): Icon {
        val base = when (kind) {
            GoDeclarationKind.FUNCTION -> AllIcons.Nodes.Function
            GoDeclarationKind.METHOD -> AllIcons.Nodes.Method
            GoDeclarationKind.INTERFACE_METHOD -> AllIcons.Nodes.AbstractMethod
            GoDeclarationKind.STRUCT -> AllIcons.Nodes.Class
            GoDeclarationKind.INTERFACE -> AllIcons.Nodes.Interface
            GoDeclarationKind.TYPE -> AllIcons.Nodes.Type
            GoDeclarationKind.FIELD -> AllIcons.Nodes.Field
            GoDeclarationKind.CONST -> AllIcons.Nodes.Constant
            GoDeclarationKind.VAR -> AllIcons.Nodes.Variable
        }
        return RowIcon(base, if (exported) PlatformIcons.PUBLIC_ICON else PlatformIcons.PRIVATE_ICON)
    }
}

/** Structure tool window and File Structure popup: the declarations of the file as a tree. */
class GoStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is GoFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = Model(psiFile, editor)

            override fun isRootNodeShown(): Boolean = false
        }
    }

    /** Tells the tree which nodes are leaves: without it every field and function gets an arrow that opens nothing. */
    private class Model(file: PsiFile, editor: Editor?) : StructureViewModelBase(file, editor, Element(file)), StructureViewModel.ElementInfoProvider {
        init {
            withSuitableClasses(GoDeclaration::class.java).withSorters(Sorter.ALPHA_SORTER)
        }

        override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
        override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = (element.value as? GoDeclaration)?.kind?.isType == false
    }

    /**
     * The methods of a type stand under it, as in GoLand, when the type is declared in the same file; a method of a type from another file
     * stays on the top level, where it is in the text.
     */
    class Element(element: PsiElement) : PsiTreeElementBase<PsiElement>(element) {
        override fun getPresentableText(): String? = (element as? GoDeclaration)?.presentation?.presentableText ?: (element as? PsiFile)?.name

        override fun getChildrenBase(): Collection<StructureViewTreeElement> {
            val element = element ?: return emptyList()
            if (element is PsiFile) {
                val declarations = PsiTreeUtil.getChildrenOfTypeAsList(element, GoDeclaration::class.java)
                val types = declarations.filter { it.kind.isType }.mapNotNull { it.name }.toSet()
                return declarations.filter { it.kind != GoDeclarationKind.METHOD || it.info?.receiver !in types }.map(::Element)
            }
            val declaration = element as? GoDeclaration ?: return emptyList()
            val members = PsiTreeUtil.getChildrenOfTypeAsList(declaration, GoDeclaration::class.java)
            if (!declaration.kind.isType) return members.map(::Element)
            val methods = PsiTreeUtil.getChildrenOfTypeAsList(declaration.containingFile, GoDeclaration::class.java)
                .filter { it.kind == GoDeclarationKind.METHOD && it.info?.receiver == declaration.name }
            return (members + methods).map(::Element)
        }
    }
}

/** `Server › Start()` above the editor, and the sticky lines that follow it. */
class GoBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(GoLanguage)
    override fun acceptElement(element: PsiElement): Boolean = element is GoDeclaration && element.info != null
    override fun getElementIcon(element: PsiElement): Icon? = (element as? GoDeclaration)?.getIcon(0)
    override fun getElementTooltip(element: PsiElement): String? = (element as? GoDeclaration)?.info?.presentation

    override fun getElementInfo(element: PsiElement): String {
        val info = (element as GoDeclaration).info ?: return ""
        val isCallable = info.kind == GoDeclarationKind.FUNCTION || info.kind == GoDeclarationKind.METHOD || info.kind == GoDeclarationKind.INTERFACE_METHOD
        return (if (info.receiver != null) "(${info.receiver}) " else "") + info.name + if (isCallable) "()" else ""
    }
}
