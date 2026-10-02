package io.github.golangsupport.semantic.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.*

/**
 * Accessors the generated PSI lacks for children parsed through external rules (`<<nested ...>>`)
 * and small structural helpers used by scopes, resolve and inference.
 */
object GoPsiUtil {
    /**
     * [file] itself, or the file it was copied from. Completion and intentions work on non-physical copies
     * (light virtual file, no directory); package, directory and module are always derived from the original.
     */
    fun originalFile(file: GoFile): GoFile = file.originalFile as? GoFile ?: file

    /** The virtual file that determines the package/directory/module of [file] (see [originalFile]). */
    fun originalVirtualFile(file: GoFile): com.intellij.openapi.vfs.VirtualFile = originalFile(file).viewProvider.virtualFile

    fun <T : PsiElement> children(parent: PsiElement?, cls: Class<T>): List<T> =
        if (parent == null) emptyList() else PsiTreeUtil.getChildrenOfTypeAsList(parent, cls)

    fun hasChildToken(parent: PsiElement, type: IElementType): Boolean = parent.node.findChildByType(type) != null

    /** The green stub of a stub-based element, or null (no stub, or the AST is already loaded). */
    inline fun <reified S : com.intellij.psi.stubs.StubElement<*>> stubOf(element: PsiElement?): S? =
        (element as? com.intellij.extapi.psi.StubBasedPsiElementBase<*>)?.greenStub as? S

    /** `...` before a parameter type, stub-first. */
    val GoParameterDeclaration.isVariadic: Boolean
        get() = stubOf<io.github.golangsupport.lang.stubs.GoParameterDeclarationStub>(this)?.isVariadic ?: hasChildToken(this, GoTypes.ELLIPSIS)

    /** `~` before a constraint term, stub-first. */
    val GoConstraintTerm.hasTilde: Boolean
        get() = stubOf<io.github.golangsupport.lang.stubs.GoConstraintTermStub>(this)?.hasTilde ?: (tilde != null)

    fun childToken(parent: PsiElement, type: IElementType): PsiElement? = parent.node.findChildByType(type)?.psi

    // --- expressions ---

    val GoLiteralValue.elements: List<GoElement> get() = children(this, GoElement::class.java)

    val GoArgumentList.expressions: List<GoExpression> get() = children(this, GoExpression::class.java)

    /** Arguments that are syntactically types (`make([]int, 3)`, `new(T)`); mixed with expressions in source order. */
    val GoArgumentList.arguments: List<PsiElement> get() = children(this, PsiElement::class.java).filter { it is GoExpression || it is GoType }

    val GoArgumentList.hasEllipsis: Boolean get() = hasChildToken(this, GoTypes.ELLIPSIS)

    val GoCallExpr.arguments: List<PsiElement> get() = argumentList?.arguments ?: emptyList()

    val GoIndexOrSliceExpr.isSlice: Boolean get() = hasChildToken(this, GoTypes.COLON)

    /** Index/slice operands and type arguments (expressions or types) in source order, excluding the indexed expression. */
    val GoIndexOrSliceExpr.indices: List<PsiElement>
        get() = children(this, PsiElement::class.java).filter { (it is GoExpression || it is GoType) && it !== expression }

    val GoParenthesesExpr.inner: PsiElement? get() = children(this, PsiElement::class.java).firstOrNull { it is GoExpression || it is GoType }

    val GoFunctionLit.block: GoBlock? get() = children(this, GoBlock::class.java).firstOrNull()

    val GoBinaryExpr.operator: IElementType?
        get() {
            var n = node.firstChildNode
            while (n != null) {
                if (n.psi !is GoExpression && !GoTokenSets.WHITESPACES.contains(n.elementType) && !GoTokenSets.COMMENTS.contains(n.elementType)) return n.elementType
                n = n.treeNext
            }
            return null
        }

    val GoUnaryExpr.operator: IElementType? get() = node.firstChildNode?.elementType

    val GoCompositeLit.literalType: PsiElement?
        get() = children(this, PsiElement::class.java).firstOrNull { it is GoType || it is GoTypeReferenceExpression }

    /** Type arguments of a generic literal type `T[A, B]{...}`: a `TypeArguments` node, or (inlined speculative parse) direct `Type` children. */
    val GoCompositeLit.typeArgumentTypes: List<GoType>?
        get() {
            children(this, GoTypeArguments::class.java).firstOrNull()?.let { return it.typeList }
            if (!hasChildToken(this, GoTypes.LBRACK)) return null
            return children(this, GoType::class.java)
        }

    val GoReferenceExpression.qualifier: GoExpression? get() = expression

    val GoReferenceExpression.referenceName: String? get() = identifier?.text

    val GoKey.referenceExpression: GoReferenceExpression? get() = expression as? GoReferenceExpression

    // --- statements ---

    val GoIfStatement.initStatement: GoStatement? get() = children(this, GoStatement::class.java).firstOrNull { it !is GoBlock && it !is GoElseStatement }

    val GoIfStatement.condition: GoExpression? get() = children(this, GoExpression::class.java).firstOrNull()

    val GoIfStatement.block: GoBlock? get() = children(this, GoBlock::class.java).firstOrNull()

    val GoIfStatement.elseStatement: GoElseStatement? get() = children(this, GoElseStatement::class.java).firstOrNull()

    val GoForStatement.forClause: GoForClause? get() = children(this, GoForClause::class.java).firstOrNull()

    val GoForStatement.rangeClause: GoRangeClause? get() = children(this, GoRangeClause::class.java).firstOrNull()

    val GoForStatement.condition: GoExpression? get() = children(this, GoExpression::class.java).firstOrNull()

    val GoForClause.initStatement: GoStatement? get() = statementList.firstOrNull { it.textRange.startOffset < (expression?.textRange?.startOffset ?: semicolonOffset(this)) }

    private fun semicolonOffset(clause: GoForClause): Int = childToken(clause, GoTypes.SEMICOLON)?.textRange?.startOffset ?: Int.MAX_VALUE

    /**
     * The first child of [cls] in the header of a switch statement, before its `{`. Scanning all
     * children (the case clauses) per lookup was quadratic for switches of thousands of cases
     * (`ssa/rewriteAMD64.go`): every name resolved inside a clause asks for the init statement.
     */
    private fun <T : PsiElement> headerChild(parent: PsiElement, cls: Class<T>): T? {
        var c = parent.firstChild
        while (c != null) {
            if (c.node.elementType === GoTypes.LBRACE) return null
            if (cls.isInstance(c)) return cls.cast(c)
            c = c.nextSibling
        }
        return null
    }

    val GoExprSwitchStatement.initStatement: GoStatement? get() = headerChild(this, GoStatement::class.java)

    val GoExprSwitchStatement.tag: GoExpression? get() = headerChild(this, GoExpression::class.java)

    val GoTypeSwitchStatement.initStatement: GoStatement? get() = headerChild(this, GoStatement::class.java)

    val GoTypeSwitchStatement.guard: GoTypeSwitchGuard? get() = headerChild(this, GoTypeSwitchGuard::class.java)

    val GoTypeCaseClause.types: List<GoType> get() = type?.let { if (it is GoTypeList) it.typeList else listOf(it) } ?: emptyList()

    val GoTypeCaseClause.isDefault: Boolean get() = default != null

    val GoCommClause.recvStatement: GoRecvStatement? get() = commCase?.statement as? GoRecvStatement

    val GoSimpleStatement.expressions: List<GoExpression> get() = leftHandExprList?.expressionList ?: emptyList()

    /** The function-like owner of [element]: declaration or literal, null at package level. */
    fun functionOwner(element: PsiElement): PsiElement? {
        var e: PsiElement? = element.parent
        while (e != null && e !is PsiFile) {
            if (e is GoFunctionOrMethodDeclaration || e is GoFunctionLit) return e
            e = e.parent
        }
        return null
    }

    /** True inside a function body. Stub-backed elements (with a green stub) are never inside bodies, so no AST is loaded. */
    fun isInsideFunctionBody(element: PsiElement): Boolean {
        if (element is com.intellij.extapi.psi.StubBasedPsiElementBase<*> && element.greenStub != null) return false
        if (element is PsiFile) return false
        var e: PsiElement? = element
        while (e != null && e !is PsiFile) {
            if (e is GoBlock) return true
            if (e is com.intellij.extapi.psi.StubBasedPsiElementBase<*> && e.greenStub != null) return false
            e = e.parent
        }
        return false
    }

    /**
     * The body block of the outermost function whose body contains [element] (or is [element]):
     * the body of the top-level function or method, or, in package-level code (a `var` initializer),
     * of the outermost function literal. Function literals inside a body belong to the enclosing
     * top-level function. Null outside bodies (signatures, package-level code); never loads AST for
     * stub-backed elements. Non-null exactly when [isInsideFunctionBody] is true.
     */
    fun outermostBody(element: PsiElement): GoBlock? = outermostBodyNode(element)?.psi as? GoBlock

    /** The AST node of [outermostBody] (no PSI conversion: the cache fast path keys on the node). */
    fun outermostBodyNode(element: PsiElement): com.intellij.lang.ASTNode? {
        if (element is PsiFile) return null
        if (element is com.intellij.extapi.psi.StubBasedPsiElementBase<*> && element.greenStub != null) return null
        val node = element.node ?: return null
        return bodyAbove(node)
    }

    /**
     * Bumped by every Go PSI change and project roots change (`GoTrackers`): [outermostBody] hints
     * and the fast path of the body store are valid only for the stamp they were checked with.
     */
    @Volatile var treeStamp = 0L
        private set

    fun treeChanged() {
        treeStamp++
    }

    private const val BODY_HINT_STEP = 32

    private class BodyHint(val stamp: Long, val body: com.intellij.lang.ASTNode?)

    private val BODY_HINT = com.intellij.openapi.util.Key.create<BodyHint>("gopsi.outermostBodyHint")

    private fun isFunctionDeclaration(type: IElementType) = type === GoTypes.FUNCTION_DECLARATION || type === GoTypes.METHOD_DECLARATION

    /**
     * The walk of [outermostBody] continued from [start] as the child, with no literal body found
     * below it. It runs on AST nodes (a PSI `getParent` costs a cancellation check and a read-access
     * assertion; this runs on every cache lookup). It stops at the first block above [start] (or
     * after [BODY_HINT_STEP] parents: generated code nests thousands of binary expressions) and
     * continues from a hint stored on that node, recomputed after any Go PSI change; a lookup thus
     * walks a few parents. A literal body found below the hinted node counts only when nothing was
     * found above it (outer bodies win).
     */
    private fun bodyAbove(start: com.intellij.lang.ASTNode): com.intellij.lang.ASTNode? {
        var literalBody: com.intellij.lang.ASTNode? = null
        var child = start
        var e = start.treeParent
        var steps = 0
        while (e != null && e !is com.intellij.psi.impl.source.tree.FileElement) {
            val type = e.elementType
            if (child.elementType === GoTypes.BLOCK) {
                if (isFunctionDeclaration(type)) return child
                if (steps > 0) return hintedBodyAbove(child) ?: literalBody
                if (type === GoTypes.FUNCTION_LIT) literalBody = child
            }
            if (isFunctionDeclaration(type)) return literalBody
            child = e
            e = e.treeParent
            if (++steps == BODY_HINT_STEP && e != null && e !is com.intellij.psi.impl.source.tree.FileElement) return hintedBodyAbove(child) ?: literalBody
        }
        return literalBody
    }

    private fun hintedBodyAbove(node: com.intellij.lang.ASTNode): com.intellij.lang.ASTNode? {
        val stamp = treeStamp
        node.getUserData(BODY_HINT)?.let { if (it.stamp == stamp) return it.body }
        val body = bodyAbove(node)
        node.putUserData(BODY_HINT, BodyHint(stamp, body))
        return body
    }

    /** The statement that directly contains [element] within [container], or null. */
    fun statementIn(container: PsiElement, element: PsiElement): PsiElement? {
        var e: PsiElement? = element
        while (e != null && e.parent !== container) e = e.parent
        return e
    }

    fun goFile(element: PsiElement): GoFile? = element.containingFile as? GoFile

    /** Named declarations introduced by a statement (var/const/type/short var declarations). */
    fun declarationsOf(statement: PsiElement): List<GoNamedElement> = when (statement) {
        is GoVarDeclaration -> statement.varSpecList.flatMap { it.varDefinitionList }
        is GoConstDeclaration -> statement.constSpecList.flatMap { it.constDefinitionList }
        is GoTypeDeclaration -> statement.typeSpecList
        is GoShortVarDeclaration -> statement.varDefinitionList
        is GoSimpleStatement -> statement.statement?.let(::declarationsOf) ?: emptyList()
        is GoLabeledStatement -> statement.statement?.let(::declarationsOf) ?: emptyList()
        else -> emptyList()
    }
}
