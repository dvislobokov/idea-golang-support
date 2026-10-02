package io.github.golangsupport.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
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
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoBlock

/**
 * The bridge between the PSI of the parser (`lang.psi`) and the text scanner [GoDeclarations], which the tools of the plugin still read
 * ([GoFileStructure]: receivers, signatures, bodies as text ranges). Until step 6 of MIGRATION.md the scanner stays the source of that
 * text; here a PSI declaration finds its [GoDeclarationInfo] by the offset of its name (doc comments are part of a PSI declaration,
 * so the start of the element is not the start the scanner knows), and an info finds its PSI element the same way back.
 */
object GoDeclarationPsi {
    /** The kind of a declaration the scanner also knows, or null for anything else: locals, parameters, imports, labels, type parameters. */
    fun kindOf(element: PsiElement): GoDeclarationKind? = when (element) {
        is GoFunctionDeclaration -> GoDeclarationKind.FUNCTION
        is GoMethodDeclaration -> GoDeclarationKind.METHOD
        is GoTypeSpec -> when (element.type) {
            is GoStructType -> GoDeclarationKind.STRUCT
            is GoInterfaceType -> GoDeclarationKind.INTERFACE
            else -> GoDeclarationKind.TYPE
        }
        is GoMethodSpec -> GoDeclarationKind.INTERFACE_METHOD
        is GoFieldDefinition -> GoDeclarationKind.FIELD
        is GoVarDefinition -> if (isPackageLevel(element)) GoDeclarationKind.VAR else null
        is GoConstDefinition -> if (isPackageLevel(element)) GoDeclarationKind.CONST else null
        else -> null
    }

    /** The named PSI element the scanner would list, when [element] is one. */
    fun declaration(element: PsiElement?): GoNamedElement? = (element as? GoNamedElement)?.takeIf { kindOf(it) != null }

    /** The declaration whose name is this identifier leaf; null for any other token, and for a name the scanner does not list. */
    fun ofName(leaf: PsiElement?): GoNamedElement? {
        if (leaf == null || leaf.elementType != GoTypes.IDENTIFIER) return null
        val parent = declaration(leaf.parent) ?: return null
        return parent.takeIf { it.nameIdentifier == leaf }
    }

    /** Any named element whose name is this identifier leaf, a local or a parameter too: what Find Usages of the PSI starts from. */
    fun namedOf(leaf: PsiElement?): GoNamedElement? {
        if (leaf == null || leaf.elementType != GoTypes.IDENTIFIER) return null
        return (leaf.parent as? GoNamedElement)?.takeIf { it.nameIdentifier == leaf }
    }

    /** The offset of the name of a named element, the start of anything else: where a request to gopls about it points. */
    fun nameOffset(element: PsiElement): Int = (element as? GoNamedElement)?.nameIdentifier?.textRange?.startOffset ?: element.textRange.startOffset

    /** The innermost scanner-level declaration around [offset] (the name or anything inside it), as Go to Test and Go to Super want it. */
    fun at(file: PsiFile, offset: Int): GoNamedElement? {
        var element: PsiElement? = file.findElementAt(offset)
        while (element != null && element !is PsiFile) {
            declaration(element)?.let { return it }
            element = element.parent
        }
        return null
    }

    /** What the scanner says about this declaration: receiver, signature, body. Null when the scanner saw the file differently (broken code). */
    fun infoOf(element: PsiElement): GoDeclarationInfo? {
        val kind = kindOf(element) ?: return null
        val name = (element as GoNamedElement).nameIdentifier ?: return null
        return GoStructure.of(element.containingFile).findByName(name.textRange.startOffset, kind)
    }

    /** The PSI element of a scanned declaration, by the offset of its name. */
    fun psiOf(file: PsiFile, info: GoDeclarationInfo): GoNamedElement? = ofName(file.findElementAt(info.nameRange.startOffset))

    /** `(Server) Start(ctx context.Context) error`, `Port int`: the presentation of the scanner, or the bare name when it has none. */
    fun presentation(element: GoNamedElement): String = infoOf(element)?.presentation ?: element.name.orEmpty()

    /** `http.Server`: the package, and for a field or a method the type it belongs to. */
    fun containerName(element: GoNamedElement): String {
        val structure = GoStructure.of(element.containingFile)
        val owner = (element.parentOfType<GoTypeSpec>())?.name ?: (element as? GoMethodDeclaration)?.receiverTypeName
        return listOfNotNull(structure.packageName, owner).joinToString(".")
    }

    /**
     * An identifier token of a Go file that is not the name of a scanned declaration: a local, a parameter, a receiver, a field of a struct
     * literal. Find Usages and the gopls searchers accept these (the server tells where such a name is declared).
     */
    fun isLocalName(element: PsiElement): Boolean = element.containingFile is GoFile && element.elementType == GoTypes.IDENTIFIER && ofName(element) == null

    /** A var or const definition outside every function body: what the scanner lists, what the structure view shows. */
    private fun isPackageLevel(element: PsiElement): Boolean = element.parentOfType<GoBlock>() == null && element.parentOfType<GoFunctionLit>() == null
}
