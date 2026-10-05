package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoGoStatement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * modernize `testingcontext` (go1.24), in a `Test*` / `Benchmark*` / `Fuzz*` function of a `_test.go` file with a `*testing.T|B|F` parameter:
 * - `ctx, cancel := context.WithCancel(context.Background())` + `defer cancel()` (cancel used nowhere else) → `ctx := t.Context()`;
 * - `context.Background()` / `context.TODO()` written directly in the test body → `t.Context()`. Not inside a function literal, and
 *   not in a test that registers `Cleanup` (the test context is cancelled before cleanups run).
 */
class GoFixTestingContextInspection : GoFix2InspectionBase() {
    override val minVersion = "1.24"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoCallExpr || !file.name.endsWith("_test.go")) return null
        withCancel(element)?.let { return it }
        return background(element)
    }

    /** Shape 1: the `context.WithCancel(context.Background())` call. */
    private fun withCancel(call: GoCallExpr): GoFixFinding? {
        val inner = GoFixPsi.args(call).singleOrNull() as? GoCallExpr ?: return null
        if (GoFixPsi.args(inner).isNotEmpty()) return null
        val declaration = call.parent as? GoShortVarDeclaration ?: return null
        if (declaration.expressionList.singleOrNull() !== call || declaration.varDefinitionList.size != 2) return null
        val (ctx, cancel) = declaration.varDefinitionList
        if (ctx.name == "_" || cancel.name == "_" || !GoFixPsi.isStatementList(declaration.parent)) return null
        val test = testParameter(call) ?: return null
        val body = GoFixPsi.enclosingBody(call) ?: return null
        val uses = GoFixPsi.references(body, cancel)
        val defer = uses.singleOrNull()?.let { deferOf(it) } ?: return null
        if (GoFixPsi.previousStatement(defer) !== declaration) return null
        if (!GoFixPsi.isCallTo(call, "context.WithCancel") || !isBackground(inner)) return null
        return GoFixFinding(call, "context.WithCancel can be modernized using $test.Context", listOf("Replace context.WithCancel with $test.Context" to { _ ->
            listOf(GoFixEdit.replace(declaration, "${ctx.name} := $test.Context()"), GoFixPsi.deleteStatement(defer))
        }))
    }

    /** `defer cancel()` around the reference [use]. */
    private fun deferOf(use: GoReferenceExpression): GoDeferStatement? {
        val call = use.parent as? GoCallExpr ?: return null
        if (call.expression !== use || GoFixPsi.args(call).isNotEmpty()) return null
        return (call.parent as? GoDeferStatement)?.takeIf { it.expression === call }
    }

    /** Shape 2: a bare `context.Background()` / `context.TODO()` in the test body. */
    private fun background(call: GoCallExpr): GoFixFinding? {
        if (GoFixPsi.args(call).isNotEmpty() || !isBackground(call)) return null
        val outer = (call.parent as? GoArgumentList)?.parent as? GoCallExpr
        if (outer != null && withCancel(outer) != null) return null
        val body = GoFixPsi.enclosingBody(call) ?: return null
        if (body.parent !is GoFunctionDeclaration || CLEANUP.containsMatchIn(body.text)) return null
        // `go use(ctx)` / `defer use(ctx)`: may run when the test context is already cancelled
        if (PsiTreeUtil.getParentOfType(call, GoGoStatement::class.java, GoDeferStatement::class.java)?.let { body.textRange.contains(it.textRange) } == true) return null
        val test = testParameter(call) ?: return null
        val name = (GoFixPsi.unparen(call.expression) as GoReferenceExpression).identifier.text
        return GoFixFinding(call, "context.$name() can be replaced by $test.Context() in a test", listOf("Replace with $test.Context()" to { _ ->
            listOf(GoFixEdit.replace(call, "$test.Context()"))
        }))
    }

    private fun isBackground(call: GoCallExpr): Boolean = GoFixPsi.callee(call, "Background", "TODO")?.let { it == "context.Background" || it == "context.TODO" } == true

    /** The name of the `*testing.T|B|F` parameter of the test function around [place], when it is visible there. */
    private fun testParameter(place: PsiElement): String? {
        val body = GoFixPsi.enclosingBody(place) ?: return null
        var function: PsiElement? = body.parent
        while (function != null && function !is GoFunctionDeclaration) function = GoFixPsi.enclosingBody(function)?.parent
        val declaration = function as? GoFunctionDeclaration ?: return null
        val name = declaration.name ?: return null
        if (!TEST_NAME.matches(name)) return null
        val parameter = declaration.signature?.parameters?.parameterDeclarationList?.singleOrNull()?.paramDefinitionList?.singleOrNull() ?: return null
        val paramName = parameter.name?.takeIf { it != "_" } ?: return null
        val type = GoSemanticService.getInstance(place.project).declarationType(parameter) as? GoPointerType ?: return null
        val named = type.elem as? GoNamedType ?: return null
        if (named.pkgPath != "testing" || named.name !in setOf("T", "B", "F")) return null
        val visible = GoScopes.resolveName(place, paramName).firstOrNull()?.element
        return paramName.takeIf { visible == parameter }
    }

    companion object {
        private val TEST_NAME = Regex("""(Test|Benchmark|Fuzz)([^a-z].*)?""")
        private val CLEANUP = Regex("""\.Cleanup\s*\(""")
    }
}
