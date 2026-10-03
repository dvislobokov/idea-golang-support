package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType

/** Shared helpers of the vet / staticcheck / revive style checks of this package. */
internal object GoLintPsi {

    fun unparen(expression: GoExpression?): GoExpression? {
        var e = expression
        while (e is GoParenthesesExpr) e = e.inner as? GoExpression
        return e
    }

    /** The callee of [call] when it is a (qualified) name. */
    fun calleeReference(call: GoCallExpr): GoReferenceExpression? = unparen(call.expression) as? GoReferenceExpression

    /** The single declaration the callee of [call] resolves to. */
    fun calleeTarget(call: GoCallExpr): PsiElement? = calleeReference(call)?.let { GoSemanticService.getInstance(call.project).resolve(it).singleOrNull() }

    /** Whether [element] is declared in the `builtin` pseudo-package. */
    fun isBuiltin(element: PsiElement): Boolean = (element.containingFile as? GoFile)?.packageName == "builtin"

    /** `T` of `T` / `*T`, as a named type of [path] called one of [names]. */
    fun isNamedOf(type: GoType, path: String, names: Set<String>): Boolean {
        val t = if (type is GoPointerType) type.elem else type
        return t is GoNamedType && t.name in names && t.pkgPath == path
    }

    /** The expression statement [call] makes (`f()` standing alone in a statement list), or null. */
    fun isExpressionStatement(call: GoCallExpr): Boolean = io.github.golangsupport.ide.editor.GoEditText.expressionStatement(call) != null

    /** The whole statement-list statement around [element] with its line when it stands alone: the range to delete. */
    fun statementRange(statement: PsiElement, text: CharSequence): TextRange {
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
        return TextRange(start, end)
    }

    /** The statement of a statement list that contains [element]. */
    fun listStatement(element: PsiElement): PsiElement? {
        var e: PsiElement? = element
        while (e != null && e !is com.intellij.psi.PsiFile) {
            val p = e.parent
            if (p is GoBlock || p is GoExprCaseClause || p is GoTypeCaseClause || p is GoCommClause) return e
            e = p
        }
        return null
    }

    /** The `go` directive of the module of [file] as (major, minor), or null when there is no module or no directive. */
    fun goVersion(file: GoFile): Pair<Int, Int>? {
        val graph = GoModuleGraphProvider.getInstance(file.project).graphFor(GoPsiUtil.originalVirtualFile(file)) ?: return null
        val path = GoPsiUtil.originalVirtualFile(file).path
        val module = graph.mainModules.filter { m -> m.dir?.let { path.startsWith(it.toString().replace(java.io.File.separatorChar, '/') + "/") } == true }.maxByOrNull { it.dir.toString().length }
            ?: graph.mainModules.singleOrNull() ?: return null
        return parseVersion(module.goVersion)
    }

    fun parseVersion(text: String?): Pair<Int, Int>? {
        val parts = text?.removePrefix("go")?.split('.') ?: return null
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        return major to minor
    }

    fun packagePath(element: PsiElement): String? = GoAnalysisPsi.packagePath(element)
}

/** Deletes the statement around the problem element (its whole line when it stands alone). */
class GoRemoveStatementFix(private val text: String) : LocalQuickFix {
    override fun getFamilyName(): String = text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val statement = descriptor.psiElement?.let(GoLintPsi::listStatement) ?: return
        val file = statement.containingFile
        val document = GoImportEdits.document(file) ?: return
        val range = GoLintPsi.statementRange(statement, document.charsSequence)
        document.deleteString(range.startOffset, range.endOffset)
        GoImportEdits.commit(file, document)
    }
}
