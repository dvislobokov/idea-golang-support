package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType

/** staticcheck SA6002: `pool.Put(v)` with a value that is not pointer-like (or is a slice): converting it to `any` allocates. */
class GoPoolPutRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA6002"
    override val title: String get() = "Storing non-pointer values in sync.Pool"
    override val description: String get() =
        "<code>sync.Pool.Put</code> takes an <code>any</code>: a struct, a number or a slice header is copied to the heap on every call, which is " +
            "what the pool was meant to avoid. Put pointers (<code>&amp;buf</code>, <code>*[]byte</code>)."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "sync.Pool.Put") return
        val argument = arguments.singleOrNull() ?: return
        val type = GoB3Psi.defaultType(ctx.typeOf(argument))
        if (type is GoTypeParamType) {
            if (maybePointerLike(type.bound, 0) && type.bound.underlying() !is GoSliceType) return
        } else {
            if (!GoB3Psi.isKnown(type)) return
            if (maybePointerLike(type, 0) && type.underlying() !is GoSliceType) return
        }
        ctx.report(argument, "argument should be pointer-like to avoid allocations")
    }

    /** `typeutil.MaybePointerLike`. */
    private fun maybePointerLike(type: GoType, depth: Int): Boolean {
        if (depth > 8) return true
        return when (val u = type.underlying()) {
            is GoInterfaceType -> {
                val terms = u.typeTerms ?: return true
                terms.isEmpty() || terms.any { maybePointerLike(it.type, depth + 1) }
            }
            is GoChanType, is GoMapType, is GoSignatureType, is GoPointerType, is GoSliceType -> true
            is GoBasicType -> u.kind == GoBasicKind.UNSAFE_POINTER
            else -> type === io.github.golangsupport.semantic.types.GoUnknownType
        }
    }

    private companion object {
        val NAMES = setOf("Put")
    }
}

/** staticcheck SA9002: a file mode written like an octal number without the leading 0 (`644` is 0o1204). Fix: add the 0. */
class GoOctalFileModeRule : GoCallRule() {
    override val id: String get() = "SA9002"
    override val linter: String get() = "staticcheck"
    override val title: String get() = "Non-octal os.FileMode that looks like it was meant to be in octal"
    override val description: String get() =
        "A three-digit decimal literal like <code>644</code> passed as an <code>os.FileMode</code> is 0o1204, not <code>rw-r--r--</code>: write <code>0644</code>."
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val args = call.argumentList?.expressions ?: return
        for (arg in args) {
            if (arg !is GoLiteral || arg.int == null) continue
            val text = arg.text
            if (text.length != 3 || text[0] !in '1'..'7' || text[1] !in '0'..'7' || text[2] !in '0'..'7') continue
            val type = ctx.semantic.expectedTypeAt(arg) ?: continue
            if (!(type is GoNamedType && type.name == "FileMode" && (type.pkgPath == "io/fs" || type.pkgPath == "os"))) continue
            val value = text.toInt()
            ctx.report(arg, "file mode '$text' evaluates to 0${Integer.toOctalString(value)}; did you mean '0$text'?", GoReplaceWithTextFix("Fix octal literal", "0$text"))
        }
    }
}

/** staticcheck SA9007: `os.RemoveAll(os.TempDir())` (or of the user's cache, config or home directory) deletes all of it. */
class GoRemoveUserDirRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA9007"
    override val title: String get() = "Deleting a directory that shouldn't be deleted"
    override val description: String get() =
        "<code>os.RemoveAll(os.TempDir())</code>, or of <code>os.UserCacheDir()</code>, <code>UserConfigDir()</code>, <code>UserHomeDir()</code>, " +
            "deletes the whole directory, not a subdirectory made for the program: join a name onto it first."
    override val calleeNames: Set<String> get() = NAMES
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES_FLOW

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "os.RemoveAll") return
        val origin = GoB3Psi.origin(arguments.singleOrNull() ?: return, ctx)
        val source = origin.expression as? GoCallExpr ?: return
        val ref = GoLintPsi.calleeReference(source) ?: return
        val kind = when (GoStaticcheckPsi.calleeKey(ref, ctx)) {
            "os.TempDir" -> if (origin.resultIndex < 0) "temporary" else null
            "os.UserCacheDir" -> if (origin.resultIndex == 0) "cache" else null
            "os.UserConfigDir" -> if (origin.resultIndex == 0) "config" else null
            "os.UserHomeDir" -> if (origin.resultIndex == 0) "home" else null
            else -> null
        } ?: return
        ctx.report(call, "this call to os.RemoveAll deletes the user's entire $kind directory, not a subdirectory therein")
    }

    private companion object {
        val NAMES = setOf("RemoveAll")
    }
}

/** staticcheck SA4027: `u.Query().Set(…)` (`Add`, `Del`) on a `*url.URL`: `Query` returns a parsed copy, the URL does not change. */
class GoUrlQueryCopyRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA4027"
    override val title: String get() = "Modifying the copy returned by url.URL.Query"
    override val description: String get() =
        "<code>(*url.URL).Query</code> parses <code>RawQuery</code> into a new <code>url.Values</code>: changing it does not change the URL. " +
            "Keep the values, modify them and assign <code>u.RawQuery = q.Encode()</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "net/url.Values.Add" && callee != "net/url.Values.Set" && callee != "net/url.Values.Del") return
        val query = GoLintPsi.unparen(GoLintPsi.calleeReference(call)?.expression) as? GoCallExpr ?: return
        if (query.argumentList?.expressions?.isNotEmpty() != false) return
        val queryRef = GoLintPsi.calleeReference(query) ?: return
        if (queryRef.identifier?.text != "Query") return
        val receiver = queryRef.expression ?: return
        val type = ctx.typeOf(receiver)
        if (type !is GoPointerType || !GoAnalysisPsi.isNamed(type.elem, "net/url", "URL")) return
        ctx.report(call, "(*net/url.URL).Query returns a copy, modifying it doesn't change the URL")
    }

    private companion object {
        val NAMES = setOf("Add", "Set", "Del")
    }
}

/** staticcheck SA4030: `rand.Intn(1)` (and the other bounded functions of `math/rand`, `math/rand/v2`): the result is always 0. */
class GoRandIntnOneRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA4030"
    override val title: String get() = "Ineffective attempt at generating random number"
    override val description: String get() =
        "<code>rand.Intn(n)</code> returns a value in [0, n): <code>rand.Intn(1)</code> is always 0. Probably <code>rand.Intn(2)</code> was meant."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        val name = displayName(callee) ?: return
        val literal = arguments.singleOrNull() as? GoLiteral ?: return
        if (literal.int == null || literal.text != "1") return
        ctx.report(call, "$name(n) generates a random value 0 <= x < n; that is, the generated values don't include n; ${call.text} therefore always returns 0")
    }

    /** `math/rand.Intn`, `(*math/rand.Rand).Intn`; null for other callees. */
    private fun displayName(callee: String): String? {
        for (pkg in listOf("math/rand/v2", "math/rand")) {
            if (!callee.startsWith("$pkg.")) continue
            val rest = callee.removePrefix("$pkg.")
            val functions = if (pkg == "math/rand") V1 else V2
            if (rest in functions) return callee
            if (rest.startsWith("Rand.") && rest.removePrefix("Rand.") in functions - "N") return "(*$pkg.Rand).${rest.removePrefix("Rand.")}"
            return null
        }
        return null
    }

    private companion object {
        val V1 = setOf("Int31n", "Int63n", "Intn")
        val V2 = setOf("Int32N", "Int64N", "IntN", "N", "Uint32N", "Uint64N", "UintN")
        val NAMES = V1 + V2
    }
}

/** staticcheck SA4015: `math.Ceil(float64(i))` (`Floor`, `Trunc`, `IsNaN`, `IsInf`) of an integer: the result is known. */
class GoIntegerMathRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA4015"
    override val title: String get() = "Calling functions like math.Ceil on floats converted from integers"
    override val description: String get() =
        "An integer converted to <code>float64</code> is already whole: <code>math.Ceil</code>, <code>Floor</code> and <code>Trunc</code> return it " +
            "unchanged, <code>IsNaN</code> and <code>IsInf</code> are false. Perhaps the division before it was meant to be in floating point."
    override val calleeNames: Set<String> get() = NAMES
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES_FLOW

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee !in CALLEES) return
        val argument = arguments.firstOrNull() ?: return
        val origin = GoB3Psi.origin(argument, ctx)
        if (origin.resultIndex >= 0) return
        val conversion = origin.expression as? GoCallExpr ?: return
        val operand = GoB3Psi.conversionOperand(conversion, ctx) ?: return
        if (!isInteger(ctx.typeOf(operand))) return
        val name = callee.substringAfter('.')
        val direct = GoLintPsi.unparen(argument) === conversion && arguments.size == 1
        val fixes = if (direct && name in WHOLE) arrayOf(GoReplaceWithTextFix("Remove the call to math.$name", argument.text)) else emptyArray()
        ctx.report(call, "calling $callee on a converted integer is pointless", *fixes)
    }

    private fun isInteger(type: GoType): Boolean {
        if (type is GoTypeParamType) {
            val terms = type.terms ?: return false
            return terms.isNotEmpty() && terms.all { (it.type.underlying() as? GoBasicType)?.kind?.isInteger == true }
        }
        val u = type.underlying() as? GoBasicType ?: return false
        return u.kind.isInteger && !u.isUntyped
    }

    private companion object {
        val CALLEES = setOf("math.Ceil", "math.Floor", "math.IsNaN", "math.Trunc", "math.IsInf")
        val NAMES = setOf("Ceil", "Floor", "IsNaN", "Trunc", "IsInf")
        val WHOLE = setOf("Ceil", "Floor", "Trunc")
    }
}
