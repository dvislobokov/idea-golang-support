package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * The `go` directive of the module of a file, for the version gates of the Go fix inspections (modernizers only suggest what
 * the module's language version has). No module or no directive: everything is suggested.
 */
object GoFixGoVersion {
    private val KEY = Key.create<com.intellij.psi.util.CachedValue<Pair<Int, Int>?>>("go.fix2.goVersion")

    /** Whether the module of [file] declares at least `go [version]` (`"1.21"`); true without a module or a directive. */
    fun atLeast(file: GoFile, version: String): Boolean {
        val have = of(file) ?: return true
        val want = GoLintPsi.parseVersion(version) ?: return true
        return compareValuesBy(have, want, { it.first }, { it.second }) >= 0
    }

    /** (major, minor) of the module's `go` directive, cached per file on the project model tracker (go.mod edits). */
    fun of(file: GoFile): Pair<Int, Int>? {
        val original = GoPsiUtil.originalFile(file)
        return CachedValuesManager.getManager(file.project).getCachedValue(original, KEY, {
            CachedValueProvider.Result.create(compute(original), GoTrackers.getInstance(original.project).projectModel)
        }, false)
    }

    private fun compute(file: GoFile): Pair<Int, Int>? {
        val virtualFile = GoPsiUtil.originalVirtualFile(file)
        val graph = GoModuleGraphProvider.getInstance(file.project).graphFor(virtualFile) ?: return null
        val path = virtualFile.path
        val module = graph.mainModules.filter { m -> m.dir?.let { path.startsWith(it.toString().replace(java.io.File.separatorChar, '/') + "/") } == true }
            .maxByOrNull { it.dir.toString().length } ?: graph.mainModules.singleOrNull() ?: return null
        return GoLintPsi.parseVersion(module.goVersion)
    }
}

/** A text edit of a Go fix rewrite (absolute offsets in the file). */
internal data class GoFixEdit(val range: TextRange, val text: String) {
    companion object {
        fun replace(element: PsiElement, text: String) = GoFixEdit(element.textRange, text)
        fun delete(range: TextRange) = GoFixEdit(range, "")
    }
}

/**
 * One finding of a Go fix inspection: where to highlight ([anchor], optionally narrowed by [range] inside it), the message, and the
 * rewrite of each quick fix. [fixes] get a [GoSourceText] to name packages (missing imports are collected there and added after the edits).
 */
internal class GoFixFinding(
    val anchor: PsiElement,
    val message: String,
    val fixes: List<Pair<String, (GoSourceText) -> List<GoFixEdit>?>>,
    val range: TextRange? = null,
)

/**
 * Base of the Go fix inspections of this package: the version gate ([minVersion] against the module's `go` directive) and quick
 * fixes that re-detect the finding at apply time (so they hold no PSI) and apply its text edits plus import insertions.
 */
abstract class GoFix2InspectionBase : GoAnalysisInspectionBase() {

    /** The lowest `go` directive the suggestion needs (`"1.21"`). */
    protected abstract val minVersion: String

    /** The finding at [element] (the visited element is always the anchor), or null. */
    internal abstract fun detect(element: PsiElement, file: GoFile): GoFixFinding?

    final override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (!GoFixGoVersion.atLeast(file, minVersion)) return
        val finding = detect(element, file) ?: return
        check(finding.anchor === element) { "a Go fix finding is anchored at the visited element" }
        val fixes = finding.fixes.indices.map { GoFixQuickFix(this, it, finding.fixes[it].first) }.toTypedArray<LocalQuickFix>()
        holder.registerProblem(element, finding.message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, finding.range, *fixes)
    }
}

/** Applies the [index]-th rewrite of the finding [inspection] detects again at the descriptor's element. */
internal class GoFixQuickFix(private val inspection: GoFix2InspectionBase, private val index: Int, private val text: String) : LocalQuickFix {
    override fun getFamilyName(): String = text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile as? GoFile ?: return
        val finding = inspection.detect(element, file) ?: return
        val source = GoSourceText(file)
        val edits = finding.fixes.getOrNull(index)?.second?.invoke(source) ?: return
        val document = GoImportEdits.document(file) ?: return
        for (edit in edits.sortedByDescending { it.range.startOffset }) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
        GoImportEdits.commit(file, document)
        if (source.imports.isEmpty()) return
        val committed = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document) as? GoFile ?: return
        for (path in source.imports) GoImportInserter.addImport(committed, document, path)
        GoImportEdits.commit(committed, document)
    }
}

/** PSI helpers of the Go fix inspections. */
internal object GoFixPsi {

    fun unparen(e: GoExpression?): GoExpression? = GoLintPsi.unparen(e)

    /** `path.Name` / `path.Type.Name` of what [call] calls, when its simple name is one of [names] (checked before resolving). */
    fun callee(call: GoCallExpr, vararg names: String): String? {
        val ref = GoLintPsi.calleeReference(call) ?: return null
        if (ref.identifier.text !in names) return null
        return GoSimplePsi.memberKey(GoSemanticService.getInstance(call.project).resolve(ref).singleOrNull())
    }

    fun isCallTo(e: PsiElement?, key: String): Boolean = e is GoCallExpr && callee(e, key.substringAfterLast('.')) == key

    fun args(call: GoCallExpr): List<GoExpression> = with(GoPsiUtil) { call.argumentList.expressions }

    /** Whether the call has `...` or a type argument (`new(T)`): such calls are not rewritten. */
    fun hasEllipsis(call: GoCallExpr): Boolean = with(GoPsiUtil) { call.argumentList.hasEllipsis }

    fun resolve(ref: GoReferenceExpression): PsiElement? = GoSemanticService.getInstance(ref.project).resolve(ref).singleOrNull()

    /** Unqualified references to [variable] inside [scope]. */
    fun references(scope: PsiElement, variable: GoNamedElement): List<GoReferenceExpression> {
        val name = variable.name ?: return emptyList()
        if (!scope.text.contains(name)) return emptyList()
        return PsiTreeUtil.findChildrenOfType(scope, GoReferenceExpression::class.java)
            .filter { it.expression == null && it.identifier.text == name && resolve(it) == variable }
    }

    /** The statement of a statement list that contains [element] (itself included). */
    fun listStatement(element: PsiElement): PsiElement? = GoLintPsi.listStatement(element)

    fun isStatementList(e: PsiElement?): Boolean = e is GoBlock || e is GoExprCaseClause || e is GoTypeCaseClause || e is GoCommClause

    /** The statement before [statement] in its statement list (comments and semicolons skipped). */
    fun previousStatement(statement: PsiElement): PsiElement? {
        var e = statement.prevSibling
        while (e != null && (e is com.intellij.psi.PsiWhiteSpace || e is com.intellij.psi.PsiComment || e.node.elementType == GoTypes.SEMICOLON || e.node.elementType == GoTypes.SEMICOLON_SYNTHETIC)) e = e.prevSibling
        return e?.takeIf { it is io.github.golangsupport.lang.psi.GoStatement }
    }

    /** The range deleting [statement] with its whole line when it stands alone on it. */
    fun deleteStatement(statement: PsiElement): GoFixEdit {
        val text = statement.containingFile.text
        val range = GoLintPsi.statementRange(statement, text)
        if (range != statement.textRange) return GoFixEdit.delete(range)
        // `{ defer wg.Done(); work() }`: the explicit `;` and the spaces after it go too
        var end = range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        if (end < text.length && text[end] == ';') {
            end++
            while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
            return GoFixEdit.delete(TextRange(range.startOffset, end))
        }
        return GoFixEdit.delete(range)
    }

    /** The innermost function body (declaration or literal) holding [element]. */
    fun enclosingBody(element: PsiElement): GoBlock? {
        var e = element.parent
        while (e != null && e !is GoFile) {
            if (e is GoBlock && (e.parent is io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration || e.parent is io.github.golangsupport.lang.psi.GoFunctionLit)) return e
            e = e.parent
        }
        return null
    }

    /** The text of [e] with every range of [cuts] (inside it, absolute offsets) removed. */
    fun textWithout(e: PsiElement, cuts: List<TextRange>): String {
        val sb = StringBuilder(e.text)
        val start = e.textRange.startOffset
        for (cut in cuts.sortedByDescending { it.startOffset }) sb.delete(cut.startOffset - start, cut.endOffset - start)
        return sb.toString()
    }
}
