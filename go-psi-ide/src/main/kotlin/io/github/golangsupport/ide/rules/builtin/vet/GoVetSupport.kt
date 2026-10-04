package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoExpressionRule
import io.github.golangsupport.ide.rules.GoFileRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.api.GoVersion
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeRenderer

/** Base of the govet call checks of batch B6: the callee's simple name is matched against [calleeNames] before anything is resolved. */
abstract class GoVetCallRule : GoCallRule() {
    override val linter: String get() = "govet"
    override val needs: Set<GoRuleNeed> get() = GoVetPsi.TYPES

    protected abstract val calleeNames: Set<String>

    final override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val ref = GoLintPsi.calleeReference(call) ?: return
        if (ref.identifier.text !in calleeNames) return
        val target = ctx.resolve(ref).singleOrNull() ?: return
        checkCallee(call, ref, target, ctx)
    }

    /** [target] is the declaration the callee resolves to (a function, method declaration or interface method). */
    protected abstract fun checkCallee(call: GoCallExpr, callee: GoReferenceExpression, target: PsiElement, ctx: GoRuleContext)
}

/** Base of the govet expression checks of batch B6. */
abstract class GoVetExprRule : GoExpressionRule() {
    override val linter: String get() = "govet"
    override val needs: Set<GoRuleNeed> get() = GoVetPsi.TYPES
}

/** Base of the govet file checks of batch B6. */
abstract class GoVetFileRule : GoFileRule() {
    override val linter: String get() = "govet"
    override val needs: Set<GoRuleNeed> get() = GoVetPsi.TYPES
}

/** Base of the staticcheck file checks of batch B6. */
abstract class GoStaticcheckFileRule : GoFileRule() {
    override val linter: String get() = "staticcheck"
}

/** PSI, type and version helpers of the batch-B6 checks. */
internal object GoVetPsi {
    val TYPES: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES)
    val TYPES_INDEX: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES, GoRuleNeed.PROJECT_INDEX)

    /** `path.Name` of a function, `path.Type.Name` of a method declaration (as `GoStaticcheckPsi.calleeKey`), or null. */
    fun key(target: PsiElement): String? = when (target) {
        is GoMethodDeclaration -> "${GoAnalysisPsi.packagePath(target) ?: return null}.${target.receiverTypeName ?: return null}.${target.name}"
        is GoFunctionDeclaration -> "${GoAnalysisPsi.packagePath(target) ?: return null}.${target.name}"
        else -> null
    }

    /** The argument expressions as written (spread calls included). */
    fun args(call: GoCallExpr): List<GoExpression> = call.argumentList?.expressions.orEmpty()

    fun hasEllipsis(call: GoCallExpr): Boolean = call.argumentList?.hasEllipsis == true

    fun isBuiltinFile(element: PsiElement): Boolean = (element.containingFile as? GoFile)?.packageName == "builtin"

    /** `types.Type.String()`: named types qualified by their import path (`go/scanner.Error`), predeclared ones bare. */
    fun pathTypeString(type: GoType): String = GoTypeRenderer.render(type) { named -> if (isBuiltinFile(named.declaration)) null else named.pkgPath }

    /** `types.TypeString(t, (*types.Package).Name)`: named types qualified by their package name, own package included. */
    fun nameTypeString(type: GoType): String = GoTypeRenderer.render(type) { named ->
        if (isBuiltinFile(named.declaration)) null else (named.declaration.containingFile as? GoFile)?.packageName ?: named.pkgPath?.substringAfterLast('/')
    }

    /** The default type of an untyped constant type (`untyped int` -> `int`); other types unchanged. */
    fun defaultType(type: GoType): GoType {
        if (type !is GoBasicType || !type.isUntyped) return type
        return when (type.kind) {
            GoBasicKind.UNTYPED_BOOL -> GoBasicType.BOOL
            GoBasicKind.UNTYPED_INT -> GoBasicType.INT
            GoBasicKind.UNTYPED_RUNE -> GoBasicType.RUNE
            GoBasicKind.UNTYPED_FLOAT -> GoBasicType.FLOAT64
            GoBasicKind.UNTYPED_COMPLEX -> GoBasicType.COMPLEX128
            GoBasicKind.UNTYPED_STRING -> GoBasicType.STRING
            else -> type
        }
    }

    fun isNamed(type: GoType, path: String, name: String): Boolean = type is GoNamedType && type.name == name && type.pkgPath == path

    /** `*path.name`. */
    fun isPointerTo(type: GoType, path: String, name: String): Boolean = type is GoPointerType && isNamed(type.elem, path, name)

    // ---- versions

    /** The `go` directive of the module of [file] as `go1.21` (as `types.Package.GoVersion`), or null without a module or directive. */
    fun moduleGoVersion(file: GoFile): String? {
        val vf = GoPsiUtil.originalVirtualFile(file)
        val graph = GoModuleGraphProvider.getInstance(file.project).graphFor(vf) ?: return null
        val path = vf.path
        val module = graph.mainModules.filter { m -> m.dir?.let { path.startsWith(it.toString().replace(java.io.File.separatorChar, '/') + "/") } == true }
            .maxByOrNull { it.dir.toString().length } ?: graph.mainModules.singleOrNull() ?: return null
        val text = module.goVersion?.trim()?.removePrefix("go")?.takeIf { it.isNotEmpty() } ?: return null
        return "go$text"
    }

    /** The Go version the `//go:build` line of [file] implies (`ast.File.GoVersion`), or null. */
    fun fileGoVersion(file: GoFile): String? = CachedValuesManager.getCachedValue(file, FILE_VERSION) {
        val header = GoBuildConstraintEvaluator.parseFileHeader(file.viewProvider.contents)
        val v = header.goBuild?.let { runCatching { GoBuildConstraintEvaluator.goVersion(GoBuildConstraintEvaluator.parse(it)) }.getOrNull() }
        CachedValueProvider.Result.create(Box(v), file)
    }.value

    private class Box(val value: String?)

    private val FILE_VERSION = Key.create<com.intellij.psi.util.CachedValue<Box>>("gopsi.rules.vet.fileGoVersion")

    /** `go/version.Compare`: invalid versions (and null) sort before valid ones. */
    fun compare(a: String?, b: String?): Int {
        val x = a?.let(GoVersion::parse)
        val y = b?.let(GoVersion::parse)
        return when {
            x == null && y == null -> 0
            x == null -> -1
            y == null -> 1
            else -> x.compareTo(y)
        }
    }

    /** `go/version.Lang`: `go1.21` for `go1.21.3`; null for an invalid version. */
    fun lang(v: String?): String? = v?.let(GoVersion::parse)?.languageVersion?.toString()

    // ---- comments

    /** The comments of [file] in source order. */
    fun comments(file: PsiFile): List<PsiComment> {
        val out = ArrayList<PsiComment>()
        com.intellij.psi.SyntaxTraverser.psiTraverser(file).filter(PsiComment::class.java).forEach { out += it }
        return out
    }

    /** The 1-based column of [offset] in [text] counted in characters. */
    fun column(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return offset - i + 1
    }

    /** The number of line breaks in [text] between [from] and [to]. */
    fun newlines(text: CharSequence, from: Int, to: Int): Int {
        var n = 0
        for (i in from until to) if (text[i] == '\n') n++
        return n
    }

    /** The comments directly before the first non-comment child of [holder] (its doc comment run), as the leading comment binder bound them. */
    fun leadingComments(holder: PsiElement): List<PsiComment> {
        val result = ArrayList<PsiComment>()
        var child = holder.firstChild
        while (child != null) {
            when (child) {
                is PsiComment -> result += child
                is PsiWhiteSpace -> {}
                else -> break
            }
            child = child.nextSibling
        }
        return result
    }

    /** Whether [e] is a type spec declared at the top level of its file. */
    fun isTopLevelType(e: PsiElement): Boolean = e is GoTypeSpec && e.parent?.parent is GoFile
}
