package io.github.golangsupport.ide.inspections.redundancy

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoEditFix
import io.github.golangsupport.ide.inspections.GoInspectionText
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoArrayOrSliceType
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoChannelType
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionType
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoMapType
import io.github.golangsupport.lang.psi.GoParType
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoPointerType
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoSwitchStatement
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoValue
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.literalType
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoTypePredicates

/** Text helpers of the redundancy inspections. */
internal object GoRedundancyText {
    /** Deletes [start, end) together with the whole lines when nothing else shares them. */
    fun deleteLines(text: CharSequence, start: Int, end: Int): GoEditPlan.Edit {
        val lines = GoInspectionText.wholeLines(text, start, end)
        return if (lines != null) GoEditPlan.Edit(lines.first, lines.last + 1, "") else GoEditPlan.Edit(start, end, "")
    }

    fun normalized(text: String): String = text.filterNot { it.isWhitespace() }

    /** The text of a composite literal's type: everything before its `{`. */
    fun literalTypeText(lit: GoCompositeLit): String? {
        val value = lit.literalValue ?: return null
        return lit.text.substring(0, value.startOffsetInParent).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * GoLand's "Empty declaration" (`GoEmptyDeclaration`): `var ()`, `const ()`, `type ()`, `import ()` declare nothing. Fix: delete it.
 */
class GoEmptyDeclarationInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val (keyword, empty) = when (element) {
            is GoVarDeclaration -> "var" to (element.lparen != null && element.varSpecList.isEmpty())
            is GoConstDeclaration -> "const" to (element.lparen != null && element.constSpecList.isEmpty())
            is GoTypeDeclaration -> "type" to (element.lparen != null && element.typeSpecList.isEmpty())
            is GoImportDeclaration -> "import" to (element.lparen != null && element.importSpecList.isEmpty())
            else -> return
        }
        if (!empty || PsiTreeUtil.findChildOfType(element, PsiComment::class.java) != null) return
        holder.registerProblem(element, "Empty '$keyword' declaration", ProblemHighlightType.LIKE_UNUSED_SYMBOL, FIX)
    }

    private companion object {
        val FIX = GoEditFix("Delete empty declaration") { e ->
            listOf(GoRedundancyText.deleteLines(e.containingFile.viewProvider.contents, e.textRange.startOffset, e.textRange.endOffset))
        }
    }
}

/**
 * GoLand's "Empty slice declared using a literal" (`GoPreferNilSlice`): a local `s := []T{}` or `var s = []T{}` allocates an empty slice
 * where the nil slice `var s []T` does the same for `append`, `len` and `range` (Go Code Review Comments, "Declaring Empty Slices").
 * Fix: replace with the nil slice declaration (for a statement of a statement list, not an `if` / `for` header).
 */
class GoPreferNilSliceInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val (names, values) = when (element) {
            is GoShortVarDeclaration -> element.varDefinitionList to element.expressionList
            is GoVarSpec -> if (element.type != null) return else element.varDefinitionList to element.expressionList
            else -> return
        }
        if (names.size != 1 || values.size != 1 || GoPsiUtil.functionOwner(element) == null) return
        val lit = values[0] as? GoCompositeLit ?: return
        if (sliceTypeText(lit) == null) return
        val fixes = if (rewritable(element)) arrayOf<LocalQuickFix>(FIX) else emptyArray()
        holder.registerProblem(lit, "Empty slice declared using a literal", *fixes)
    }

    companion object {
        /** `[]T` of an empty slice literal `[]T{}`, or null. */
        fun sliceTypeText(lit: GoCompositeLit): String? {
            val type = lit.literalType as? GoArrayOrSliceType ?: return null
            if (type.ellipsis != null || PsiTreeUtil.getChildOfType(type, GoExpression::class.java) != null) return null
            val value = lit.literalValue ?: return null
            if (value.elements.isNotEmpty() || PsiTreeUtil.findChildOfType(value, PsiComment::class.java) != null) return null
            return type.text
        }

        /** The statement holding a `:=` (the parser may or may not wrap it in a simple statement). */
        private fun statementOf(declaration: GoShortVarDeclaration): PsiElement = declaration.parent as? GoSimpleStatement ?: declaration

        private fun rewritable(declaration: PsiElement): Boolean = when (declaration) {
            is GoShortVarDeclaration -> statementOf(declaration).parent.let {
                it is GoBlock || it is GoExprCaseClause || it is GoTypeCaseClause || it is GoCommClause
            }
            is GoVarSpec -> (declaration.parent as? GoVarDeclaration)?.lparen == null
            else -> false
        }

        private val FIX = GoEditFix("Replace with nil slice declaration") { lit ->
            val declaration = PsiTreeUtil.getParentOfType(lit, GoShortVarDeclaration::class.java, GoVarSpec::class.java) ?: return@GoEditFix null
            if (!rewritable(declaration)) return@GoEditFix null
            val type = sliceTypeText(lit as? GoCompositeLit ?: return@GoEditFix null) ?: return@GoEditFix null
            when (declaration) {
                is GoShortVarDeclaration -> {
                    val name = declaration.varDefinitionList.single().name
                    val statement = statementOf(declaration)
                    listOf(GoEditPlan.Edit(statement.textRange.startOffset, statement.textRange.endOffset, "var $name $type"))
                }
                is GoVarSpec -> {
                    val name = declaration.varDefinitionList.single().name
                    listOf(GoEditPlan.Edit(declaration.textRange.startOffset, declaration.textRange.endOffset, "$name $type"))
                }
                else -> null
            }
        }
    }
}

/**
 * GoLand's "Redundant comma" (`GoRedundantComma`): a trailing comma before `)`, `}` or `]` on the same line (`f(a, b,)`, `T{1, 2,}`);
 * gofmt keeps it, but it is only needed before a line break. Fix: remove it.
 */
class GoRedundantCommaInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element.node?.elementType != GoTypes.COMMA) return
        var next = element.nextSibling
        while (next is PsiWhiteSpace && !next.textContains('\n')) next = next.nextSibling
        if (next == null || next.node.elementType !in CLOSERS) return
        holder.registerProblem(element, "Redundant comma", ProblemHighlightType.LIKE_UNUSED_SYMBOL, FIX)
    }

    private companion object {
        val CLOSERS = TokenSet.create(GoTypes.RPAREN, GoTypes.RBRACE, GoTypes.RBRACK)
        val FIX = GoEditFix("Remove redundant comma") { listOf(GoEditPlan.Edit(it.textRange.startOffset, it.textRange.endOffset, "")) }
    }
}

/**
 * GoLand's "Redundant semicolon" (`GoRedundantSemicolon`): an explicit `;` at the end of a line (a line comment may follow), before
 * `}` / `)` or before another `;`. `for` clause semicolons are syntax and are never reported. Fix: remove it.
 */
class GoRedundantSemicolonInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element.node?.elementType != GoTypes.SEMICOLON || element.parent is GoForClause || element.parent is GoForStatement) return
        if (!redundant(element)) return
        holder.registerProblem(element, "Redundant semicolon", ProblemHighlightType.LIKE_UNUSED_SYMBOL, FIX)
    }

    private fun redundant(semicolon: PsiElement): Boolean {
        var next = PsiTreeUtil.nextLeaf(semicolon)
        while (next != null) {
            val type = next.node.elementType
            when {
                next is PsiWhiteSpace -> if (next.textContains('\n')) return true
                type == GoTypes.LINE_COMMENT -> return true
                type == GoTypes.SEMICOLON_SYNTHETIC -> return true
                type == GoTypes.RBRACE || type == GoTypes.RPAREN || type == GoTypes.SEMICOLON -> return true
                type == GoTypes.BLOCK_COMMENT -> {}
                else -> return false
            }
            next = PsiTreeUtil.nextLeaf(next)
        }
        return true
    }

    private companion object {
        val FIX = GoEditFix("Remove redundant semicolon") { listOf(GoEditPlan.Edit(it.textRange.startOffset, it.textRange.endOffset, "")) }
    }
}

/**
 * GoLand's "Redundant parentheses" (`GoRedundantParens`, group General): parentheses around an operand that needs none (a name, literal,
 * call, selector, index, type assertion or another parenthesised expression), around a whole condition, return value, assigned value,
 * initialiser or argument, and around a named type in a declaration (`x (int)`). Kept: a composite literal inside an `if` / `for` /
 * `switch` header (the parser needs them there), `(*T)` and other non-name types in expressions, channel and function types. Fix:
 * remove the parentheses.
 */
class GoRedundantParensInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val redundant = when (element) {
            is GoParenthesesExpr -> redundantExpr(element)
            is GoParType -> redundantType(element)
            else -> false
        }
        if (redundant) holder.registerProblem(element, "Redundant parentheses", ProblemHighlightType.LIKE_UNUSED_SYMBOL, FIX)
    }

    companion object {
        fun redundantExpr(paren: GoParenthesesExpr): Boolean {
            val inner = paren.inner as? GoExpression ?: return false
            if (inHeader(paren) && (inner is GoCompositeLit || PsiTreeUtil.findChildOfType(inner, GoCompositeLit::class.java) != null)) return false
            if (isPrimary(inner)) return true
            val parent = paren.parent
            return when (parent) {
                is GoIfStatement, is GoForStatement, is GoExprSwitchStatement -> true
                is GoReturnStatement, is GoArgumentList, is GoValue -> true
                is GoAssignmentStatement -> parent.expressionList.any { it === paren }
                is GoShortVarDeclaration -> parent.expressionList.any { it === paren }
                is GoVarSpec -> parent.expressionList.any { it === paren }
                is GoConstSpec -> parent.expressionList.any { it === paren }
                else -> false
            }
        }

        private fun isPrimary(e: GoExpression): Boolean = e is GoReferenceExpression || e is GoLiteral || e is GoStringLiteral || e is GoCallExpr ||
            e is GoIndexOrSliceExpr || e is GoParenthesesExpr || e is GoTypeAssertionExpr || e is GoCompositeLit

        /** Whether [e] sits in the header of an `if` / `for` / `switch` (outside its body). */
        private fun inHeader(e: PsiElement): Boolean {
            var p: PsiElement? = e.parent
            while (p != null && p !is GoFile) {
                if (p is GoBlock || p is GoLiteralValue || p is GoArgumentList) return false
                if (p is GoIfStatement || p is GoForStatement || p is GoSwitchStatement || p is GoExprSwitchStatement || p is GoTypeSwitchStatement) return true
                p = p.parent
            }
            return false
        }

        fun redundantType(par: GoParType): Boolean {
            val inner = par.type ?: return false
            if (inner is GoChannelType || inner is GoFunctionType) return false
            if (inner !is GoParType && inner.typeReferenceExpression == null) return false
            return when (par.parent) {
                is GoParameterDeclaration, is GoVarSpec, is GoFieldDeclaration, is GoArrayOrSliceType, is GoMapType, is GoPointerType, is GoParType, is GoTypeSpec -> true
                else -> false
            }
        }

        private val FIX = GoEditFix("Remove redundant parentheses") { e ->
            val inner = when (e) {
                is GoParenthesesExpr -> e.inner
                is GoParType -> e.type
                else -> null
            } ?: return@GoEditFix null
            listOf(GoEditPlan.Edit(e.textRange.startOffset, e.textRange.endOffset, inner.text))
        }
    }
}

/**
 * GoLand's "Redundant import alias" (`GoRedundantImportAlias`): `import fmt "fmt"`, an alias equal to the name of the imported package
 * (the resolved package's name; the last path segment when it cannot be resolved). Fix: remove the alias.
 */
class GoRedundantImportAliasInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoImportSpec) return
        val alias = element.identifier ?: return
        val name = alias.text
        if (name == "_" || name == ".") return
        val packageName = GoPackageModel.getInstance(file.project).resolveImport(element.path, file)?.name ?: element.path.substringAfterLast('/')
        if (name != packageName) return
        holder.registerProblem(element, "Redundant alias '$name'", ProblemHighlightType.LIKE_UNUSED_SYMBOL, TextRange(0, alias.textLength), FIX)
    }

    private companion object {
        val FIX = GoEditFix("Remove redundant alias") { spec ->
            val alias = (spec as? GoImportSpec)?.identifier ?: return@GoEditFix null
            val path = spec.stringLiteral
            listOf(GoEditPlan.Edit(alias.textRange.startOffset, path.textRange.startOffset, ""))
        }
    }
}

/**
 * GoLand's "Redundant types in composite literals" (`GoRedundantTypeDeclInCompositeLit`, gofmt -s): an element, key or value of a
 * slice, array or map literal that repeats the element type (`[]T{T{1}}`, `[]*T{&T{1}}`, `map[K]V{K{}: V{}}`). Fix: remove the type
 * (and the `&`).
 */
class GoRedundantTypeDeclInCompositeLitInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoCompositeLit) return
        val range = redundantRange(element) ?: return
        val anchor = if (range.startOffset < element.textRange.startOffset) element.parent else element
        holder.registerProblem(anchor, "Redundant type declaration", ProblemHighlightType.LIKE_UNUSED_SYMBOL, range.shiftLeft(anchor.textRange.startOffset), FIX)
    }

    companion object {
        /** The absolute range of the redundant `T` / `&T` of [lit] as an element of an enclosing literal, or null. */
        fun redundantRange(lit: GoCompositeLit): TextRange? {
            val holder = lit.parent.let { if (it is GoUnaryExpr && it.and != null) it.parent else it }
            val pointer = lit.parent is GoUnaryExpr
            val slot = holder as? GoValue ?: holder as? GoKey ?: return null
            val outer = slot.parent?.parent as? GoLiteralValue ?: return null
            val outerLit = outer.parent as? GoCompositeLit ?: return null
            val expected = when (val type = outerLit.literalType) {
                is GoArrayOrSliceType -> type.type
                is GoMapType -> if (slot is GoKey) type.typeList.getOrNull(0) else type.typeList.getOrNull(1)
                else -> null
            } ?: return null
            val own = GoRedundancyText.literalTypeText(lit) ?: return null
            val expectedText = if (pointer) (expected as? GoPointerType)?.type?.text ?: return null else expected.text
            if (GoRedundancyText.normalized(own) != GoRedundancyText.normalized(expectedText)) return null
            val start = if (pointer) lit.parent.textRange.startOffset else lit.textRange.startOffset
            return TextRange(start, lit.literalValue!!.textRange.startOffset)
        }

        private val FIX = GoEditFix("Remove redundant type") { e ->
            val lit = e as? GoCompositeLit ?: (e as? GoUnaryExpr)?.expression as? GoCompositeLit ?: return@GoEditFix null
            val range = redundantRange(lit) ?: return@GoEditFix null
            listOf(GoEditPlan.Edit(range.startOffset, range.endOffset, ""))
        }
    }
}

/**
 * GoLand's "Type can be omitted" (`GoVarAndConstTypeMayBeOmitted`): `var s string = fmt.Sprint(x)`, `var n int = 1`: the declared type is
 * the one the values have anyway (identical for typed values; the default type for untyped constants of a `var`). Constants keep a type
 * for an untyped value (dropping it would make the constant untyped). Fix: remove the type.
 */
class GoVarAndConstTypeMayBeOmittedInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (!omittable(element)) return
        val type = (element as? GoVarSpec)?.type ?: (element as GoConstSpec).type ?: return
        holder.registerProblem(type, "Type can be omitted", ProblemHighlightType.LIKE_UNUSED_SYMBOL, FIX)
    }

    companion object {
        fun omittable(spec: PsiElement): Boolean {
            val (names, values) = when (spec) {
                is GoVarSpec -> spec.varDefinitionList to spec.expressionList
                is GoConstSpec -> spec.constDefinitionList to spec.expressionList
                else -> return false
            }
            if (values.isEmpty() || values.size != names.size || ((spec as? GoVarSpec)?.type ?: (spec as? GoConstSpec)?.type) == null) return false
            val semantic = GoSemanticService.getInstance(spec.project)
            return names.indices.all { i ->
                val declared = semantic.declarationType(names[i])
                val value = semantic.typeOf(values[i])
                if (!GoTypePredicates.isKnown(declared) || !GoTypePredicates.isKnown(value)) return@all false
                if (GoTypePredicates.isUntyped(value)) spec is GoVarSpec && (value as GoBasicType).kind != io.github.golangsupport.semantic.types.GoBasicKind.UNTYPED_NIL &&
                    GoTypePredicates.identical(GoTypePredicates.defaultType(value), declared)
                else GoTypePredicates.identical(value, declared)
            }
        }

        private val FIX = GoEditFix("Remove type") { type ->
            val spec = type.parent
            if (!omittable(spec)) return@GoEditFix null
            var start = type.textRange.startOffset
            val text = type.containingFile.viewProvider.contents
            while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            listOf(GoEditPlan.Edit(start, type.textRange.endOffset, ""))
        }
    }
}

/**
 * GoLand's "Unused type parameter" (`GoUnusedTypeParameter`): a type parameter of a function or a type that nothing in its signature,
 * constraints, body or type mentions. Fix: rename it to `_`.
 */
class GoUnusedTypeParameterInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoTypeParamDefinition) return
        val name = element.name ?: return
        if (name == "_") return
        val owner = PsiTreeUtil.getParentOfType(element, GoFunctionDeclaration::class.java, GoTypeSpec::class.java) ?: return
        if (used(owner, element, name)) return
        holder.registerProblem(element.nameIdentifier ?: element, "Unused type parameter '$name'", ProblemHighlightType.LIKE_UNUSED_SYMBOL, FIX)
    }

    private fun used(owner: PsiElement, def: GoTypeParamDefinition, name: String): Boolean =
        PsiTreeUtil.findChildrenOfAnyType(owner, GoTypeReferenceExpression::class.java, GoReferenceExpression::class.java).any { ref ->
            val qualified = (ref as? GoTypeReferenceExpression)?.referenceExpression != null || (ref as? GoReferenceExpression)?.expression != null
            !qualified && ref.node.findChildByType(GoTypes.IDENTIFIER)?.text == name && !PsiTreeUtil.isAncestor(def, ref, false)
        }

    private companion object {
        val FIX = GoEditFix("Rename to '_'") { id ->
            listOf(GoEditPlan.Edit(id.textRange.startOffset, id.textRange.endOffset, "_"))
        }
    }
}
