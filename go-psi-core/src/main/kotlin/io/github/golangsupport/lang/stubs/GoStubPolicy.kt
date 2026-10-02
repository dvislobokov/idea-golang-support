package io.github.golangsupport.lang.stubs

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.psi.GoTypes
import org.jetbrains.annotations.ApiStatus

/**
 * Which nodes get stubs (docs/GRAMMAR.md section M):
 *  - declaration roots (package clause, import specs, func/method declarations, type specs,
 *    var/const declarations) unless they are inside a `Block` (function bodies, function literals);
 *  - every other stub element type (signatures, parameters, specs, fields, type nodes, ...) only
 *    when its direct parent node is itself stubbed. Types inside expressions (composite literal
 *    types, conversions, function literal signatures) therefore never get stubs.
 */
@ApiStatus.Internal
object GoStubPolicy {
    /** Stubbed node types that may sit under a non-stubbed parent. */
    @JvmField
    val DECLARATION_ROOTS: TokenSet = TokenSet.create(
        GoTypes.PACKAGE_CLAUSE, GoTypes.IMPORT_SPEC, GoTypes.FUNCTION_DECLARATION, GoTypes.METHOD_DECLARATION,
        GoTypes.TYPE_SPEC, GoTypes.VAR_DECLARATION, GoTypes.CONST_DECLARATION,
    )

    /** Non-stubbed nodes the stub builder must descend into: they contain declaration roots. */
    @JvmField
    val TRANSPARENT_CONTAINERS: TokenSet = TokenSet.create(
        GoTypes.IMPORT_LIST, GoTypes.IMPORT_DECLARATION, GoTypes.TYPE_DECLARATION,
    )

    @JvmStatic
    fun shouldCreateStub(node: ASTNode): Boolean {
        if (DECLARATION_ROOTS.contains(node.elementType)) return !isInsideBlock(node)
        val parent = node.treeParent ?: return false
        val parentType = parent.elementType
        return parentType is IStubElementType<*, *> && parentType.shouldCreateStub(parent)
    }

    private fun isInsideBlock(node: ASTNode): Boolean {
        var current = node.treeParent
        while (current != null) {
            if (current.elementType === GoTypes.BLOCK) return true
            current = current.treeParent
        }
        return false
    }
}
