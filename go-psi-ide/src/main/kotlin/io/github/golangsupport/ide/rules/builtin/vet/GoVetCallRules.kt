package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType
import java.util.IdentityHashMap

/** govet `deepequalerrors`: `reflect.DeepEqual(err1, err2)` on values that are or hold errors. */
class GoVetDeepEqualErrorsRule : GoVetCallRule() {
    override val id: String get() = "govet:deepequalerrors"
    override val title: String get() = "reflect.DeepEqual on errors"
    override val description: String get() =
        "govet <code>deepequalerrors</code>: <code>reflect.DeepEqual(err1, err2)</code> compares the dynamic values of errors, which often " +
            "differ for errors that mean the same; use <code>errors.Is</code> or compare to a sentinel. Off in golangci-lint's govet defaults."
    override val enabledWithLinter: Boolean get() = false
    override val calleeNames: Set<String> get() = DEEP_EQUAL

    override fun checkCallee(call: GoCallExpr, callee: GoReferenceExpression, target: PsiElement, ctx: GoRuleContext) {
        if (GoVetPsi.key(target) != "reflect.DeepEqual") return
        val args = GoVetPsi.args(call)
        if (args.size < 2) return
        if (containsError(ctx.typeOf(args[0]), ctx) && containsError(ctx.typeOf(args[1]), ctx)) ctx.report(call, "avoid using reflect.DeepEqual with errors")
    }

    /** vet's `containsError`: the predeclared `error` (a type defined as it included), or a composite holding one. */
    private fun containsError(type: GoType, ctx: GoRuleContext): Boolean {
        val seen = IdentityHashMap<Any, Boolean>()
        fun check(t: GoType, depth: Int): Boolean {
            if (depth > 16 || seen.put(t, true) != null) return false
            return when (t) {
                is GoNamedType -> GoAnalysisPsi.isError(t) || if (t.underlying() is GoInterfaceType) definedAsError(t, ctx) else check(t.underlying(), depth + 1)
                is GoPointerType -> check(t.elem, depth + 1)
                is GoSliceType -> check(t.elem, depth + 1)
                is GoArrayType -> check(t.elem, depth + 1)
                is GoMapType -> check(t.key, depth + 1) || check(t.value, depth + 1)
                is GoStructType -> t.fields.any { check(it.type, depth + 1) }
                else -> false
            }
        }
        return check(type, 0)
    }

    /** `type E error` (or a chain of such definitions): its underlying type is the very interface of `error`. */
    private fun definedAsError(t: GoNamedType, ctx: GoRuleContext): Boolean {
        var spec: GoTypeSpec = t.declaration
        repeat(8) {
            val outer = spec.type ?: return false
            val node = PsiTreeUtil.getStubChildOfType(outer, io.github.golangsupport.lang.psi.GoType::class.java) ?: outer
            val named = GoExpressionPsi.typeOf(node) as? GoNamedType ?: return false
            if (GoAnalysisPsi.isError(named)) return true
            spec = named.declaration
        }
        return false
    }

    private companion object {
        val DEEP_EQUAL = setOf("DeepEqual")
    }
}

/** govet `reflectvaluecompare`: `==` / `!=` and `reflect.DeepEqual` on `reflect.Value`s compare the wrappers, not the values. */
class GoVetReflectValueCompareRule : GoVetExprRule() {
    override val id: String get() = "govet:reflectvaluecompare"
    override val title: String get() = "Comparison of reflect.Value values"
    override val description: String get() =
        "govet <code>reflectvaluecompare</code>: <code>v1 == v2</code> on <code>reflect.Value</code> compares the reflection wrappers, not the " +
            "values they hold; compare <code>v1.Interface()</code> or use <code>v1.Equal(v2)</code>. <code>v == reflect.Value{}</code> is allowed. " +
            "Off in golangci-lint's govet defaults."
    override val enabledWithLinter: Boolean get() = false

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        when (expression) {
            is GoConditionalExpr -> {
                val op = GoExpressionPsi.op(expression)
                if (op != "==" && op != "!=") return
                val right = expression.right ?: return
                if (isReflectValue(expression.left, ctx) || isReflectValue(right, ctx)) ctx.report(expression, "avoid using $op with reflect.Value")
            }
            is GoCallExpr -> {
                val ref = GoLintPsi.calleeReference(expression) ?: return
                if (ref.identifier.text != "DeepEqual") return
                val target = ctx.resolve(ref).singleOrNull() ?: return
                if (GoVetPsi.key(target) != "reflect.DeepEqual") return
                val args = GoVetPsi.args(expression)
                if (args.size < 2) return
                if (isReflectValue(args[0], ctx) || isReflectValue(args[1], ctx)) ctx.report(expression, "avoid using reflect.DeepEqual with reflect.Value")
            }
            else -> {}
        }
    }

    private fun isReflectValue(e: GoExpression, ctx: GoRuleContext): Boolean =
        e !is GoCompositeLit && GoVetPsi.isNamed(ctx.typeOf(e), "reflect", "Value")
}

/** govet `hostport`: `fmt.Sprintf("%s:%d", host, port)` passed to `net.Dial` does not work with IPv6. Fix: `net.JoinHostPort`. */
class GoVetHostPortRule : GoVetCallRule() {
    override val id: String get() = "govet:hostport"
    override val title: String get() = "Address built with Sprintf passed to net.Dial"
    override val description: String get() =
        "govet <code>hostport</code>: an address made by <code>fmt.Sprintf(\"%s:%d\", host, port)</code> (or <code>\"%s:%s\"</code>) and " +
            "passed to <code>net.Dial</code>, <code>net.DialTimeout</code> or <code>net.Dialer.Dial</code> breaks for IPv6 hosts, which need " +
            "brackets; <code>net.JoinHostPort</code> adds them."
    override val calleeNames: Set<String> get() = DIALS

    override fun checkCallee(call: GoCallExpr, callee: GoReferenceExpression, target: PsiElement, ctx: GoRuleContext) {
        if (GoVetPsi.key(target) !in DIAL_KEYS) return
        val args = GoVetPsi.args(call)
        if (args.size < 2) return
        when (val address = args[1]) {
            is GoCallExpr -> if (args.size == 2) checkAddress(address, null, ctx)
            is GoReferenceExpression -> {
                if (address.expression != null) return
                val def = ctx.resolve(address).singleOrNull() as? GoVarDefinition ?: return
                if (def.containingFile != ctx.file) return
                val value = when (val parent = def.parent) {
                    is GoShortVarDeclaration -> parent.expressionList.singleOrNull()
                    is GoVarSpec -> parent.expressionList.singleOrNull()
                    else -> null
                } ?: return
                checkAddress(value, call, ctx)
            }
            else -> {}
        }
    }

    private fun checkAddress(e: GoExpression, dial: GoCallExpr?, ctx: GoRuleContext) {
        val call = e as? GoCallExpr ?: return
        val args = GoVetPsi.args(call)
        if (args.size != 3 || GoVetPsi.hasEllipsis(call)) return
        val ref = GoLintPsi.calleeReference(call) ?: return
        if (ref.identifier.text != "Sprintf" || GoVetPsi.key(ctx.resolve(ref).singleOrNull() ?: return) != "fmt.Sprintf") return
        val format = GoStaticcheckPsi.stringConstant(args[0], ctx) ?: return
        if (format != "%s:%d" && format != "%s:%s") return
        val suffix = dial?.let { " (passed to net.Dial at L${lineOf(it)})" } ?: ""
        ctx.report(args[0], "address format ${GoStaticcheckPsi.quote(format)} does not work with IPv6$suffix", JoinHostPortFix(netQualifier(ctx.file)))
    }

    private fun lineOf(e: PsiElement): Int {
        val document = e.containingFile.viewProvider.document ?: return 0
        return document.getLineNumber(e.textRange.startOffset) + 1
    }

    /** `net.`, the alias of `net` in [file], or nothing for a dot import. */
    private fun netQualifier(file: GoFile): String {
        val spec = file.imports.firstOrNull { it.path == "net" && !it.isBlank } ?: return "net."
        return when (val alias = spec.alias) {
            null -> "net."
            "." -> ""
            else -> "$alias."
        }
    }

    /** The fix of vet: `fmt.Sprintf("%s:%d", host, port)` -> `net.JoinHostPort(host, "port")` (`fmt.Sprintf("%d", port)` for a non-literal port). */
    private class JoinHostPortFix(private val net: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Replace fmt.Sprintf with net.JoinHostPort"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val format = descriptor.psiElement as? GoExpression ?: return
            val call = PsiTreeUtil.getParentOfType(format, GoCallExpr::class.java) ?: return
            val args = GoVetPsi.args(call)
            if (args.size != 3 || args[0] !== format) return
            val numeric = "%d" in format.text
            val file = call.containingFile
            val document = GoImportEdits.document(file) ?: return
            val sprintf = call.expression.text
            val port = args[2]
            if (numeric) {
                val literal = (port as? GoLiteral)?.takeIf { it.int != null }?.let { GoConstant.parseInt(it.text)?.value }
                val text = if (literal != null && literal.signum() >= 0) "\"$literal\"" else "$sprintf(\"%d\", ${port.text})"
                document.replaceString(port.textRange.startOffset, port.textRange.endOffset, text)
            }
            document.deleteString(format.textRange.startOffset, args[1].textRange.startOffset)
            document.replaceString(call.expression.textRange.startOffset, call.expression.textRange.endOffset, "${net}JoinHostPort")
            GoImportEdits.commit(file, document)
        }
    }

    private companion object {
        val DIALS = setOf("Dial", "DialTimeout")
        val DIAL_KEYS = setOf("net.Dial", "net.DialTimeout", "net.Dialer.Dial")
    }
}

/** govet `httpmux`: a Go 1.22 `ServeMux` pattern (method, wildcard) registered in a module whose `go` version is 1.21 or older. */
class GoVetHttpMuxRule : GoVetCallRule() {
    override val id: String get() = "govet:httpmux"
    override val title: String get() = "Enhanced ServeMux pattern with Go before 1.22"
    override val description: String get() =
        "govet <code>httpmux</code>: patterns such as <code>\"GET /items/{id}\"</code> are understood by <code>net/http.ServeMux</code> only " +
            "from Go 1.22. In a module whose <code>go</code> directive is 1.21 or older they register as plain paths and never match as intended."
    // not in cmd/vet, so not in golangci's govet defaults either
    override val enabledWithLinter: Boolean get() = false
    override val calleeNames: Set<String> get() = HANDLE

    override fun checkCallee(call: GoCallExpr, callee: GoReferenceExpression, target: PsiElement, ctx: GoRuleContext) {
        if (GoVetPsi.key(target) !in KEYS) return
        if (!before122(GoVetPsi.moduleGoVersion(ctx.file) ?: return)) return
        if (ctx.packageFiles.none { f -> f.imports.any { it.path == "net/http" } }) return
        val pattern = GoVetPsi.args(call).firstOrNull() ?: return
        val text = GoStaticcheckPsi.stringConstant(pattern, ctx) ?: return
        if (!likelyEnhanced(text)) return
        ctx.report(pattern, "possible enhanced ServeMux pattern used with Go version before 1.22 (update go.mod file?)")
    }

    /** vet's `!goVersionAfter121`: semver of the version at most `v1.21` (`1.21`, `1.21.0`, `1.21rc1`, any 1.20 ...; not `1.21.1`). */
    private fun before122(version: String): Boolean {
        val m = VERSION.matchEntire(version) ?: return false
        val minor = m.groupValues[1].toInt()
        val patch = m.groupValues[2].removePrefix(".")
        return minor < 21 || minor == 21 && (patch.isEmpty() || patch.toInt() == 0)
    }

    private fun likelyEnhanced(pattern: String): Boolean = ' ' in pattern || WILDCARD.containsMatchIn(pattern)

    private companion object {
        val HANDLE = setOf("Handle", "HandleFunc")
        val KEYS = setOf("net/http.Handle", "net/http.HandleFunc", "net/http.ServeMux.Handle", "net/http.ServeMux.HandleFunc")
        val VERSION = Regex("""go1\.(\d+)(\.\d+)?((beta|rc)\d+)?""")
        val WILDCARD = Regex("""/\{[_\p{L}][_\p{L}\p{Nd}]*(\.\.\.)?}""")
    }
}

/** govet `slog`: mismatched key-value pairs in the variadic arguments of `log/slog` calls. */
class GoVetSlogRule : GoVetCallRule() {
    override val id: String get() = "govet:slog"
    override val title: String get() = "Mismatched slog key-value pairs"
    override val description: String get() =
        "govet <code>slog</code>: the variadic arguments of <code>slog.Info</code>, <code>Logger.With</code>, <code>Record.Add</code> … " +
            "alternate a string key and a value, or are <code>slog.Attr</code>s. A missing key or value shifts every pair after it."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: GoReferenceExpression, target: PsiElement, ctx: GoRuleContext) {
        if (GoVetPsi.hasEllipsis(call)) return
        val name = callee.identifier.text
        val receiver = when (target) {
            is GoMethodDeclaration -> target.receiverTypeName ?: return
            is GoFunctionDeclaration -> ""
            else -> return
        }
        if (GoAnalysisPsi.packagePath(target) != "log/slog") return
        var skip = FUNCS[receiver]?.get(name) ?: return
        if (isMethodExpression(callee, ctx)) skip++
        val args = GoVetPsi.args(call)
        if (args.size <= skip) return
        val short = if (receiver.isEmpty()) "slog.$name" else "slog.$receiver.$name"
        val attr = attrType(target, ctx)
        var pos = KEY
        var unknown: GoExpression? = null
        for (arg in args.drop(skip)) {
            val t = ctx.typeOf(arg)
            when (pos) {
                KEY -> when {
                    isString(t) -> pos = VALUE
                    isAttr(t) -> pos = KEY
                    t is GoUnknownType -> return
                    else -> {
                        val iface = t !is GoTypeParamType && t.underlying() is GoInterfaceType
                        if (iface && GoTypePredicates.assignable(GoBasicType.STRING, t)) { pos = UNKNOWN; unknown = arg; continue }
                        if (iface && attr != null && GoTypePredicates.assignable(attr, t)) { pos = KEY; continue }
                        val text = GoStaticcheckPsi.quote(GoExpressionPsi.render(arg))
                        val previous = unknown
                        if (previous == null) ctx.report(arg, "$short arg $text should be a string or a slog.Attr (possible missing key or value)")
                        else ctx.report(arg, "$short arg $text should probably be a string or a slog.Attr (previous arg ${GoStaticcheckPsi.quote(GoExpressionPsi.render(previous))} cannot be a key)")
                        return
                    }
                }
                VALUE -> pos = KEY
                UNKNOWN -> {
                    unknown = arg
                    if (!isString(t) && !isAttr(t) && !(t is GoTypeParamType || t.underlying() is GoInterfaceType)) pos = KEY
                }
            }
        }
        if (pos == VALUE) {
            if (unknown == null) ctx.report(call, "call to $short missing a final value") else ctx.report(call, "call to $short has a missing or misplaced value")
        }
    }

    private fun isString(t: GoType): Boolean = t is GoBasicType && (t.kind == GoBasicKind.STRING || t.kind == GoBasicKind.UNTYPED_STRING)

    private fun isAttr(t: GoType): Boolean = GoVetPsi.isNamed(t, "log/slog", "Attr")

    /** `slog.Attr` as a type, from the package the callee is declared in. */
    private fun attrType(target: PsiElement, ctx: GoRuleContext): GoType? {
        val file = target.containingFile as? GoFile ?: return null
        val spec = GoPackageModel.getInstance(ctx.project).scopeOf(file).lookupType("Attr") ?: return null
        return ctx.semantic.declarationType(spec).takeIf { it is GoNamedType }
    }

    /** `(*slog.Logger).Info(l, …)` / `slog.Logger.Info`: the receiver is the first argument. */
    private fun isMethodExpression(callee: GoReferenceExpression, ctx: GoRuleContext): Boolean {
        val q = callee.expression ?: return false
        if (q is GoParenthesesExpr) return true
        val ref = q as? GoReferenceExpression ?: return false
        return ctx.resolve(ref).singleOrNull() is GoTypeSpec
    }

    private companion object {
        const val KEY = 0
        const val VALUE = 1
        const val UNKNOWN = 2
        private val LEVELS = mapOf("Debug" to 1, "Info" to 1, "Warn" to 1, "Error" to 1, "DebugContext" to 2, "InfoContext" to 2, "WarnContext" to 2, "ErrorContext" to 2, "Log" to 3)
        val FUNCS: Map<String, Map<String, Int>> = mapOf(
            "" to LEVELS + ("Group" to 1),
            "Logger" to LEVELS + ("With" to 0),
            "Record" to mapOf("Add" to 0),
        )
        val NAMES: Set<String> = FUNCS.values.flatMap { it.keys }.toSet()
    }
}
