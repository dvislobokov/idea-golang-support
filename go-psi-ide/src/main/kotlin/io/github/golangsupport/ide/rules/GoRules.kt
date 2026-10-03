package io.github.golangsupport.ide.rules

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommCase
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoSwitchStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * "Go lint rules": the one inspection that runs every [GoRule]. The platform walks the file once and hands each element to this
 * visitor, which calls only the rules subscribed to the element's kind ([GoRuleScope]); FILE and PACKAGE rules run once, at the
 * package clause. The subscriber lists come filtered from [GoRuleSnapshot] (enabled rules of the file's configuration, dumb mode,
 * gopls mode), so a disabled rule costs nothing. Dumb-aware: rules that need only syntax keep working while the IDE indexes.
 * Files outside [GoAnalysisScope] (vendor, testdata, generated, libraries) are not analysed.
 */
class GoRules : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? GoFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!GoAnalysisScope.isAnalysed(file)) return PsiElementVisitor.EMPTY_VISITOR
        val project = file.project
        val goplsDiagnostics = !GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, project)
        val byScope = GoRuleSet.getInstance(project).snapshot(file).forRun(DumbService.isDumb(project), goplsDiagnostics)
        if (byScope.all { it.isEmpty() }) return PsiElementVisitor.EMPTY_VISITOR
        return GoRuleVisitor(byScope, GoRuleContext(file, holder, isOnTheFly))
    }

    companion object {
        const val SHORT_NAME = "GoRules"

        /** Test hooks: every element the visitor receives, every problem a rule reports. */
        @TestOnly @Volatile var visitListener: ((PsiElement) -> Unit)? = null
        @TestOnly @Volatile var reportListener: ((GoRule, String) -> Unit)? = null
    }
}

private val LOG = Logger.getInstance(GoRules::class.java)

internal class GoRuleVisitor(byScope: Array<Array<GoActiveRule>>, private val ctx: GoRuleContext) : PsiElementVisitor() {
    private val calls = byScope[GoRuleScope.CALL.ordinal]
    private val expressions = byScope[GoRuleScope.EXPRESSION.ordinal]
    private val statements = byScope[GoRuleScope.STATEMENT.ordinal]
    private val functions = byScope[GoRuleScope.FUNCTION.ordinal]
    private val types = byScope[GoRuleScope.TYPE_SPEC.ordinal]
    private val files = byScope[GoRuleScope.FILE.ordinal]
    private val packages = byScope[GoRuleScope.PACKAGE.ordinal]

    override fun visitElement(element: PsiElement) {
        GoRules.visitListener?.invoke(element)
        when (element) {
            is GoExpression -> {
                if (calls.isNotEmpty() && element is GoCallExpr) run(calls, element)
                if (expressions.isNotEmpty()) run(expressions, element)
                if (functions.isNotEmpty() && element is GoFunctionLit) run(functions, element)
            }
            is GoStatement -> when {
                element is GoFunctionOrMethodDeclaration -> if (functions.isNotEmpty()) run(functions, element)
                statements.isNotEmpty() && isStatement(element) -> run(statements, element)
            }
            is GoTypeSpec -> if (types.isNotEmpty()) run(types, element)
            is GoPackageClause -> if (element.parent === ctx.file) {
                if (files.isNotEmpty()) run(files, ctx.file)
                if (packages.isNotEmpty()) runPackageRules()
            }
        }
    }

    private fun run(rules: Array<GoActiveRule>, element: PsiElement) {
        for (active in rules) {
            ctx.current = active
            try {
                active.rule.check(element, ctx)
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.error("Go rule ${active.rule.id} failed on ${element.javaClass.simpleName} in ${ctx.file.name}", e)
            }
        }
    }

    /** Package results are computed once per package (and change) and reported in the file being highlighted. */
    private fun runPackageRules() {
        val file = ctx.file
        val vf = GoPsiUtil.originalVirtualFile(file)
        for (active in packages) {
            ctx.current = active
            val stored = try {
                GoPackageResults.of(file, active)[vf].orEmpty()
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.error("Go rule ${active.rule.id} failed on the package of ${file.name}", e)
                emptyList()
            }
            for (problem in stored) {
                val element = coveringElement(file, problem.range) ?: continue
                val inElement = problem.range.shiftLeft(element.textRange.startOffset)
                ctx.report(element, inElement.takeIf { it.length < element.textLength }, problem.message, *problem.fixes.toTypedArray())
            }
        }
    }

    private companion object {
        /** A statement of a statement list, a labeled statement's body, an `else if`, or the init / post statement of a control clause. */
        fun isStatement(e: GoStatement): Boolean = when (val parent = e.parent) {
            is GoBlock, is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause, is GoCommCase, is GoLabeledStatement -> true
            is GoIfStatement -> e is GoSimpleStatement || e is GoIfStatement && parent.statementList.lastOrNull() === e
            is GoSwitchStatement, is GoForClause -> e is GoSimpleStatement
            else -> false
        }
    }
}

/** Results of PACKAGE rules per package directory, kept until a file of the package (or what it depends on) changes. */
internal object GoPackageResults {
    private val KEY = Key.create<CachedValue<ConcurrentHashMap<String, Map<VirtualFile, List<GoStoredProblem>>>>>("gopsi.rules.packageResults")

    fun of(file: GoFile, active: GoActiveRule): Map<VirtualFile, List<GoStoredProblem>> {
        val pkg = GoRulePackage.of(file) ?: return emptyMap()
        val dir = PsiManager.getInstance(file.project).findDirectory(pkg.directory) ?: return compute(pkg, active)
        val trackers = GoTrackers.getInstance(file.project)
        val results = CachedValuesManager.getCachedValue(dir, KEY) {
            // any change of a file of the package (bodies included) or of what the package depends on drops every result
            val deps = trackers.packageDependencies(dir).toMutableList<Any>()
            for (f in pkg.files) deps += trackers.forFile(f)
            CachedValueProvider.Result.create(ConcurrentHashMap(), *deps.toTypedArray())
        }
        return results.getOrPut("${active.rule.id}\u0000${active.options.raw}") { compute(pkg, active) }
    }

    private fun compute(pkg: GoRulePackage, active: GoActiveRule): Map<VirtualFile, List<GoStoredProblem>> {
        val ctx = GoPackageRuleContext(pkg.files.firstOrNull()?.project ?: return emptyMap(), active)
        active.rule.checkPackage(pkg, ctx)
        return ctx.problems
    }
}
