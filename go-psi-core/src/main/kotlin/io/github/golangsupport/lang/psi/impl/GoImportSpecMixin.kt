package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.stubs.IStubElementType
import com.intellij.util.IncorrectOperationException
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoImportSpecBase
import io.github.golangsupport.lang.psi.GoReferenceProvider
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.stubs.GoImportSpecStub

/**
 * Import spec: the name is the alias (identifier, `.` or `_`) or, without an alias, the last
 * segment of the import path. Only an explicit identifier alias is a name identifier.
 */
abstract class GoImportSpecMixin : GoNamedElementImpl<GoImportSpecStub>, GoImportSpecBase {
    constructor(node: ASTNode) : super(node)
    constructor(stub: GoImportSpecStub, type: IStubElementType<*, *>) : super(stub, type)

    override val path: String
        get() = greenStub?.path ?: GoPsiImplUtil.unquote(node.findChildByType(GoTypes.STRING_LITERAL)?.text)

    override val alias: String?
        get() {
            val stub = greenStub
            if (stub != null) return stub.alias
            return (node.findChildByType(GoTypes.IDENTIFIER) ?: node.findChildByType(GoTypes.PERIOD))?.text
        }

    /** The import path reference (to the package directory), supplied by the semantic module. */
    override fun getReference(): PsiReference? = GoReferenceProvider.referenceOf(this)

    override val isDot: Boolean get() = alias == "."

    override val isBlank: Boolean get() = alias == "_"

    override fun getName(): String? {
        val stub = greenStub
        return if (stub != null) stub.name else GoPsiImplUtil.importName(alias, path)
    }

    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier
        if (identifier != null) {
            identifier.replace(GoElementFactory.createIdentifier(project, name))
            return this
        }
        val replacement = GoElementFactory.createImportSpec(project, name, path)
            ?: throw IncorrectOperationException("cannot create import spec '$name'")
        return replace(replacement)
    }
}
