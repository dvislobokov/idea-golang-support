package io.github.golangsupport.ide.intentions

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition

/**
 * Handle error: after `x, err := f()` / `err = f()` whose call returns an `error` last and whose next statement does not check that
 * variable, `if err != nil { return <zero values>, err }` with the results of the enclosing function. A call standing alone
 * (`os.Remove(p)`) becomes `if err := os.Remove(p); err != nil { … }` (`_, err :=` for the other results).
 */
class GoHandleErrorIntention : GoCodeActionIntention() {
    override val defaultText: String = "Handle error"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val outer = GoIntentionText.statementAt(leaf) ?: return null
        // `x, err := f()` may come wrapped in a simple statement
        val statement = (outer as? GoSimpleStatement)?.statement ?: outer
        val service = GoSemanticService.getInstance(file.project)
        val text = file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, statement.textRange.startOffset)
        val source = GoSourceText(file)
        val signature = GoIntentionText.enclosingSignature(statement)
        when (statement) {
            is GoShortVarDeclaration, is GoAssignmentStatement -> {
                val (names, values) = when (statement) {
                    is GoShortVarDeclaration -> statement.varDefinitionList.map { it.name } to statement.expressionList
                    is GoAssignmentStatement -> {
                        if (statement.assignOp.text != "=") return null
                        // non-simple targets (`s.err = f()`) are not variables to check
                        statement.leftHandExprList.expressionList.map { (it as? GoReferenceExpression)?.takeIf { r -> r.expression == null }?.identifier?.text } to statement.expressionList
                    }
                    else -> return null
                }
                val call = values.singleOrNull() as? GoCallExpr ?: return null
                val results = service.calleeSignature(call)?.results ?: return null
                if (results.size != names.size || !GoZeroValues.isError(results.last().type)) return null
                val error = names.last()?.takeIf { it != "_" } ?: return null
                if (checked(outer, error)) return null
                val check = "\n${indent}if $error != nil {\n$indent\t${GoIntentionText.returnStatement(signature, error, source)}\n$indent}"
                val end = statement.textRange.endOffset
                return GoEditPlan(listOf(GoEditPlan.Edit(end, end, check)), source.imports)
            }
            is GoSimpleStatement -> {
                if (statement.statement != null) return null
                val call = statement.leftHandExprList?.expressionList?.singleOrNull() as? GoCallExpr ?: return null
                val results = service.calleeSignature(call)?.results ?: return null
                if (results.isEmpty() || !GoZeroValues.isError(results.last().type)) return null
                val targets = (List(results.size - 1) { "_" } + "err").joinToString(", ")
                val replacement = "if $targets := ${call.text}; err != nil {\n$indent\t${GoIntentionText.returnStatement(signature, "err", source)}\n$indent}"
                return GoEditPlan(listOf(GoEditPlan.Edit(statement.textRange.startOffset, statement.textRange.endOffset, replacement)), source.imports)
            }
            else -> return null
        }
    }

    /** The statement after [statement] is an `if` whose condition mentions [error]. */
    private fun checked(statement: GoStatement, error: String): Boolean {
        val next = PsiTreeUtil.getNextSiblingOfType(statement, GoStatement::class.java) as? GoIfStatement ?: return false
        val condition = next.condition ?: return false
        return PsiTreeUtil.findChildrenOfType(condition, GoReferenceExpression::class.java).any { it.expression == null && it.identifier?.text == error } ||
            (condition is GoReferenceExpression && condition.identifier?.text == error)
    }
}

/**
 * Wrap error with fmt.Errorf: in `return …, err` the error becomes `fmt.Errorf("<function>: %w", err)`, the function being the
 * enclosing declaration (`Type.Method` for a method); `fmt` is imported when the file does not import it.
 */
class GoWrapErrorIntention : GoCodeActionIntention() {
    override val defaultText: String = "Wrap error with fmt.Errorf"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val statement = PsiTreeUtil.getParentOfType(leaf, GoReturnStatement::class.java, false) ?: return null
        if (GoPsiUtil.functionOwner(statement) != GoPsiUtil.functionOwner(leaf)) return null
        val error = statement.expressionList.lastOrNull() as? GoReferenceExpression ?: return null
        if (error.expression != null || error.identifier?.text == "nil") return null
        val service = GoSemanticService.getInstance(file.project)
        if (!GoZeroValues.isError(service.typeOf(error))) return null
        val function = PsiTreeUtil.getParentOfType(statement, GoFunctionOrMethodDeclaration::class.java) ?: return null
        val name = functionName(function) ?: return null
        val source = GoSourceText(file)
        val fmt = source.prefix("fmt", "fmt")
        val wrapped = "${fmt}Errorf(\"$name: %w\", ${error.text})"
        return GoEditPlan(listOf(GoEditPlan.Edit(error.textRange.startOffset, error.textRange.endOffset, wrapped)), source.imports)
    }

    private fun functionName(function: GoFunctionOrMethodDeclaration): String? {
        val name = function.name ?: return null
        val receiver = (function as? io.github.golangsupport.lang.psi.GoMethodDeclaration)?.receiverTypeName
        return if (receiver != null) "$receiver.$name" else name
    }
}
