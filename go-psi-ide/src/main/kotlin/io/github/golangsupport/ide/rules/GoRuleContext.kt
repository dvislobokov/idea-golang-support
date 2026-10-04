package io.github.golangsupport.ide.rules

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoInspectionSuppressor
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.types.GoType

/**
 * What a rule sees during one pass over one file: lazy, pass-scoped access to types, flow graphs and the package's files, the
 * options of the rule being run, and reporting (message prefixed with `[id]`, suppression comments honoured, the rule's level).
 * One instance per file pass, used from one thread; never keep it.
 */
class GoRuleContext internal constructor(val file: GoFile, private val holder: ProblemsHolder?, val isOnTheFly: Boolean) {

    val project: Project get() = file.project

    /** The rule being run and its effective level and options; set by the dispatcher before each call. */
    internal lateinit var current: GoActiveRule

    val rule: GoRule get() = current.rule

    val options: GoRuleOptions get() = current.options

    /** The value of [option] for the rule being run. */
    fun <T : Any> option(option: GoRuleOption<T>): T = current.options[option]

    // ---- semantics: everything below is cached by go-psi itself (GoBodyCache / GoTrackers); the context only saves the lookups

    val semantic: GoSemanticService by lazy(LazyThreadSafetyMode.NONE) { GoSemanticService.getInstance(file.project) }

    fun typeOf(expression: GoExpression): GoType = semantic.typeOf(expression)

    fun resolve(reference: GoReferenceExpression): List<PsiElement> = semantic.resolve(reference)

    private val flows = HashMap<PsiElement, GoControlFlow?>()

    /** The flow graph of the function [element] is (a declaration or literal) or is in; null outside functions or when unsupported. */
    fun flowOf(element: PsiElement): GoControlFlow? {
        val owner = when (element) {
            is GoFunctionOrMethodDeclaration, is GoFunctionLit -> element
            else -> GoPsiUtil.functionOwner(element) ?: return null
        }
        return flows.getOrPut(owner) {
            when (owner) {
                is GoFunctionOrMethodDeclaration -> GoControlFlow.of(owner)
                is GoFunctionLit -> GoControlFlow.of(owner)
                else -> null
            }
        }
    }

    /** The files of this file's package (build constraints applied, in-package tests included), from the project model; lazy. */
    val packageFiles: List<GoFile> by lazy(LazyThreadSafetyMode.NONE) { GoRulePackage.of(file)?.files ?: listOf(file) }

    // ---- reporting

    private val directives: GoRuleDirectives by lazy(LazyThreadSafetyMode.NONE) { GoRuleDirectives.of(file) }

    /** Whether a problem of the current rule at [element] is suppressed (`//nolint`, `//lint:ignore`, `//noinspection` of an alias or the id). */
    fun isSuppressed(element: PsiElement): Boolean = isSuppressed(element, element.textRange.startOffset)

    private fun isSuppressed(element: PsiElement, offset: Int): Boolean {
        val rule = current.rule
        if (directives.suppresses(rule, offset)) return true
        if (rule.aliases.isEmpty() && !SUPPRESSIBLE_ID.matches(rule.id)) return false
        val suppressor = GoInspectionSuppressor()
        return (rule.aliases + rule.id).any { suppressor.isSuppressedFor(element, it) }
    }

    /** Reports [message] on [element] (or [range] inside it) with [fixes]. Fixes must not keep PSI of other files. */
    fun report(element: PsiElement, message: String, vararg fixes: LocalQuickFix) = report(element, null, message, *fixes)

    fun report(element: PsiElement, range: TextRange?, message: String, vararg fixes: LocalQuickFix) = report(element, range, message, false, *fixes)

    /** Like [report], shown struck through (a use of a deprecated symbol) unless the rule's level is INFO. */
    fun reportDeprecated(element: PsiElement, range: TextRange?, message: String, vararg fixes: LocalQuickFix) = report(element, range, message, true, *fixes)

    private fun report(element: PsiElement, range: TextRange?, message: String, deprecated: Boolean, vararg fixes: LocalQuickFix) {
        val holder = holder ?: return
        val start = element.textRange.startOffset + (range?.startOffset ?: 0)
        if (isSuppressed(element, start)) return
        val active = current
        val text = "[${active.rule.id}] $message"
        val type = if (deprecated && active.level != GoRuleLevel.INFO) ProblemHighlightType.LIKE_DEPRECATED else active.level.highlightType
        val descriptor = holder.manager.createProblemDescriptor(element, range, text, type, isOnTheFly, *fixes)
        holder.registerProblem(descriptor)
        GoRules.reportListener?.invoke(active.rule, text)
    }

    private companion object {
        /** What `//noinspection` can name (SuppressionUtil's tool id pattern): `errcheck`, `SA4006`, not `revive:var-naming`. */
        val SUPPRESSIBLE_ID = Regex("""[\w.-]+""")
    }
}

/** A function declaration, method declaration or function literal as one thing for [GoFunctionRule]s. */
class GoRuleFunction private constructor(val element: PsiElement) {
    val declaration: GoFunctionOrMethodDeclaration? get() = element as? GoFunctionOrMethodDeclaration
    val literal: GoFunctionLit? get() = element as? GoFunctionLit
    val isLiteral: Boolean get() = element is GoFunctionLit

    /** The name of a declaration; null for a literal. */
    val name: String? get() = declaration?.name

    val signature: GoSignature? get() = declaration?.signature ?: literal?.signature

    /** The body; loads the AST of a lazily parsed body. */
    val body: GoBlock? get() = declaration?.block ?: literal?.block

    /** Where a problem about the whole function goes: the name of a declaration, the `func` keyword of a literal. */
    val anchor: PsiElement get() = declaration?.identifier ?: literal?.func ?: element

    companion object {
        fun of(element: PsiElement): GoRuleFunction? = if (element is GoFunctionOrMethodDeclaration || element is GoFunctionLit) GoRuleFunction(element) else null
    }
}

/** A package for [GoPackageRule]s: the files of one directory under the project's build context. */
class GoRulePackage internal constructor(val directory: VirtualFile, val name: String?, val importPath: String?, val files: List<GoFile>) {

    /** Files that are not `_test.go`. */
    val nonTestFiles: List<GoFile> get() = files.filter { !it.isTestFile }

    companion object {
        /**
         * The package of [file] from the project model ([GoPackageResolver]: build constraints applied, external `_test` package left out);
         * when the model does not know the directory, the `.go` files beside [file] with the same package name.
         */
        fun of(file: GoFile): GoRulePackage? {
            val vf = GoPsiUtil.originalVirtualFile(file)
            val dir = vf.parent ?: return null
            val manager = PsiManager.getInstance(file.project)
            val pkg = GoPackageResolver.getInstance(file.project).packageOf(dir)
            val name = file.packageName
            val files = if (pkg != null && (vf in pkg.goFiles || vf in pkg.testFiles)) {
                (pkg.goFiles + pkg.testFiles).mapNotNull { manager.findFile(it) as? GoFile }
            } else {
                dir.children.filter { it.extension == "go" }.mapNotNull { manager.findFile(it) as? GoFile }.filter { it.packageName == name }
            }
            return GoRulePackage(dir, pkg?.name ?: name, pkg?.importPath, files.sortedBy { it.name })
        }
    }
}

/** Where [GoPackageRule]s report: problems are kept per file and shown when that file is highlighted. */
class GoPackageRuleContext internal constructor(val project: Project, internal val active: GoActiveRule) {
    internal val problems = LinkedHashMap<VirtualFile, MutableList<GoStoredProblem>>()

    val rule: GoRule get() = active.rule

    val options: GoRuleOptions get() = active.options

    fun <T : Any> option(option: GoRuleOption<T>): T = active.options[option]

    /** Reports [message] on [element] of a file of the package. [fixes] are kept with the cached result: they must not hold PSI. */
    fun report(element: PsiElement, message: String, vararg fixes: LocalQuickFix) {
        val file = element.containingFile as? GoFile ?: return
        problems.getOrPut(GoPsiUtil.originalVirtualFile(file)) { ArrayList() } += GoStoredProblem(element.textRange, message, fixes.toList())
    }
}

internal class GoStoredProblem(val range: TextRange, val message: String, val fixes: List<LocalQuickFix>)

/** The element of [file] that covers [range] best (for a stored package problem). */
internal fun coveringElement(file: GoFile, range: TextRange): PsiElement? {
    val first = file.findElementAt(range.startOffset) ?: return null
    val last = file.findElementAt(maxOf(range.startOffset, range.endOffset - 1)) ?: first
    var e: PsiElement? = PsiTreeUtil.findCommonParent(first, last)
    while (e != null && !e.textRange.contains(range)) e = e.parent
    return e
}
