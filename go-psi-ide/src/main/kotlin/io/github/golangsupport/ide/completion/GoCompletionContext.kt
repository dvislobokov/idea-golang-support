package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * Classification of a completion position (computed once per completion session on the file
 * copy, see [of]). Purely syntactic; semantic questions (expected type, qualifier type) are
 * answered lazily by [GoCompletionSemantics].
 */
class GoCompletionContext private constructor(val parameters: CompletionParameters) {

    enum class Kind {
        /** Comments, ordinary strings, numbers, names being declared: no Go completion. */
        NONE,
        /** Between top-level declarations: `func type var const import package`. */
        TOP_LEVEL,
        /** An expression at the start of a statement: values, statement keywords, snippets. */
        STATEMENT,
        /** Any other expression position (operand, argument, after `=`/`:=`/`return`/`case`). */
        EXPRESSION,
        /** A type position: var/field/param/result/conversion/literal type, type arguments, constraints. */
        TYPE,
        /** The receiver type of a method declaration: types of the current package. */
        RECEIVER_TYPE,
        /** A member after `.` in an expression: fields/methods, package members, method expressions. */
        SELECTOR,
        /** A member after `pkg.` in a type position: types of the package. */
        TYPE_SELECTOR,
        /** A struct literal key (`T{Na<caret>: 1}`); unkeyed elements are [EXPRESSION] with [literalValue]. */
        STRUCT_KEY,
        /** The path string of an import spec. */
        IMPORT_PATH,
        /** An ordinary string literal that is a call argument: Printf verbs when it is the format of a printf-like call ([GoFormatVerbProvider]). */
        STRING_ARGUMENT,
        /** A label after `goto`/`break`/`continue`. */
        LABEL,
        /** The name in the package clause. */
        PACKAGE_CLAUSE,
        /** The name after `func` (nothing to offer). */
        FUNC_NAME,
        /** Directly inside `switch {`/`select {` before any clause: `case`, `default`. */
        SWITCH_BODY,
    }

    val leaf: PsiElement = parameters.position
    val file: GoFile = leaf.containingFile as GoFile
    /** The file being completed; for a dialog's code fragment the file it resolves in ([GoCodeFragments]). */
    val originalFile: GoFile = GoCodeFragments.contextOf(parameters.originalFile) ?: (parameters.originalFile as? GoFile) ?: file
    val offset: Int = parameters.offset

    var kind: Kind = Kind.NONE
        private set

    /** The (unqualified or qualified) reference expression holding the caret. */
    var reference: GoReferenceExpression? = null
        private set

    /** The type reference expression holding the caret (type positions). */
    var typeReference: GoTypeReferenceExpression? = null
        private set

    /** The qualifier before `.` for [Kind.SELECTOR] / [Kind.TYPE_SELECTOR]. */
    val qualifier: GoExpression?
        get() = when (kind) {
            Kind.SELECTOR -> reference?.expression
            Kind.TYPE_SELECTOR -> typeReference?.referenceExpression
            else -> null
        }

    /** The literal value whose element holds the caret (struct literal keys). */
    var literalValue: GoLiteralValue? = null
        private set

    /** The element is a key (`K: v`); otherwise an unkeyed element where keys and values both fit. */
    var keyOnly: Boolean = false
        private set

    /** Labels: the jump statement (`goto`, `break`, `continue`). */
    var jump: GoStatement? = null
        private set

    // --- statement context flags (meaningful for STATEMENT/EXPRESSION) ---
    var insideFunction: Boolean = false
        private set
    var inLoop: Boolean = false
        private set
    var inBreakable: Boolean = false
        private set
    var inCaseClause: Boolean = false
        private set
    var inExprCaseClause: Boolean = false
        private set
    var inConstSpec: Boolean = false
        private set
    /** `if c {} el<caret>`: `else` fits. */
    var afterIfBlock: Boolean = false
        private set
    /** In a `for` header after `for`, `:=` or `=`: `range` fits. */
    var rangeAllowed: Boolean = false
        private set
    /** A constraint position (type parameter list or interface element): `comparable`, `~` terms. */
    var inConstraint: Boolean = false
        private set

    // --- top-level flags ---
    var packageKeywordAllowed: Boolean = false
        private set
    var importKeywordAllowed: Boolean = false
        private set

    /** The enclosing function declaration or literal (copy PSI), null at package level. */
    val functionOwner: PsiElement? by lazy { GoPsiUtil.functionOwner(leaf) }

    val semantics: GoCompletionSemantics by lazy { GoCompletionSemantics(this) }

    init {
        analyze()
    }

    private fun analyze() {
        val type = leaf.node.elementType
        if (leaf is PsiComment || GoTokenSets.COMMENTS.contains(type)) return
        if (GoTokenSets.STRING_LITERALS.contains(type)) {
            if (leaf.parent is GoStringLiteral && leaf.parent.parent is GoImportSpec) kind = Kind.IMPORT_PATH
            else if (leaf.parent is GoStringLiteral && leaf.parent.parent is GoArgumentList) kind = Kind.STRING_ARGUMENT
            return
        }
        if (type != GoTypes.IDENTIFIER) return
        insideFunction = PsiTreeUtil.getParentOfType(leaf, GoBlock::class.java) != null
        when (val parent = leaf.parent) {
            is GoLabelRef -> {
                jump = parent.parent as? GoStatement
                kind = Kind.LABEL
            }
            is GoPackageClause -> kind = Kind.PACKAGE_CLAUSE
            is GoFunctionOrMethodDeclaration -> kind = Kind.FUNC_NAME
            is GoTypeReferenceExpression -> classifyTypeReference(parent)
            is GoReferenceExpression -> classifyReference(parent)
            is PsiErrorElement, is GoFile, is GoImportList -> classifyStray()
            else -> {}
        }
    }

    private fun classifyTypeReference(ref: GoTypeReferenceExpression) {
        typeReference = ref
        if (ref.referenceExpression != null) {
            kind = Kind.TYPE_SELECTOR
            return
        }
        val type = ref.parent
        // `x := Na<caret>{...}` / `for i := ra<caret> {`: parsed as a composite literal type, but an expression is being typed.
        if (type is GoCompositeLit) {
            kind = Kind.EXPRESSION
            computeStatementFlags(type)
            return
        }
        if (PsiTreeUtil.getParentOfType(ref, GoReceiver::class.java, true, GoBlock::class.java) != null) {
            kind = Kind.RECEIVER_TYPE
            return
        }
        inConstraint = PsiTreeUtil.getParentOfType(ref, GoTypeParameters::class.java, GoInterfaceType::class.java) != null ||
            PsiTreeUtil.getParentOfType(ref, GoConstraintElem::class.java) != null
        kind = Kind.TYPE
    }

    private fun classifyReference(ref: GoReferenceExpression) {
        reference = ref
        if (ref.expression != null) {
            kind = Kind.SELECTOR
            return
        }
        val parent = ref.parent
        if (parent is GoTypeReferenceExpression) {
            // The qualifier of `Na<caret>.T` in a type position: package names (and types, harmlessly).
            typeReference = parent
            kind = Kind.TYPE
            return
        }
        if (parent is GoKey && parent.parent is GoElement) {
            literalValue = parent.parent.parent as? GoLiteralValue
            keyOnly = true
            kind = Kind.STRUCT_KEY
            return
        }
        if (parent is GoValue && parent.parent is GoElement && (parent.parent as GoElement).key == null) {
            literalValue = parent.parent.parent as? GoLiteralValue
        }
        kind = Kind.EXPRESSION
        computeStatementFlags(ref)
    }

    /** Statement-level flags for an expression [expr]; promotes [kind] to [Kind.STATEMENT] at a statement start. */
    private fun computeStatementFlags(expr: PsiElement) {
        val list = expr.parent as? GoLeftHandExprList
        val statement = list?.parent as? GoSimpleStatement
        if (statement != null && list.children.count { it is GoExpression } == 1 && statement.children.size == 1) {
            val container = statement.parent
            if (container is GoBlock || container is GoExprCaseClause || container is GoTypeCaseClause || container is GoCommClause || container is GoLabeledStatement) {
                kind = Kind.STATEMENT
                classifyStatementNeighbourhood(statement)
            }
        }
        var e: PsiElement? = expr.parent
        while (e != null && e !is PsiFile) {
            when (e) {
                is GoFunctionLit, is GoFunctionOrMethodDeclaration -> break
                is GoBlock -> if (e.parent is GoForStatement) { inLoop = true; inBreakable = true }
                is GoExprCaseClause -> { inBreakable = true; inCaseClause = true; inExprCaseClause = true }
                is GoTypeCaseClause, is GoCommClause -> { inBreakable = true; inCaseClause = true }
                is GoConstSpec -> inConstSpec = true
            }
            e = e.parent
        }
        // Only the innermost clause decides `fallthrough`/`case`: a statement inside a nested block is not a clause statement.
        val clauseParent = PsiTreeUtil.getParentOfType(expr, GoExprCaseClause::class.java, GoTypeCaseClause::class.java, GoCommClause::class.java, GoBlock::class.java)
        if (clauseParent is GoBlock) {
            inCaseClause = false
            inExprCaseClause = false
        }
        rangeAllowed = isAfterForHeaderStart(leaf)
        // `for i := ra<caret>`: the broken header leaves the operand as a statement of the outer block.
        if (rangeAllowed && kind == Kind.STATEMENT) kind = Kind.EXPRESSION
    }

    /** `switch x {` + caret before any clause (the parser leaves the clause list in error), or `if {} el<caret>`. */
    private fun classifyStatementNeighbourhood(statement: GoSimpleStatement) {
        var prev = statement.prevSibling
        var sawNewline = false
        var sawError = false
        while (prev != null && (prev is PsiWhiteSpace || prev is PsiComment || prev is PsiErrorElement || prev.node.elementType == GoTypes.SEMICOLON_SYNTHETIC)) {
            if (prev.textContains('\n')) sawNewline = true
            if (prev is PsiErrorElement) sawError = true
            prev = prev.prevSibling
        }
        if (prev is GoExprSwitchStatement || prev is GoTypeSwitchStatement || prev is GoSelectStatement) {
            if (prev.lastChild is PsiErrorElement) kind = Kind.SWITCH_BODY
            return
        }
        if (prev is GoIfStatement && !sawNewline && sawError && lastIfOfChain(prev).let { GoPsiUtil.run { it.elseStatement } == null }) {
            afterIfBlock = true
        }
    }

    private fun lastIfOfChain(statement: GoIfStatement): GoIfStatement {
        var current = statement
        while (true) {
            val next = GoPsiUtil.run { current.elseStatement }?.statement as? GoIfStatement ?: return current
            current = next
        }
    }

    /** Tokens before [leaf] are `for`, or `for a, b :=` / `for a, b =` on the same statement. */
    private fun isAfterForHeaderStart(leaf: PsiElement): Boolean {
        var t = prevToken(leaf) ?: return false
        if (t.node.elementType == GoTypes.FOR) return true
        if (t.node.elementType != GoTypes.DEFINE && t.node.elementType != GoTypes.ASSIGN) return false
        while (true) {
            t = prevToken(t) ?: return false
            val et: IElementType = t.node.elementType
            when (et) {
                GoTypes.FOR -> return true
                GoTypes.IDENTIFIER, GoTypes.COMMA, GoTypes.PERIOD, GoTypes.MUL, GoTypes.LBRACK, GoTypes.RBRACK, GoTypes.INT -> continue
                else -> return false
            }
        }
    }

    private fun prevToken(e: PsiElement): PsiElement? {
        var t = PsiTreeUtil.prevLeaf(e)
        while (t != null && (t is PsiWhiteSpace || t is PsiComment || t.textLength == 0)) t = PsiTreeUtil.prevLeaf(t)
        return t
    }

    /** An identifier the parser could not place: top level (between declarations) or a stray token in a block. */
    private fun classifyStray() {
        if (PsiTreeUtil.getParentOfType(leaf, GoBlock::class.java) != null) return
        kind = Kind.TOP_LEVEL
        packageKeywordAllowed = file.packageClause == null
        val declarations: List<PsiElement> = file.functions + file.methods + file.types + file.vars + file.consts
        importKeywordAllowed = file.packageClause != null && declarations.none { it.textRange.startOffset < offset }
    }

    /** True for positions where an expression is expected (statement start included). */
    val isExpression: Boolean get() = kind == Kind.STATEMENT || kind == Kind.EXPRESSION

    companion object {
        private val KEY = Key.create<Cached>("gopsi.completion.context")

        /** A context remembered for exactly one completion session. */
        private class Cached(val parameters: CompletionParameters, val offset: Int, val stamp: Long, val context: GoCompletionContext)

        /**
         * The context of [parameters], computed once per completion session. It is cached on the
         * copy's leaf, but the platform reuses the file copy (and that leaf) between sessions, so the
         * cached value is only reused for the same [CompletionParameters] instance, offset and copy
         * modification stamp; otherwise a session would see the previous session's context.
         */
        fun of(parameters: CompletionParameters): GoCompletionContext? {
            val position = parameters.position
            val file = position.containingFile as? GoFile ?: return null
            val stamp = file.modificationStamp
            position.getUserData(KEY)
                ?.takeIf { it.parameters === parameters && it.offset == parameters.offset && it.stamp == stamp }
                ?.let { return it.context }
            val context = GoCompletionContext(parameters)
            position.putUserData(KEY, Cached(parameters, parameters.offset, stamp, context))
            return context
        }
    }
}
