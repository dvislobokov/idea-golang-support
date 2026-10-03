package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.refactoring.GoInlineSupport.refuse
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType

/**
 * Inline Function: a call of a project function or method whose body is one `return expr` (or one expression statement for a function
 * without results) becomes `expr` with the arguments in place of the parameters. v1 keeps the semantics simple: arguments must be free
 * of side effects (so dropping, reordering or evaluating them later changes nothing), and an argument that is not a plain name or
 * literal may be used at most once. Converting where the types would change (`float64(1)` for an untyped constant passed as
 * float64, `R(expr)` for a result of another type) keeps the values identical. Refused: recursion, type parameters, variadic or
 * named results, `recover`, several statements, function literals capturing parameters, calls from another package, `go` / `defer`
 * of the call.
 */
internal object GoInlineFunction {
    const val TITLE = "Inline Function"

    private class Param(val def: GoNamedElement?, val typeText: String?, val type: GoType?, val receiver: Boolean)

    /** The body's single expression and whether the function has a result. */
    private class Body(val expr: GoExpression, val returns: Boolean)

    /** The edit replacing [call] of [function] by the function's expression. */
    fun planCall(function: GoFunctionOrMethodDeclaration, call: GoCallExpr): List<GoTextEdit> {
        val body = checkFunction(function)
        return listOf(callEdit(function, body, call))
    }

    /** Every call of [function] inlined and the declaration removed (functions only: removing a method may break an interface). */
    fun planAll(function: GoFunctionOrMethodDeclaration): List<GoTextEdit> {
        if (function is GoMethodDeclaration) refuse("Put the caret on a call: inlining every call of a method is not supported")
        val body = checkFunction(function)
        val name = function.name
        val refs = ReferencesSearch.search(function, GlobalSearchScope.projectScope(function.project)).findAll().map { it.element }
        if (refs.isEmpty()) refuse("'$name' is never called")
        val edits = refs.map { ref ->
            val call = (ref as? GoReferenceExpression)?.let(::callOf) ?: refuse("'$name' is used as a value, not only called")
            callEdit(function, body, call)
        }
        val file = function.containingFile
        return edits + GoTextEdit(file, GoInlineSupport.lineRange(file.viewProvider.contents, function.textRange), "")
    }

    /** The call whose callee is [ref], or null. */
    fun callOf(ref: GoReferenceExpression): GoCallExpr? {
        var callee: PsiElement = ref
        while (callee.parent is GoParenthesesExpr) callee = callee.parent
        return (callee.parent as? GoCallExpr)?.takeIf { it.expression === callee }
    }

    private fun checkFunction(function: GoFunctionOrMethodDeclaration): Body {
        val name = function.name
        val file = function.containingFile.originalFile.virtualFile
        if (file == null || !ProjectFileIndex.getInstance(function.project).isInContent(file)) refuse("'$name' is not declared in the project")
        if (function.typeParameters != null) refuse("'$name' is generic")
        val signature = function.signature ?: refuse("'$name' has no signature")
        if (signature.parameters.parameterDeclarationList.any { it.isVariadic }) refuse("'$name' is variadic")
        val result = signature.result
        if (result?.parameters?.parameterDeclarationList?.any { it.paramDefinitionList.isNotEmpty() } == true) refuse("'$name' has named results")
        if (result?.parameters != null && result.parameters!!.parameterDeclarationList.size != 1) refuse("'$name' returns several results")
        if (function is GoMethodDeclaration && function.receiver?.type?.text?.contains('[') == true) refuse("'$name' is a method of a generic type")
        val block = function.block ?: refuse("'$name' has no body")
        val statement = block.statementList.singleOrNull() ?: refuse("The body of '$name' is not a single statement: only one-statement bodies can be inlined")
        val body = when {
            statement is GoReturnStatement && result != null -> Body(statement.expressionList.singleOrNull() ?: refuse("'$name' does not return one expression"), true)
            statement is GoSimpleStatement && result == null && statement.statement == null ->
                Body(statement.leftHandExprList?.expressionList?.singleOrNull() ?: refuse("The body of '$name' is not an expression"), false)
            statement is GoDeferStatement -> refuse("The body of '$name' defers a call")
            else -> refuse("The body of '$name' is not a single return or expression statement")
        }
        val service = GoSemanticService.getInstance(function.project)
        val refs = PsiTreeUtil.findChildrenOfType(body.expr, GoReferenceExpression::class.java) + listOfNotNull(body.expr as? GoReferenceExpression)
        if (refs.any { r -> service.resolve(r).any { it == function } }) refuse("'$name' is recursive")
        if (refs.any { it.expression == null && it.identifier.text == "recover" }) refuse("'$name' calls recover")
        return body
    }

    private fun params(function: GoFunctionOrMethodDeclaration): List<Param> {
        val service = GoSemanticService.getInstance(function.project)
        val result = ArrayList<Param>()
        (function as? GoMethodDeclaration)?.receiver?.let { r -> result += Param(r.takeIf { it.identifier != null }, r.type?.text, service.declarationType(r), true) }
        for (decl in function.signature!!.parameters.parameterDeclarationList) {
            val type = decl.type?.text
            if (decl.paramDefinitionList.isEmpty()) result += Param(null, type, null, false)
            for (def in decl.paramDefinitionList) result += Param(def, type, service.declarationType(def), false)
        }
        return result
    }

    private fun callEdit(function: GoFunctionOrMethodDeclaration, body: Body, call: GoCallExpr): GoTextEdit {
        val name = function.name
        val service = GoSemanticService.getInstance(function.project)
        val callFile = call.containingFile as? GoFile ?: refuse("The call is not in Go code")
        val home = function.containingFile as GoFile
        if (callFile.packageName != home.packageName || callFile.originalFile.virtualFile?.parent != home.originalFile.virtualFile?.parent) {
            refuse("The call is in another package: the names of '$name' would need qualifying")
        }
        val parent = call.parent
        if (parent is GoGoStatement || parent is GoDeferStatement) refuse("The call is the one of a 'go' or 'defer' statement")
        val statementCall = parent is GoLeftHandExprList && parent.parent is GoSimpleStatement
        if (body.returns && statementCall) refuse("The result of the call is not used")
        if (!body.returns && !statementCall) refuse("'$name' has no result to use here")
        if (call.argumentList?.hasEllipsis == true) refuse("The call spreads a slice with '...'")

        val params = params(function)
        val args = ArrayList<GoExpression>()
        val callee = call.expression as? GoReferenceExpression ?: refuse("The called function is not named directly")
        if (function is GoMethodDeclaration) {
            val receiver = callee.expression as? GoReferenceExpression
            if (receiver == null || receiver.expression != null) refuse("The receiver of the call is not a plain name")
            if (service.resolve(receiver).any { it is GoTypeSpec || it is GoImportSpec }) refuse("A method expression cannot be inlined")
            args += receiver
        } else if (callee.expression != null) refuse("The call is qualified")
        val given = call.argumentList?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, GoExpression::class.java) }.orEmpty()
        args += given
        if (given.size == 1 && service.typeOf(given[0]) is GoTupleType) refuse("The call passes a multi-value result")
        if (args.size != params.size) refuse("The arguments of the call do not match the parameters of '$name'")

        // parameters -> argument texts
        val replacements = HashMap<PsiElement, Pair<String, Int>>()
        val paramRefs = PsiTreeUtil.findChildrenOfType(body.expr, GoReferenceExpression::class.java) + listOfNotNull(body.expr as? GoReferenceExpression)
        for ((i, p) in params.withIndex()) {
            val arg = args[i]
            val argName = if (p.receiver) "the receiver" else "argument ${if (function is GoMethodDeclaration) i else i + 1}"
            if (GoInlineSupport.hasSideEffects(arg)) refuse("The value of $argName has side effects")
            val uses = p.def?.let { def -> paramRefs.filter { it.expression == null && service.resolve(it).contains(def) } }.orEmpty()
            if (uses.size > 1 && !isSimple(arg)) refuse("The value of $argName is not a plain name and is used ${uses.size} times")
            for (use in uses) {
                if (PsiTreeUtil.getParentOfType(use, GoFunctionLit::class.java, true, GoFunctionOrMethodDeclaration::class.java) != null) refuse("A function literal in '$name' captures '${use.identifier.text}'")
                if (GoInlineSupport.isAddressTaken(use)) refuse("'$name' takes the address of '${use.identifier.text}'")
                if (GoInlineSupport.callsPointerMethod(use)) refuse("'$name' calls a pointer method on its copy of '${use.identifier.text}'")
            }
            val argType = service.typeOf(arg)
            for (use in uses) replacements[use] = argumentText(p, arg, argType, use, sameFile = callFile == home)
        }

        // the names of the body must mean the same at the call
        val paramDefs = params.mapNotNull { it.def }.toSet()
        GoInlineSupport.capturedName(GoInlineSupport.freeReferences(body.expr), call) { it in paramDefs }?.let { refuse("'$it' means something else at the call") }

        val text = substitute(body.expr, replacements)
        val bodyType = service.typeOf(body.expr)
        val resultTypeText = function.signature?.result?.let { r -> r.type?.text ?: r.parameters?.parameterDeclarationList?.singleOrNull()?.type?.text }
        val resultType = service.calleeSignature(call)?.results?.singleOrNull()?.type
        val convert = body.returns && resultType != null && resultTypeText != null && GoInlineSupport.needsConversion(bodyType, resultType)
        if (convert && callFile != home && '.' in resultTypeText!!) refuse("The result would need a conversion to a type of another file's imports")
        val (finalText, prec) = when {
            convert -> GoInlineSupport.conversion(resultTypeText!!, text) to 7
            replacements.containsKey(body.expr) -> text to replacements.getValue(body.expr).second
            else -> text to GoInlineSupport.precedence(body.expr)
        }
        return GoTextEdit(callFile, call.textRange, GoInlineSupport.placed(call, finalText, prec))
    }

    /** A plain name, a literal or a qualified package member: copying it is free and means the same. */
    private fun isSimple(e: GoExpression): Boolean = when (e) {
        is GoParenthesesExpr -> PsiTreeUtil.getChildOfType(e, GoExpression::class.java)?.let(::isSimple) == true
        is GoLiteral, is GoStringLiteral -> true
        is GoReferenceExpression -> e.expression == null ||
            (e.expression as? GoReferenceExpression)?.let { q -> GoSemanticService.getInstance(e.project).resolve(q).any { it is GoImportSpec } } == true
        else -> false
    }

    /**
     * The argument's text for one use of its parameter and the precedence of that text: converted where its type differs, `&x` / `*x`
     * for a receiver of the other kind, parenthesised for the place of the use.
     */
    private fun argumentText(p: Param, arg: GoExpression, argType: GoType, use: GoReferenceExpression, sameFile: Boolean): Pair<String, Int> {
        if (p.receiver) {
            val selector = (use.parent as? GoReferenceExpression)?.expression === use
            val wantPointer = p.type?.underlying() is GoPointerType || p.typeText?.trim()?.startsWith("*") == true
            val isPointer = argType.underlying() is GoPointerType
            val text = when {
                selector || wantPointer == isPointer -> arg.text
                wantPointer -> "&" + arg.text
                else -> "*" + arg.text
            }
            return placed(use, text, if (text === arg.text) GoInlineSupport.precedence(arg) else 6)
        }
        val type = p.type
        if (type != null && p.typeText != null && GoInlineSupport.needsConversion(argType, type)) {
            if (!sameFile && '.' in p.typeText) refuse("The argument would need a conversion to a type of another file's imports")
            return placed(use, GoInlineSupport.conversion(p.typeText, arg.text), 7)
        }
        return placed(use, arg.text, GoInlineSupport.precedence(arg))
    }

    private fun placed(use: GoExpression, text: String, prec: Int): Pair<String, Int> =
        GoInlineSupport.placed(use, text, prec).let { if (it == text) it to prec else it to 7 }

    /** The text of [expr] with the elements of [replacements] replaced. */
    private fun substitute(expr: GoExpression, replacements: Map<PsiElement, Pair<String, Int>>): String {
        val base = expr.textRange.startOffset
        val sb = StringBuilder(expr.text)
        for ((element, text) in replacements.entries.sortedByDescending { it.key.textRange.startOffset }) {
            val r = element.textRange
            sb.replace(r.startOffset - base, r.endOffset - base, text.first)
        }
        return sb.toString()
    }
}
