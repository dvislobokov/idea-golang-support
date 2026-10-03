package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause

/**
 * A loop variable captured by a function literal that outlives the iteration (vet `loopclosure`): the literal of a `go` / `defer`
 * statement, of `errgroup.Group.Go`, or of a `t.Run` that calls `t.Parallel()`, as the last statement of the body. Go 1.22 gives
 * every iteration its own variable, so this is reported only when the module's `go` directive is older than 1.22 (no module or
 * no directive: nothing). Fix: `v := v` at the start of the body.
 */
class GoLoopClosureInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoForStatement) return
        val body = element.block ?: return
        val last = body.statementList.lastOrNull() ?: return
        val literal = lastLiteral(last) ?: return
        val variables = loopVariables(element)
        if (variables.isEmpty()) return
        val version = GoLintPsi.goVersion(file) ?: return
        if (version.first > 1 || version.second >= 22) return
        val service = GoSemanticService.getInstance(file.project)
        val block = literal.block ?: return
        for (reference in PsiTreeUtil.findChildrenOfType(block, GoReferenceExpression::class.java)) {
            if (reference.expression != null) continue
            val name = reference.identifier.text
            if (variables.none { it.name == name }) continue
            val target = service.resolve(reference).singleOrNull() ?: continue
            if (variables.none { it === target }) continue
            holder.registerProblem(reference, "loop variable $name captured by func literal", GoShadowLoopVariableFix(name))
        }
    }

    private fun loopVariables(loop: GoForStatement): List<GoVarDefinition> {
        loop.rangeClause?.let { range -> return if (range.define != null) range.varDefinitionList else emptyList() }
        val init = loop.forClause?.statementList?.firstOrNull() ?: return emptyList()
        val declaration = (init as? GoShortVarDeclaration) ?: PsiTreeUtil.findChildOfType(init, GoShortVarDeclaration::class.java, false) ?: return emptyList()
        return declaration.varDefinitionList
    }

    /** The function literal [statement] hands to a `go`, a `defer`, `errgroup.Group.Go` or a parallel `t.Run`. */
    private fun lastLiteral(statement: PsiElement): GoFunctionLit? {
        val expression: GoExpression = when (statement) {
            is GoGoStatement -> statement.expression
            is GoDeferStatement -> statement.expression
            is GoSimpleStatement -> {
                if (statement.statement != null) return null
                statement.leftHandExprList?.expressionList?.singleOrNull()
            }
            else -> null
        } ?: return null
        val call = GoLintPsi.unparen(expression) as? GoCallExpr ?: return null
        val callee = GoLintPsi.unparen(call.expression)
        if (statement is GoSimpleStatement) {
            val reference = callee as? GoReferenceExpression ?: return null
            val qualifier = reference.expression ?: return null
            val literal = call.arguments.filterIsInstance<GoFunctionLit>().lastOrNull() ?: return null
            val service = GoSemanticService.getInstance(call.project)
            val type = service.typeOf(qualifier)
            return when (reference.identifier.text) {
                "Go" -> literal.takeIf { type.let { t -> val n = (t as? io.github.golangsupport.semantic.types.GoPointerType)?.elem ?: t; n is io.github.golangsupport.semantic.types.GoNamedType && n.name == "Group" && n.pkgPath?.endsWith("errgroup") == true } }
                "Run" -> literal.takeIf { GoLintPsi.isNamedOf(type, "testing", setOf("T", "B")) && callsParallel(it, service) }
                else -> null
            }
        }
        return callee as? GoFunctionLit
    }

    private fun callsParallel(literal: GoFunctionLit, service: GoSemanticService): Boolean {
        val block = literal.block ?: return false
        return PsiTreeUtil.findChildrenOfType(block, GoCallExpr::class.java).any { call ->
            val reference = GoLintPsi.calleeReference(call)
            reference?.identifier?.text == "Parallel" && reference.expression?.let { GoLintPsi.isNamedOf(service.typeOf(it), "testing", setOf("T", "B")) } == true
        }
    }
}

/** `v := v` as the first statement of the loop body. */
class GoShadowLoopVariableFix(private val name: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Copy the loop variable"

    override fun getName(): String = "Insert '$name := $name'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val reference = descriptor.psiElement as? GoReferenceExpression ?: return
        val definition = GoSemanticService.getInstance(project).resolve(reference).singleOrNull() ?: return
        val loop = PsiTreeUtil.getParentOfType(definition, GoForStatement::class.java) ?: return
        val brace = loop.block?.lbrace ?: return
        val file = loop.containingFile
        val document = GoImportEdits.document(file) ?: return
        val text = document.charsSequence
        val offset = loop.textRange.startOffset
        val lineStart = (text.lastIndexOf('\n', offset - 1) + 1).coerceAtLeast(0)
        var indentEnd = lineStart
        while (indentEnd < text.length && (text[indentEnd] == ' ' || text[indentEnd] == '\t')) indentEnd++
        val indent = text.subSequence(lineStart, indentEnd)
        document.insertString(brace.textRange.endOffset, "\n$indent\t$name := $name")
        GoImportEdits.commit(file, document)
    }

    private fun CharSequence.lastIndexOf(c: Char, from: Int): Int {
        var i = minOf(from, length - 1)
        while (i >= 0 && this[i] != c) i--
        return i
    }
}
