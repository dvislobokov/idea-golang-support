package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.lang.psi.impl.GoDocComments
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * staticcheck SA1019: a use of a function, variable, constant, type, field or method of another package whose doc comment has a
 * `Deprecated: ` paragraph, and imports of deprecated packages. Selectors only (`pkg.Name`, `x.Field`, `x.Method`, keys of struct
 * literals), as staticcheck. Standard-library symbols are reported only when staticcheck's table knows the deprecation and the file
 * targets (module `go` directive / `//go:build`) at least the version that deprecated them. A deprecated function may use deprecated
 * symbols. Shown struck through.
 */
class GoDeprecatedRule : GoStaticcheckFileRule() {
    override val id: String get() = "SA1019"
    override val title: String get() = "Using a deprecated function, variable, constant or field"
    override val description: String get() =
        "Marks uses of identifiers whose documentation has a paragraph starting with <code>Deprecated: </code>, and imports of deprecated " +
            "packages. For the standard library the use is reported only once the targeted Go version has the deprecation."
    override val needs: Set<GoRuleNeed> get() = GoVetPsi.TYPES_INDEX

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        val here = GoAnalysisPsi.packagePath(file)
        for (spec in file.imports) checkImport(spec, here, ctx)
        val traverser = SyntaxTraverser.psiTraverser(file)
        for (e in traverser) {
            when (e) {
                is GoReferenceExpression -> {
                    val q = e.expression ?: continue
                    val target = ctx.resolve(e).singleOrNull() as? GoNamedElement ?: continue
                    val node: PsiElement = if (isTypeExpression(q, ctx)) e.identifier else e
                    check(target, node, { selectorName(e, target, ctx) }, here, ctx)
                }
                is GoTypeReferenceExpression -> {
                    val q = e.referenceExpression ?: continue
                    val target = ctx.semantic.resolve(e) as? GoNamedElement ?: continue
                    check(target, e, { "${GoAnalysisPsi.packagePath(target) ?: q.text}.${target.name}" }, here, ctx)
                }
                is GoCompositeLit -> checkKeys(e, here, ctx)
            }
        }
    }

    private fun checkKeys(lit: GoCompositeLit, here: String?, ctx: GoRuleContext) {
        lit.typeReferenceExpression ?: return // elided and composite-typed literals are not looked at
        if (lit.typeList.isNotEmpty()) return
        val type = ctx.typeOf(lit)
        val struct = type.underlying() as? GoStructType ?: return
        for (element in lit.literalValue?.elements.orEmpty()) {
            val key = element.key?.expression as? GoReferenceExpression ?: return
            if (key.expression != null) return
            val target = struct.field(key.identifier.text)?.declaration ?: continue
            if (target !is GoFieldDefinition && target !is GoAnonymousFieldDefinition) continue
            check(target, key, { "(${GoVetPsi.pathTypeString(type)}).${key.identifier.text}" }, here, ctx)
        }
    }

    private fun check(target: GoNamedElement, node: PsiElement, name: () -> String, here: String?, ctx: GoRuleContext) {
        if (GoVetPsi.isBuiltinFile(target)) return
        val path = GoAnalysisPsi.packagePath(target) ?: return
        if (samePackage(here, path)) return
        val message = GoDeprecations.of(target) ?: return
        report(message, node, name(), path, enclosingFunction(node), ctx)
    }

    private fun checkImport(spec: GoImportSpec, here: String?, ctx: GoRuleContext) {
        val path = spec.path
        if (path == "C" || path == "syscall") return
        val literal = spec.stringLiteral ?: return
        if (here != null && (here.removeSuffix("_test") == path || here.removeSuffix(".test") == path || here.removeSuffix(".test") == path.removeSuffix("_test"))) return
        val message = GoDeprecations.ofPackage(path, ctx) ?: return
        report(message, literal, path, path, null, ctx)
    }

    /** staticcheck's `handleDeprecation`. */
    private fun report(message: String, node: PsiElement, name: String, path: String, function: GoFunctionOrMethodDeclaration?, ctx: GoRuleContext) {
        val std = GoStdlibTables.deprecations[name]
        if (std == null && !path.contains('.')) return // a stdlib deprecation staticcheck does not know the version of
        if (std != null && GoVetPsi.compare(stdlibVersion(ctx.file), std.since) < 0) return
        if (function != null && GoDeprecations.of(function) != null) return // deprecated functions may use deprecated symbols
        val text = when {
            std == null -> "$name is deprecated: $message"
            std.alternative == GoStdlibTables.NEVER_USE -> "$name has been deprecated since ${goVersion(std.since)} because it shouldn't be used: $message"
            std.alternative == std.since || std.alternative == GoStdlibTables.NO_LONGER -> "$name has been deprecated since ${goVersion(std.since)}: $message"
            else -> "$name has been deprecated since ${goVersion(std.since)} and an alternative has been available since ${goVersion(std.alternative)}: $message"
        }
        ctx.reportDeprecated(node, null, text)
    }

    private fun goVersion(v: String): String = "Go " + v.removePrefix("go")

    /** staticcheck's `code.StdlibVersion`: the module's version, or the file's `//go:build` version where it applies. */
    private fun stdlibVersion(file: GoFile): String? {
        val n = GoVetPsi.moduleGoVersion(file)
        val nf = GoVetPsi.fileGoVersion(file) ?: return n
        return if (GoVetPsi.compare(n, "go1.21") < 0 || GoVetPsi.compare(nf, n) > 0) nf else n
    }

    private fun samePackage(here: String?, there: String): Boolean {
        if (here == null) return false
        return here == there || here.removeSuffix("_test") == there || here.removeSuffix(".test") == there ||
            here.removeSuffix(".test") == there.removeSuffix("_test")
    }

    private fun enclosingFunction(node: PsiElement): GoFunctionOrMethodDeclaration? {
        var e: PsiElement? = node
        while (e != null && e !is GoFile) {
            if (e is GoFunctionOrMethodDeclaration && e.parent is GoFile) return e
            e = e.parent
        }
        return null
    }

    /** Whether [q] names a type (`T.Method` method expressions report on the method name only). */
    private fun isTypeExpression(q: PsiElement, ctx: GoRuleContext): Boolean {
        val ref = q as? GoReferenceExpression ?: return false
        return ctx.resolve(ref).singleOrNull() is GoTypeSpec
    }

    /** staticcheck's `code.SelectorName`: `path.Name` for a qualified identifier, `(T).Name` / `(*T).Name` for a field or method. */
    private fun selectorName(e: GoReferenceExpression, target: GoNamedElement, ctx: GoRuleContext): String {
        val q = e.expression!!
        val qualifier = (q as? GoReferenceExpression)?.takeIf { it.expression == null }?.let { ctx.resolve(it).singleOrNull() }
        val name = e.identifier.text
        if (qualifier is GoImportSpec) return "${GoAnalysisPsi.packagePath(target)}.$name"
        val type = if (qualifier is GoTypeSpec) ctx.semantic.declarationType(qualifier) else ctx.typeOf(q)
        if (type is GoUnknownType) return "?.$name"
        val isField = target is GoFieldDefinition || target is GoAnonymousFieldDefinition
        val recv = if (isField && type is GoPointerType) type.elem else type
        return "(${GoVetPsi.pathTypeString(recv)}).$name"
    }
}

/** The `Deprecated: ` paragraphs of doc comments, as staticcheck's `fact_deprecated` finds them; a file's AST is read only when its text has one. */
internal object GoDeprecations {
    private const val MARK = "Deprecated: "

    /** The deprecation message of [e] (the paragraph after `Deprecated: `, lines joined), or null. */
    fun of(e: GoNamedElement): String? {
        val file = e.containingFile as? GoFile ?: return null
        if (!mayHave(file)) return null
        val holders: List<PsiElement?> = when (e) {
            is GoFunctionOrMethodDeclaration, is GoMethodSpec -> listOf(e)
            is GoTypeSpec -> listOf(e.parent, e)
            is GoVarDefinition -> (e.parent as? GoVarSpec).let { listOf(it?.parent, it) }
            is GoConstDefinition -> (e.parent as? GoConstSpec).let { listOf(it?.parent, it) }
            is GoFieldDefinition, is GoAnonymousFieldDefinition -> listOf(e.parent)
            else -> emptyList()
        }
        for (h in holders) {
            h ?: continue
            message(GoVetPsi.leadingComments(h))?.let { return it }
        }
        return null
    }

    /** The deprecation of the package [path] imported from [ctx]'s file: a `Deprecated: ` paragraph in the package doc of any of its files. */
    fun ofPackage(path: String, ctx: GoRuleContext): String? {
        val model = GoPackageModel.getInstance(ctx.project)
        val pkg = model.resolveImport(path, ctx.file) ?: return null
        val manager = PsiManager.getInstance(ctx.project)
        for (vf in pkg.goFiles) {
            val file = manager.findFile(vf) as? GoFile ?: continue
            if (!mayHave(file)) continue
            val clause = file.packageClause ?: continue
            message(GoVetPsi.leadingComments(clause))?.let { return it }
        }
        return null
    }

    private fun message(comments: List<PsiComment>): String? {
        if (comments.isEmpty()) return null
        val text = GoDocComments.text(comments.map { it.text }) ?: return null
        for (part in text.split("\n\n")) {
            if (!part.startsWith(MARK)) continue
            return part.substring(MARK.length).replace('\n', ' ').trimEnd()
        }
        return null
    }

    private val MAY_HAVE = Key.create<CachedValue<Boolean>>("gopsi.rules.sa1019.mayHave")

    /** Whether the text of [file] has `Deprecated: ` at all (read without building its AST). */
    private fun mayHave(file: GoFile): Boolean = CachedValuesManager.getCachedValue(file, MAY_HAVE) {
        CachedValueProvider.Result.create(file.viewProvider.contents.contains(MARK), file)
    }
}
