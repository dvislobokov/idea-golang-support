package io.github.golangsupport.ide.inspections.printf

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType

/** How a printf-like function reads its arguments (vet's `Kind`). */
enum class GoPrintKind {
    /** `Print`, `Println`, `Error`: operands formatted with `%v`. */
    PRINT,
    /** `Printf`, `Sprintf`, `Logf`: a format string and its arguments. */
    PRINTF,
    /** `fmt.Errorf` and its wrappers: like [PRINTF], and `%w` is allowed. */
    ERRORF,
}

/**
 * A call of a printf-like function: [name] as vet prints it (`fmt.Printf`, `(*log.Logger).Printf`, `(*testing.common).Errorf`),
 * the index of the format argument ([PRINTF]/[ERRORF] only) and of the first value argument.
 */
class GoPrintfCall(val call: GoCallExpr, val name: String, val kind: GoPrintKind, val formatIndex: Int, val firstArg: Int) {
    val arguments: List<GoExpression> by lazy { call.argumentList?.expressions ?: emptyList() }

    /** `f(format, args...)`: the arguments are a slice spread, so their count is unknown. */
    val spread: Boolean get() = call.argumentList?.hasEllipsis == true

    val format: GoExpression? get() = if (formatIndex >= 0) arguments.getOrNull(formatIndex) else null

    /** The value arguments (after the format). */
    val values: List<GoExpression> get() = arguments.drop(firstArg)

    val isPrintf: Boolean get() = kind != GoPrintKind.PRINT
}

/**
 * Recognition of printf-like calls (vet `printf`): the known functions of `fmt`, `log`, `testing` and `runtime/trace`, and the
 * functions and methods of the call's own package whose last two parameters are `(format string, args ...any)` (or the last one
 * is `args ...any` for Print-like ones) and whose body forwards `format, args...` to a printf-like function. Each body is
 * scanned once ([GoBodyCache], so an edit elsewhere keeps the result); the chain of wrappers is followed at most [MAX_DEPTH]
 * levels, one cached step per body. The user's [GoPrintfFunctions] come first: an excluded name is never printf-like, a marked one is.
 */
object GoPrintfCalls {
    const val MAX_DEPTH = 3

    private val FUNCTIONS = listOf("Print", "Printf", "Println", "Fatal", "Fatalf", "Fatalln", "Panic", "Panicf", "Panicln")
    private val TESTING = listOf("Error", "Errorf", "Fatal", "Fatalf", "Log", "Logf", "Skip", "Skipf")

    /** vet's `isPrint` table: full name to kind (an `f` suffix means a format). */
    private val KNOWN: Map<String, GoPrintKind> = buildMap {
        for (n in listOf("Append", "Appendf", "Appendln", "Errorf", "Fprint", "Fprintf", "Fprintln", "Print", "Printf", "Println", "Sprint", "Sprintf", "Sprintln")) {
            put("fmt.$n", kindOfName(n))
        }
        put("fmt.Errorf", GoPrintKind.ERRORF)
        put("runtime/trace.Logf", GoPrintKind.PRINTF)
        for (n in FUNCTIONS) {
            put("log.$n", kindOfName(n))
            put("(*log.Logger).$n", kindOfName(n))
        }
        for (n in TESTING) {
            put("(*testing.common).$n", kindOfName(n))
            put("(testing.TB).$n", kindOfName(n))
        }
    }

    private fun kindOfName(name: String): GoPrintKind = if (name.endsWith("f")) GoPrintKind.PRINTF else GoPrintKind.PRINT

    /** The printf-like call [call] makes, or null. */
    fun of(call: GoCallExpr): GoPrintfCall? {
        val service = GoSemanticService.getInstance(call.project)
        val callee = calleeOf(call, service) ?: return null
        val file = call.containingFile as? GoFile ?: return null
        val (name, kind) = kindOf(callee, file, service, 0) ?: return null
        val signature = service.declarationType(callee) as? GoSignatureType ?: return null
        val params = signature.params.size
        if (!signature.variadic || params == 0) return null
        val firstArg = params - 1
        val formatIndex = if (kind == GoPrintKind.PRINT) -1 else params - 2
        if (kind != GoPrintKind.PRINT && formatIndex < 0) return null
        return GoPrintfCall(call, name, kind, formatIndex, firstArg)
    }

    /** vet's full name of the function or method [call] invokes (what [GoPrintfFunctions] stores), or null. */
    fun nameOf(call: GoCallExpr): String? {
        val callee = calleeOf(call, GoSemanticService.getInstance(call.project)) ?: return null
        return fullName(callee, callee.containingFile as? GoFile ?: return null)
    }

    /** Whether [call] is not printf-like but could be marked so: its callee's last parameter is `...any`. */
    fun markable(call: GoCallExpr): Boolean {
        if (of(call) != null) return false
        val service = GoSemanticService.getInstance(call.project)
        val callee = calleeOf(call, service) ?: return false
        return markedKind(callee, service) != null
    }

    /** The kind of a function the user marked as printf-like: Printf-like after a `string` parameter, Print-like otherwise; null without `...any`. */
    private fun markedKind(callee: GoNamedElement, service: GoSemanticService): GoPrintKind? {
        val signature = service.declarationType(callee) as? GoSignatureType ?: return null
        if (!signature.variadic || signature.params.isEmpty() || !isAnySlice(signature.params.last().type)) return null
        val format = signature.params.getOrNull(signature.params.size - 2)?.type as? GoBasicType
        return if (format?.kind == GoBasicKind.STRING) GoPrintKind.PRINTF else GoPrintKind.PRINT
    }

    /** The declaration a call invokes when its callee is a (qualified) name: a function, a method or an interface method. */
    private fun calleeOf(call: GoCallExpr, service: GoSemanticService): GoNamedElement? {
        var e: GoExpression? = call.expression
        while (e is GoParenthesesExpr) e = e.inner as? GoExpression
        val ref = e as? GoReferenceExpression ?: return null
        val target = service.resolve(ref).singleOrNull() ?: return null
        return when (target) {
            is GoFunctionOrMethodDeclaration, is GoMethodSpec -> target as GoNamedElement
            else -> null
        }
    }

    /** The vet name and kind of [callee]: a known function, or a wrapper in the package of [from] (at most [MAX_DEPTH] levels). */
    private fun kindOf(callee: GoNamedElement, from: GoFile, service: GoSemanticService, depth: Int): Pair<String, GoPrintKind>? {
        val file = callee.containingFile as? GoFile ?: return null
        val name = fullName(callee, file) ?: return null
        val user = GoPrintfFunctions.getInstance()
        if (user.isExcluded(name)) return null
        KNOWN[name]?.let { return name to it }
        if (user.isExtra(name)) return markedKind(callee, service)?.let { name to it }
        if (depth >= MAX_DEPTH || callee !is GoFunctionOrMethodDeclaration) return null
        // Wrappers are looked for in the package of the call only: their bodies are read from the PSI.
        if (GoPsiUtil.originalVirtualFile(file).parent != GoPsiUtil.originalVirtualFile(from).parent) return null
        return wrapperKind(callee, from, service, depth)?.let { name to it }
    }

    /** vet's full name: `pkg/path.F`, `(*pkg/path.T).M`, `(pkg/path.T).M`, `(pkg/path.I).M`. */
    private fun fullName(callee: GoNamedElement, file: GoFile): String? {
        val name = callee.name ?: return null
        // The import path; a package outside any module or GOPATH (no path) is named by its package name.
        val pkg = GoSemanticService.getInstance(callee.project).packageOf(file)?.importPath ?: file.packageName ?: return null
        return when (callee) {
            is GoFunctionDeclaration -> "$pkg.$name"
            is GoMethodDeclaration -> {
                val receiver = callee.receiverTypeName ?: return null
                if (callee.isPointerReceiver) "(*$pkg.$receiver).$name" else "($pkg.$receiver).$name"
            }
            is GoMethodSpec -> {
                val spec = PsiTreeUtil.getParentOfType(callee, GoTypeSpec::class.java) ?: return null
                "($pkg.${spec.name}).$name"
            }
            else -> null
        }
    }

    /** A call in a wrapper's body that spreads the wrapper's `args` into its last argument, and whether it passes `format` before it. */
    private class Forward(val callee: GoNamedElement, val withFormat: Boolean)

    private val FORWARDS = Key.create<CachedValue<List<Forward>>>("gopsi.printf.forwards")

    /**
     * The kind of [decl] as a wrapper: its last parameter is `...any`, and its body forwards it with `...` to a printf-like
     * function, together with the `format string` parameter before it for [GoPrintKind.PRINTF]/[GoPrintKind.ERRORF].
     */
    private fun wrapperKind(decl: GoFunctionOrMethodDeclaration, from: GoFile, service: GoSemanticService, depth: Int): GoPrintKind? {
        val signature = service.declarationType(decl) as? GoSignatureType ?: return null
        if (!signature.variadic || signature.params.isEmpty()) return null
        val last = signature.params.last()
        if (!isAnySlice(last.type)) return null
        val format = signature.params.getOrNull(signature.params.size - 2)?.takeIf { (it.type as? GoBasicType)?.kind == GoBasicKind.STRING }
        val body = decl.block ?: return null
        val forwards = GoBodyCache.cached(body, FORWARDS) { forwardsOf(body, last.declaration, format?.declaration, service) }
        for (f in forwards) {
            val kind = kindOf(f.callee, from, service, depth + 1)?.second ?: continue
            if (f.withFormat && kind != GoPrintKind.PRINT) return kind
            if (!f.withFormat && kind == GoPrintKind.PRINT && format == null) return kind
        }
        return null
    }

    private fun isAnySlice(type: io.github.golangsupport.semantic.types.GoType): Boolean {
        val elem = (type as? GoSliceType)?.elem ?: return false
        val iface = elem.underlying() as? GoInterfaceType ?: return false
        return iface.isEmpty
    }

    private fun forwardsOf(body: GoBlock, args: GoNamedElement?, format: GoNamedElement?, service: GoSemanticService): List<Forward> {
        if (args !is GoParamDefinition) return emptyList()
        val out = ArrayList<Forward>()
        for (call in PsiTreeUtil.findChildrenOfType(body, GoCallExpr::class.java)) {
            val list = call.argumentList ?: continue
            if (!list.hasEllipsis) continue
            val exprs = list.expressions
            if (!refersTo(exprs.lastOrNull(), args, service)) continue
            val callee = calleeOf(call, service) ?: continue
            val withFormat = format != null && refersTo(exprs.getOrNull(exprs.size - 2), format, service)
            out += Forward(callee, withFormat)
        }
        return out
    }

    private fun refersTo(expr: GoExpression?, target: PsiElement, service: GoSemanticService): Boolean {
        val ref = expr as? GoReferenceExpression ?: return false
        if (ref.expression != null || ref.identifier?.text != (target as? GoNamedElement)?.name) return false
        return service.resolve(ref).singleOrNull() == target
    }
}
