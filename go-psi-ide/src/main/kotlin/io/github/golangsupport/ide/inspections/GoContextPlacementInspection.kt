package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoPointerType
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments

/**
 * Where a `context.Context` goes (revive `context-as-argument`, `contextcheck`):
 * - a `context.Context` parameter of a function or method that is not the first one (testing `*T`, `*B`, `*F`, `TB` may come first);
 * - inside a function with a context parameter, the parameter replaced (`ctx = context.Background()`) or shadowed
 *   (`ctx := context.TODO()`) by a root context: the caller's deadline and cancellation are lost (fix: remove the assignment);
 * - `context.Background()` / `context.TODO()` passed as an argument where the function has a context parameter (weak warning;
 *   fix "Use ctx"). Only the innermost function counts: a function literal without a context parameter (a detached goroutine)
 *   is not reported.
 */
class GoContextPlacementInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        when (element) {
            is GoFunctionOrMethodDeclaration -> checkParameters(element, holder)
            is GoCallExpr -> checkRootContext(element, holder)
        }
    }

    private fun checkParameters(function: GoFunctionOrMethodDeclaration, holder: ProblemsHolder) {
        val declarations = function.signature?.parameters?.parameterDeclarationList ?: return
        var position = 0
        for (declaration in declarations) {
            val count = maxOf(1, declaration.paramDefinitionList.size)
            if (isContext(declaration.type)) {
                if (position > 0 && !declarations.takeWhile { it !== declaration }.all { isTesting(it.type) }) {
                    holder.registerProblem(declaration.type, "context.Context should be the first parameter of a function")
                }
                return
            }
            position += count
        }
    }

    private fun checkRootContext(call: GoCallExpr, holder: ProblemsHolder) {
        val name = rootContextName(call) ?: return
        val param = contextParameter(call) ?: return
        val ctx = param.name ?: return
        val parent = call.parent
        if (parent is GoArgumentList) {
            holder.registerProblem(call, "context.$name() is passed where '$ctx' is available", ProblemHighlightType.WEAK_WARNING, GoUseContextFix(ctx))
            return
        }
        val target = assignedName(call) ?: return
        if (target != ctx) return
        val statement = call.parent
        val single = when (statement) {
            is GoShortVarDeclaration -> statement.varDefinitionList.size == 1 && statement.expressionList.size == 1
            is GoAssignmentStatement -> statement.leftHandExprList.expressionList.size == 1 && statement.expressionList.size == 1
            else -> false
        }
        val verb = if (statement is GoShortVarDeclaration) "shadowed" else "replaced"
        val fixes = if (single) arrayOf<LocalQuickFix>(GoRemoveContextAssignmentFix(ctx)) else emptyArray()
        holder.registerProblem(call, "'$ctx' is $verb by context.$name(): the caller's deadline and cancellation are lost", *fixes)
    }

    companion object {
        private val TESTING_TYPES = setOf("T", "B", "F", "TB")

        /** `Background` / `TODO` when [call] is `context.Background()` / `context.TODO()` of the standard library. */
        fun rootContextName(call: GoCallExpr): String? {
            val callee = call.expression as? GoReferenceExpression ?: return null
            val name = callee.identifier.text
            if (name != "Background" && name != "TODO" || callee.expression == null || call.arguments.isNotEmpty()) return null
            val target = GoSemanticService.getInstance(call.project).resolve(callee).firstOrNull() as? GoFunctionDeclaration ?: return null
            return name.takeIf { GoAnalysisPsi.packagePath(target) == "context" }
        }

        /** The first named `context.Context` parameter of the innermost function around [element]. */
        fun contextParameter(element: PsiElement): GoParamDefinition? {
            val signature: GoSignature = when (val owner = GoPsiUtil.functionOwner(element)) {
                is GoFunctionOrMethodDeclaration -> owner.signature
                is GoFunctionLit -> owner.signature
                else -> null
            } ?: return null
            val declaration = signature.parameters.parameterDeclarationList.firstOrNull { isContext(it.type) } ?: return null
            return declaration.paramDefinitionList.firstOrNull { it.name != null && it.name != "_" }
        }

        /** Whether [type] names `context.Context`. */
        fun isContext(type: GoType?): Boolean = isNamed(type, "context", setOf("Context"))

        private fun isTesting(type: GoType?): Boolean = isNamed((type as? GoPointerType)?.type ?: type, "testing", TESTING_TYPES)

        private fun isNamed(type: GoType?, path: String, names: Set<String>): Boolean {
            val reference = type?.typeReferenceExpression ?: return false
            if (reference.identifier.text !in names) return false
            val spec = GoSemanticService.getInstance(type.project).resolve(reference) as? GoTypeSpec ?: return false
            return GoAnalysisPsi.packagePath(spec) == path
        }

        /** The name [call] is assigned to in `x = call` / `x := call` (by position), or null. */
        private fun assignedName(call: GoCallExpr): String? = when (val statement = call.parent) {
            is GoShortVarDeclaration -> statement.varDefinitionList.getOrNull(statement.expressionList.indexOf(call))?.name
            is GoAssignmentStatement -> {
                val left = statement.leftHandExprList.expressionList.getOrNull(statement.expressionList.indexOf(call)) as? GoReferenceExpression
                left?.takeIf { it.expression == null && statement.assignOp.text == "=" }?.identifier?.text
            }
            else -> null
        }
    }
}

/** Replaces `context.Background()` by the function's context parameter. */
class GoUseContextFix(private val ctx: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Use context parameter"

    override fun getName(): String = "Use $ctx"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val call = descriptor.psiElement as? GoCallExpr ?: return
        val document = GoImportEdits.document(call.containingFile) ?: return
        document.replaceString(call.textRange.startOffset, call.textRange.endOffset, ctx)
        GoImportEdits.commit(call.containingFile, document)
    }
}

/** Removes `ctx = context.Background()` (its whole line when it stands alone), so the code keeps using the parameter. */
class GoRemoveContextAssignmentFix(private val ctx: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Use context parameter"

    override fun getName(): String = "Use $ctx (remove the assignment)"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val statement = descriptor.psiElement?.parent ?: return
        if (statement !is GoShortVarDeclaration && statement !is GoAssignmentStatement) return
        val file = statement.containingFile
        val document = GoImportEdits.document(file) ?: return
        val text = document.charsSequence
        var start = statement.textRange.startOffset
        var end = statement.textRange.endOffset
        var lineStart = start
        while (lineStart > 0 && (text[lineStart - 1] == ' ' || text[lineStart - 1] == '\t')) lineStart--
        var lineEnd = end
        while (lineEnd < text.length && (text[lineEnd] == ' ' || text[lineEnd] == '\t')) lineEnd++
        if ((lineStart == 0 || text[lineStart - 1] == '\n') && lineEnd < text.length && text[lineEnd] == '\n') {
            start = lineStart
            end = lineEnd + 1
        }
        document.deleteString(start, end)
        GoImportEdits.commit(file, document)
    }
}
