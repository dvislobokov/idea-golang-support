package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoVarDefinition

/**
 * contextcheck: `context.Background()` / `context.TODO()` passed as an argument while a parameter or local of type
 * `context.Context` is in scope: cancellation and deadlines of the caller are lost. Not reported in `main`, `init`, test files,
 * goroutine literals (`go func() {…}()`) and deferred calls, where detaching is usually deliberate. Fix: pass the variable.
 * A parameter of the innermost function is left to [io.github.golangsupport.ide.inspections.GoContextPlacementInspection]:
 * this one adds locals (`ctx, cancel := …`) and parameters of enclosing functions.
 */
class GoContextNotPropagatedInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val call = element as? GoCallExpr ?: return
        if (call.parent !is GoArgumentList) return
        val callee = GoFlowChecks.unparen(call.expression) as? GoReferenceExpression ?: return
        val name = callee.identifier.text
        if (name != "Background" && name != "TODO" || callee.expression == null || call.argumentList?.textLength != 2) return
        if (file.name.endsWith("_test.go") || detached(call)) return
        val target = GoFlowChecks.service(call).resolve(callee).firstOrNull() as? GoFunctionDeclaration ?: return
        if (GoAnalysisPsi.packagePath(target) != "context") return
        val variable = contextInScope(call) ?: return
        val varName = variable.name ?: return
        // a parameter of the innermost function is GoContextPlacement's report (same range, its own "Use ctx"): seen live as a duplicate
        if (variable is GoParamDefinition && PsiTreeUtil.getParentOfType(call, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java)
                ?.let { PsiTreeUtil.isAncestor(it, variable, true) && PsiTreeUtil.getParentOfType(variable, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java) === it } == true) return
        holder.registerProblem(call, "use $varName instead of context.$name()", ProblemHighlightType.WEAK_WARNING, ReplaceFix(varName))
    }

    /** Inside `main`, `init`, a goroutine literal or a deferred call (or literal). */
    private fun detached(call: GoCallExpr): Boolean {
        var e: PsiElement? = call.parent
        while (e != null) {
            when (e) {
                is GoGoStatement, is GoDeferStatement -> return true
                is GoFunctionOrMethodDeclaration -> return e is GoFunctionDeclaration && (e.name == "main" || e.name == "init")
                is GoFile -> return true
            }
            e = e.parent
        }
        return true
    }

    /** A variable of type `context.Context` visible at [call]: `ctx` if there is one, else the innermost. */
    private fun contextInScope(call: GoCallExpr): GoNamedElement? {
        val service = GoFlowChecks.service(call)
        val start = call.textRange.startOffset
        val candidates = ArrayList<GoNamedElement>()
        var e: PsiElement? = call.parent
        var top: PsiElement? = null
        while (e != null && e !is GoFile) {
            val signature = when (e) {
                is GoFunctionLit -> e.signature
                is GoFunctionOrMethodDeclaration -> e.signature.also { top = e }
                else -> null
            }
            signature?.let { s -> candidates += params(s) }
            if (top != null) break
            e = e.parent
        }
        val function = top ?: return null
        for (def in PsiTreeUtil.findChildrenOfType(function, GoVarDefinition::class.java)) {
            if (def.textRange.startOffset >= start) continue
            val scope = scopeOf(def) ?: continue
            if (!PsiTreeUtil.isAncestor(scope, call, true)) continue
            val statement = PsiTreeUtil.getParentOfType(def, GoStatement::class.java) ?: continue
            if (statement.textRange.endOffset > start) continue
            candidates += def
        }
        val typed = candidates.filter { it.name != null && it.name != "_" && GoAnalysisPsi.isNamed(service.declarationType(it), "context", "Context") }
        return typed.firstOrNull { it.name == "ctx" } ?: typed.maxByOrNull { it.textRange.startOffset }
    }

    private fun params(signature: GoSignature): List<GoParamDefinition> =
        signature.parameters.parameterDeclarationList.flatMap { it.paramDefinitionList }

    /** The block or statement header whose extent a local declared at [def] is visible in. */
    private fun scopeOf(def: PsiElement): PsiElement? = PsiTreeUtil.getParentOfType(
        def, GoBlock::class.java, GoIfStatement::class.java, GoForStatement::class.java, GoExprSwitchStatement::class.java,
        GoTypeSwitchStatement::class.java, GoCommClause::class.java, GoExprCaseClause::class.java, GoTypeCaseClause::class.java, GoFunctionLit::class.java,
    )

    /** Replaces the call with the context variable. */
    class ReplaceFix(private val name: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Use $name"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val call = descriptor.psiElement as? GoCallExpr ?: return
            val file = call.containingFile
            val document = GoImportEdits.document(file) ?: return
            document.replaceString(call.textRange.startOffset, call.textRange.endOffset, name)
            GoImportEdits.commit(file, document)
        }
    }
}
