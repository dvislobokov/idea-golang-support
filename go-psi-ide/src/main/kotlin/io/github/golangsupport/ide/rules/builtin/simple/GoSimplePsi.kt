package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoExpressionRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoStatementRule
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGotoStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoSwitchStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.indices

/** staticcheck S-checks (gosimple before golangci-lint v2): one linter, the old name accepted by `//nolint`, weak warnings. */
abstract class GoSimpleStatementRule : GoStatementRule() {
    override val linter: String get() = "staticcheck"
    override val linterAliases: Set<String> get() = GoSimplePsi.GOSIMPLE
    override val defaultLevel: GoRuleLevel get() = GoRuleLevel.WEAK_WARNING
    override val needs: Set<GoRuleNeed> get() = GoSimplePsi.TYPES
}

/** [GoSimpleStatementRule] for calls. */
abstract class GoSimpleCallRule : GoCallRule() {
    override val linter: String get() = "staticcheck"
    override val linterAliases: Set<String> get() = GoSimplePsi.GOSIMPLE
    override val defaultLevel: GoRuleLevel get() = GoRuleLevel.WEAK_WARNING
    override val needs: Set<GoRuleNeed> get() = GoSimplePsi.TYPES
}

/** [GoSimpleStatementRule] for expressions. */
abstract class GoSimpleExpressionRule : GoExpressionRule() {
    override val linter: String get() = "staticcheck"
    override val linterAliases: Set<String> get() = GoSimplePsi.GOSIMPLE
    override val defaultLevel: GoRuleLevel get() = GoRuleLevel.WEAK_WARNING
    override val needs: Set<GoRuleNeed> get() = GoSimplePsi.TYPES
}

/**
 * A quick fix that recomputes its text edits from the reported element when applied ([plan] returns null when the code changed
 * so that the rewrite is no longer safe). Holds no PSI and no pass context: [plan] is a function of the rule and gets a fresh context.
 */
class GoRewriteFix(private val name: String, private val plan: (PsiElement, GoRuleContext) -> List<GoEditPlan.Edit>?) : LocalQuickFix {
    override fun getFamilyName(): String = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile as? GoFile ?: return
        val edits = plan(element, GoRuleContext(file, null, false)) ?: return
        GoEditText.apply(file, edits)
    }

    companion object {
        /** The fix when [plan] has something to do for [element] now, else none. */
        fun offer(name: String, element: PsiElement, ctx: GoRuleContext, plan: (PsiElement, GoRuleContext) -> List<GoEditPlan.Edit>?): Array<LocalQuickFix> =
            if (plan(element, ctx) != null) arrayOf(GoRewriteFix(name, plan)) else emptyArray()
    }
}

/** PSI and text helpers of the staticcheck S-rules. */
internal object GoSimplePsi {
    val GOSIMPLE: Set<String> = setOf("gosimple")
    val TYPES: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES)

    fun unparen(e: GoExpression?): GoExpression? = GoLintPsi.unparen(e)

    /** `_` as a definition or an expression. */
    fun isBlank(e: PsiElement?): Boolean = when (e) {
        is GoVarDefinition -> e.identifier.text == "_"
        is GoReferenceExpression -> e.expression == null && e.identifier.text == "_"
        else -> false
    }

    /** An unqualified name. */
    fun isIdent(e: PsiElement?): Boolean = e is GoReferenceExpression && e.expression == null

    fun identName(e: PsiElement?): String? = (e as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text

    /** The single declaration [e] (an unqualified name) resolves to. */
    fun target(e: PsiElement?, ctx: GoRuleContext): PsiElement? {
        val ref = e as? GoReferenceExpression ?: return null
        if (ref.expression != null) return null
        return ctx.resolve(ref).singleOrNull()
    }

    /** [e] is the predeclared [name] (`true`, `nil`, `append`), not a local redeclaration. */
    fun isBuiltin(e: PsiElement?, name: String, ctx: GoRuleContext): Boolean {
        if (identName(unparen(e as? GoExpression)) != name) return false
        val t = target(unparen(e as GoExpression), ctx) ?: return false
        return GoLintPsi.isBuiltin(t)
    }

    /** A call of the builtin [name] (`append`, `len`, `delete`). */
    fun isBuiltinCall(e: PsiElement?, name: String, ctx: GoRuleContext): Boolean =
        e is GoCallExpr && isBuiltin(e.expression, name, ctx)

    /** `pkg.Name(...)` of the package [path]: the name, else null. */
    fun packageFunction(call: GoCallExpr, path: String, names: Set<String>, ctx: GoRuleContext): String? {
        val ref = unparen(call.expression) as? GoReferenceExpression ?: return null
        val name = ref.identifier.text
        if (name !in names || ref.expression !is GoReferenceExpression) return null
        val t = ctx.resolve(ref).singleOrNull() ?: return null
        if (t !is GoFunctionOrMethodDeclaration) return null
        return name.takeIf { GoLintPsi.packagePath(t) == path }
    }

    fun args(call: GoCallExpr): List<PsiElement> = call.arguments

    /** No space differences: `a [ i ]` and `a[i]` are the same text. */
    fun norm(e: PsiElement): String = e.text.filterNot { it.isWhitespace() }

    /** The same expression written twice: names resolving to the same declaration, otherwise identical text. */
    fun sameNonDynamic(a: PsiElement?, b: PsiElement?, ctx: GoRuleContext): Boolean {
        val x = unparen(a as? GoExpression) ?: return false
        val y = unparen(b as? GoExpression) ?: return false
        if (isIdent(x) || isIdent(y)) {
            if (!isIdent(x) || !isIdent(y) || identName(x) != identName(y)) return false
            val t = target(x, ctx) ?: return false
            return t == target(y, ctx)
        }
        return when (x) {
            is GoReferenceExpression, is GoIndexOrSliceExpr, is GoLiteral, is GoStringLiteral -> x.javaClass == y.javaClass && norm(x) == norm(y) && isPure(x)
            else -> false
        }
    }

    /** Whether evaluating [e] cannot have side effects (syntax only: names, selectors, indexing, literals, `len` / `cap`). */
    fun isPure(e: PsiElement?): Boolean = when (e) {
        null -> true
        is GoReferenceExpression -> isPure(e.expression)
        is GoLiteral, is GoStringLiteral -> true
        is GoParenthesesExpr -> isPure(GoLintPsi.unparen(e))
        is GoIndexOrSliceExpr -> isPure(e.expression) && e.indices.all { it !is GoExpression || isPure(it) }
        is GoUnaryExpr -> e.arrow == null && e.and == null && isPure(e.expression)
        is io.github.golangsupport.lang.psi.GoBinaryExpr -> e.expressionList.all(::isPure)
        is GoCallExpr -> (identName(e.expression) == "len" || identName(e.expression) == "cap") && e.arguments.all { it is GoExpression && isPure(it) }
        else -> false
    }

    /** Whether [scope] mentions a name resolving to [target] (a cheap text check first). */
    fun refersTo(scope: PsiElement, target: PsiElement, ctx: GoRuleContext): Boolean {
        val name = (target as? GoNamedElement)?.name ?: return true
        if (!scope.text.contains(name)) return false
        return PsiTreeUtil.findChildrenOfType(scope, GoReferenceExpression::class.java).plus(listOfNotNull(scope as? GoReferenceExpression))
            .any { it.expression == null && it.identifier.text == name && target(it, ctx).let { t -> t == null || t == target } }
    }

    /** Whether [scope] mentions [name] at all (an identifier token). */
    fun mentions(scope: PsiElement, name: String): Boolean =
        scope.text.contains(name) && PsiTreeUtil.collectElements(scope) { it.node.elementType == GoTypes.IDENTIFIER && it.text == name }.isNotEmpty()

    /** A comment anywhere in [element]. */
    fun hasComments(element: PsiElement): Boolean = PsiTreeUtil.findChildOfType(element, PsiComment::class.java) != null

    /** A comment between the end of [a] and the start of [b]. */
    fun hasCommentsBetween(a: PsiElement, b: PsiElement): Boolean {
        var leaf = PsiTreeUtil.nextLeaf(a)
        val end = b.textRange.startOffset
        while (leaf != null && leaf.textRange.startOffset < end) {
            if (leaf is PsiComment) return true
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return false
    }

    /** A comment in [container] outside every range of [keep]: rewriting [container] to text built from [keep] would lose it. */
    fun commentsOutside(container: PsiElement, keep: List<TextRange>): Boolean =
        PsiTreeUtil.findChildrenOfType(container, PsiComment::class.java).any { c -> keep.none { it.contains(c.textRange) } }

    /** The statement list [statement] is in (a block or a clause), or null. */
    fun list(statement: PsiElement): PsiElement? = statement.parent?.takeIf { GoEditText.isStatementList(it) }

    fun statements(list: PsiElement): List<GoStatement> = GoEditText.statements(list)

    /** The statement after [statement] in its list. */
    fun next(statement: GoStatement): GoStatement? {
        val list = list(statement) ?: return null
        val all = statements(list)
        return all.getOrNull(all.indexOf(statement) + 1)
    }

    /** The text of [element] with the lines after its first shifted by [delta] tabs (it moves to a shallower or deeper level). */
    fun reindented(element: PsiElement, delta: Int): String {
        val file = element.containingFile
        val text = file.node.chars
        val range = element.textRange
        val eol = GoEditText.lineEnd(text, range.startOffset)
        if (eol >= range.endOffset || delta == 0) return element.text
        return text.subSequence(range.startOffset, eol).toString() + "\n" + GoEditText.shift(file, eol + 1, range.endOffset, delta)
    }

    /** Replace [statement] by [text] (its first line without indentation, later lines indented). */
    fun replace(statement: PsiElement, text: String): List<GoEditPlan.Edit> = listOf(GoEditText.replaceStatement(statement, text))

    /** The `for` keyword of a for statement etc.: a short range for the problem. */
    fun keywordRange(statement: PsiElement): TextRange = TextRange(0, statement.firstChild?.textLength ?: statement.textLength)

    // ---- for clauses

    /** init; cond; post of a three-clause `for`. */
    class ForParts(val init: GoStatement?, val cond: GoExpression?, val post: GoStatement?)

    fun forParts(clause: GoForClause): ForParts? {
        val semis = clause.node.getChildren(null).filter { it.elementType == GoTypes.SEMICOLON }.map { it.startOffset }
        if (semis.size != 2) return null
        val statements = clause.statementList
        return ForParts(statements.firstOrNull { it.textRange.startOffset < semis[0] }, clause.expression, statements.firstOrNull { it.textRange.startOffset > semis[1] })
    }

    /** `a[i]` (not a slice): the operand and the index. */
    fun index(e: PsiElement?): Pair<GoExpression, GoExpression>? {
        val x = unparen(e as? GoExpression) as? GoIndexOrSliceExpr ?: return null
        if (GoPsiUtil.hasChildToken(x, GoTypes.COLON)) return null
        val operand = x.expression ?: return null
        val i = x.indices.singleOrNull() as? GoExpression ?: return null
        return operand to i
    }

    /** `s[lo:hi]` (two-index slice): (operand, low, high). */
    class Slice(val operand: GoExpression, val low: GoExpression?, val high: GoExpression?)

    fun slice(e: PsiElement?): Slice? {
        val x = unparen(e as? GoExpression) as? GoIndexOrSliceExpr ?: return null
        val colons = x.node.getChildren(null).filter { it.elementType == GoTypes.COLON }
        if (colons.size != 1) return null
        val colon = colons[0].startOffset
        val operand = x.expression ?: return null
        val parts = x.indices.filterIsInstance<GoExpression>()
        if (parts.size != x.indices.size) return null
        return Slice(operand, parts.firstOrNull { it.textRange.endOffset <= colon }, parts.firstOrNull { it.textRange.startOffset > colon })
    }

    /** A simple `lhs = rhs` (one of each, plain `=`). */
    fun simpleAssignment(statement: PsiElement?): Pair<GoExpression, GoExpression>? {
        val a = statement as? GoAssignmentStatement ?: return null
        if (a.assignOp.assign == null) return null
        val lhs = a.leftHandExprList?.expressionList?.singleOrNull() ?: return null
        val rhs = a.expressionList.singleOrNull() ?: return null
        return lhs to rhs
    }

    /** The expression of an expression statement `f()`. */
    fun expressionStatement(statement: PsiElement?): GoExpression? {
        val s = statement as? GoSimpleStatement ?: return null
        if (s.statement != null) return null
        return s.leftHandExprList?.expressionList?.singleOrNull()
    }

    /**
     * `if _, ok := m[k]; ok` (the condition the `ok` of the init statement): the map and the key, or null. The `ok` must be a plain
     * name of the if's own definition.
     */
    fun mapLookupGuard(statement: GoIfStatement, ctx: GoRuleContext): Pair<GoExpression, GoExpression>? {
        val init = statement.initStatement as? GoShortVarDeclaration ?: return null
        val defs = init.varDefinitionList
        if (defs.size != 2 || !isBlank(defs[0]) || isBlank(defs[1])) return null
        val lookup = init.expressionList.singleOrNull() ?: return null
        val (map, key) = index(lookup) ?: return null
        if (target(statement.condition, ctx) != defs[1]) return null
        return map to key
    }

    fun intLiteral(e: PsiElement?, value: String): Boolean = (unparen(e as? GoExpression) as? GoLiteral)?.int?.text == value

    /** A shadowing declaration of the builtin [name] anywhere in the file (then a fix writing `name(...)` would call it). */
    fun shadowsBuiltin(file: PsiFile, name: String): Boolean =
        file.text.contains(name) && PsiTreeUtil.findChildrenOfType(file, GoNamedElement::class.java).any { it.name == name }

    // ---- moving a clause body into the enclosing statement list

    /** An unlabeled `break` of [body] that leaves the statement owning it (not one of a nested loop, switch, select or function). */
    fun hasOwnBreak(body: List<PsiElement>): Boolean = body.any { s ->
        PsiTreeUtil.collectElementsOfType(s, GoBreakStatement::class.java).any { b -> b.labelRef == null && breakTarget(b, s) == null }
    }

    /** The loop / switch / select inside [top] that an unlabeled `break` [b] leaves, or null when it leaves something above [top]. */
    private fun breakTarget(b: PsiElement, top: PsiElement): PsiElement? {
        var e: PsiElement? = b.parent
        while (e != null) {
            if (e is GoForStatement || e is GoSwitchStatement || e is GoExprSwitchStatement || e is GoTypeSwitchStatement || e is GoSelectStatement || e is GoFunctionLit) return e
            if (e === top) return null
            e = e.parent
        }
        return null
    }

    /**
     * Whether declaring [names] directly in the list of [statement] (replacing it) keeps the program meaning: no other statement of the
     * list declares them, no later statement mentions them (it may mean an outer variable now shadowed), a function body has no
     * parameter of the name, a type switch clause does not bind it, and there is no `goto` around (it must not jump over a new variable).
     */
    fun canDeclareInPlaceOf(statement: GoStatement, names: Collection<String>): Boolean {
        if (names.isEmpty()) return true
        if (statement.parent is GoLabeledStatement) return false
        val list = list(statement) ?: return false
        val all = statements(list)
        val i = all.indexOf(statement)
        if (i < 0) return false
        for ((j, s) in all.withIndex()) {
            if (j == i) continue
            if (GoPsiUtil.declarationsOf(s).any { it.name in names }) return false
            if (j > i && names.any { mentions(s, it) }) return false
        }
        val owner = list.parent
        if (list is GoBlock && (owner is GoFunctionOrMethodDeclaration || owner is GoFunctionLit)) {
            val signature = (owner as? GoFunctionOrMethodDeclaration)?.signature ?: (owner as? GoFunctionLit)?.signature
            val params = PsiTreeUtil.findChildrenOfType(signature, GoParamDefinition::class.java).map { it.name } +
                PsiTreeUtil.getChildrenOfType(owner, GoReceiver::class.java).orEmpty().map { it.name }
            if (params.any { it in names }) return false
        }
        if (list is GoTypeCaseClause) {
            val bound = (list.parent as? GoTypeSwitchStatement)?.guard?.varDefinition?.name
            if (bound != null && bound in names) return false
        }
        val function = GoPsiUtil.functionOwner(statement) ?: return false
        return PsiTreeUtil.findChildOfType(function, GoGotoStatement::class.java) == null
    }

    /**
     * The edits that replace a single-case `select` [select] by [first] (the channel operation, or `time.Sleep(d)`) followed by the
     * statements of [clause]; null when the body breaks out of the select, the moved declarations would clash, or comments would be lost.
     */
    fun inlineClause(select: GoSelectStatement, clause: GoCommClause, first: String, declared: Collection<String>): List<GoEditPlan.Edit>? {
        val body = clause.statementList
        if (hasOwnBreak(body)) return null
        val colon = clause.colon ?: return null
        val end = GoEditText.contentEnd(clause)
        if (commentsOutside(select, listOf(TextRange(colon.textRange.endOffset, maxOf(end, colon.textRange.endOffset))))) return null
        val names = declared + body.flatMap { s -> GoPsiUtil.declarationsOf(s).mapNotNull { it.name } }
        if (!canDeclareInPlaceOf(select, names.filter { it != "_" })) return null
        val file = select.containingFile
        val indent = GoEditText.indentOf(file.node.chars, select.textRange.startOffset)
        val rest = if (end > colon.textRange.endOffset) GoEditText.body(file, colon.textRange.endOffset, end, -1, indent) else ""
        return replace(select, if (rest.isEmpty()) first else "$first\n$rest")
    }

    /** Clause lists `case` bodies belong to (not `select`): S1023's break check. */
    fun isSwitchClause(e: PsiElement?): Boolean = e is GoExprCaseClause || e is GoTypeCaseClause

    // ---- calls and expressions (batch B9)

    /** The simple name of the function or method [call] calls (`Index` of `strings.Index(...)`), read before anything is resolved. */
    fun calleeName(call: GoCallExpr): String? = GoLintPsi.calleeReference(call)?.identifier?.text

    /** `path.Name` of a function, `path.Type.Name` of a method or of an interface method; null for anything else. */
    fun memberKey(e: PsiElement?): String? {
        val named = e as? GoNamedElement ?: return null
        val name = named.name ?: return null
        val path = GoLintPsi.packagePath(named) ?: return null
        return when (named) {
            is GoMethodDeclaration -> "$path.${named.receiverTypeName ?: return null}.$name"
            is GoFunctionDeclaration -> "$path.$name"
            is GoMethodSpec -> "$path.${PsiTreeUtil.getParentOfType(named, GoTypeSpec::class.java)?.name ?: return null}.$name"
            else -> null
        }
    }

    /** [memberKey] of what [call] calls when its simple name is one of [names] (checked before resolving), else null. */
    fun callee(call: GoCallExpr, names: Set<String>, ctx: GoRuleContext): String? {
        val ref = GoLintPsi.calleeReference(call) ?: return null
        if (ref.identifier.text !in names) return null
        return memberKey(ctx.resolve(ref).singleOrNull())
    }

    /** Whether [e] is a call of [key] (`fmt.Sprintf`, `time.Time.Sub`); like staticcheck's patterns, [e] itself is not unwrapped from parentheses. */
    fun isCallTo(e: PsiElement?, key: String, ctx: GoRuleContext): Boolean {
        val call = e as? GoCallExpr ?: return false
        val ref = GoLintPsi.calleeReference(call) ?: return false
        if (ref.identifier.text != key.substringAfterLast('.')) return false
        return memberKey(ctx.resolve(ref).singleOrNull()) == key
    }

    /** The qualifier the callee of [call] is written with, dot included (`strings.`, `str.`), `""` for an unqualified name; null for `x.y.F`. */
    fun qualifier(call: GoCallExpr): String? {
        val ref = GoLintPsi.calleeReference(call) ?: return null
        val q = ref.expression ?: return ""
        return if (q is GoReferenceExpression && q.expression == null) q.text + "." else null
    }

    /** The text between the parentheses of [call]'s argument list, as written. */
    fun argumentsText(call: GoCallExpr): String? {
        val list = call.argumentList ?: return null
        val r = list.rparen ?: return null
        return call.containingFile.node.chars.subSequence(list.lparen.textRange.endOffset, r.textRange.startOffset).toString()
    }

    /** The range between the parentheses of [call]'s argument list. */
    fun argumentsRange(call: GoCallExpr): TextRange? {
        val list = call.argumentList ?: return null
        val r = list.rparen ?: return null
        return TextRange(list.lparen.textRange.endOffset, r.textRange.startOffset)
    }

    /** Replaces [element] by [text]. */
    fun replaceWith(element: PsiElement, text: String): List<GoEditPlan.Edit> =
        listOf(GoEditPlan.Edit(element.textRange.startOffset, element.textRange.endOffset, text))

    /** An expression that needs no parentheses as the operand of `.`, `!` or `*`. */
    fun isPrimary(e: GoExpression): Boolean = e is GoReferenceExpression || e is GoCallExpr || e is GoParenthesesExpr || e is GoIndexOrSliceExpr ||
        e is GoTypeAssertionExpr || e is GoLiteral || e is GoStringLiteral || e is GoCompositeLit || e is GoConversionExpr

    /** [e]'s text as the operand of `.` or `!`: parenthesized unless it is a primary expression. */
    fun operand(e: GoExpression): String = if (isPrimary(e)) e.text else "(${e.text})"

    /** [e]'s text written where [replaced] was: parenthesized when it is not primary and [replaced] is an operand of another expression. */
    fun inPlaceOf(replaced: PsiElement, e: GoExpression): String {
        val parent = replaced.parent
        return if (parent is GoExpression && parent !is GoParenthesesExpr && !isPrimary(e)) "(${e.text})" else e.text
    }

    /** The `go` version of the module is at least [major].[minor]; an unknown version counts as recent. */
    fun goAtLeast(ctx: GoRuleContext, major: Int, minor: Int): Boolean {
        val v = GoLintPsi.goVersion(ctx.file) ?: return true
        return v.first > major || v.first == major && v.second >= minor
    }

    /** The predeclared `string` (or an untyped string constant), not a named string type. */
    fun isPlainString(type: GoType): Boolean = type is GoBasicType && (type.kind == GoBasicKind.STRING || type.kind == GoBasicKind.UNTYPED_STRING)

    /** staticcheck `IsOfStringConvertibleByteSlice`: a slice of bytes; from Go 1.18 also a slice of a named byte type. */
    fun isStringConvertibleByteSlice(type: GoType, ctx: GoRuleContext): Boolean {
        if (type is GoTypeParamType) return false
        val slice = type.underlying() as? GoSliceType ?: return false
        val elem = if (goAtLeast(ctx, 1, 18)) slice.elem.underlying() else slice.elem
        return elem is GoBasicType && elem.kind == GoBasicKind.UINT8
    }

    /** A method `name(params...) results...` of [type]'s method set with [params] parameters and [results] results, or null. */
    fun method(type: GoType, name: String, params: Int, results: Int, ctx: GoRuleContext): GoMethod? =
        ctx.semantic.methodsOf(type).firstOrNull { it.name == name && it.signature.params.size == params && it.signature.results.size == results }

    /** `String() string` (`fmt.Stringer`), `Error() string`: a method of [type] with no parameters returning the predeclared string. */
    fun hasStringMethod(type: GoType, name: String, ctx: GoRuleContext): Boolean =
        method(type, name, 0, 1, ctx)?.signature?.results?.single()?.type.let { it is GoBasicType && it.kind == GoBasicKind.STRING }
}
