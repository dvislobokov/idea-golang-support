package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPointerType
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.impl.GoDocComments
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType
import io.github.golangsupport.semantic.types.GoPointerType as PointerType

/**
 * govet `tests`: in `_test.go` files, malformed `Test` / `Benchmark` / `Fuzz` names (`Testfoo`), test functions with type parameters,
 * `Example` functions with parameters, results, unknown identifiers or malformed suffixes, misplaced `// Output:` comments, and fuzz
 * targets of the wrong shape (`f.Fuzz` arguments, `f.Add` values).
 */
class GoVetTestsRule : GoVetFileRule() {
    override val id: String get() = "govet:tests"
    override val title: String get() = "Malformed test, benchmark, fuzz test or example"
    override val description: String get() =
        "govet <code>tests</code>: <code>func Testfoo(t *testing.T)</code> is not run by <code>go test</code> (the letter after <code>Test</code> " +
            "must not be lowercase); an <code>Example</code> must be niladic and name an existing identifier and method; the <code>// Output:</code> " +
            "comment must be the last one; a fuzz target takes <code>*testing.T</code> and arguments of the supported types, and <code>f.Add</code> " +
            "values must match them."

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        if (!file.name.endsWith("_test.go")) return
        for (fn in file.functions) {
            val name = fn.name ?: continue
            when {
                name.startsWith("Example") -> {
                    checkExampleName(fn, name, ctx)
                    checkExampleOutput(fn, ctx)
                }
                name.startsWith("Test") -> checkTest(fn, name, "Test", ctx)
                name.startsWith("Benchmark") -> checkTest(fn, name, "Benchmark", ctx)
                name.startsWith("Fuzz") -> {
                    checkTest(fn, name, "Fuzz", ctx)
                    checkFuzz(fn, ctx)
                }
            }
        }
    }

    // ---- Test / Benchmark / Fuzz names

    private fun checkTest(fn: GoFunctionDeclaration, name: String, prefix: String, ctx: GoRuleContext) {
        val sig = fn.signature ?: return
        if (hasResults(fn)) return
        val params = sig.parameters.parameterDeclarationList
        if (params.size != 1 || params[0].paramDefinitionList.size > 1) return
        val star = params[0].type as? GoPointerType ?: return
        if (star.type?.typeReferenceExpression?.identifier?.text != prefix.substring(0, 1)) return
        fn.typeParameters?.let { tp ->
            if (tp.typeParameterDeclarationList.isNotEmpty()) {
                ctx.report(tp, "$name has type parameters: it will not be run by go test as a ${prefix}XXX function")
            }
        }
        if (!isTestSuffix(name.substring(prefix.length))) {
            ctx.report(fn.identifier ?: return, "$name has malformed name: first letter after '$prefix' must not be lowercase")
        }
    }

    private fun hasResults(fn: GoFunctionDeclaration): Boolean {
        val result = fn.signature?.result ?: return false
        return result.type != null || result.parameters?.parameterDeclarationList?.isNotEmpty() == true
    }

    private fun isTestSuffix(s: String): Boolean = s.isEmpty() || !Character.isLowerCase(s.codePointAt(0))

    private fun isExampleSuffix(s: String): Boolean = s.isNotEmpty() && Character.isLowerCase(s.codePointAt(0))

    // ---- examples

    private fun checkExampleName(fn: GoFunctionDeclaration, name: String, ctx: GoRuleContext) {
        val anchor = fn.identifier ?: return
        if (fn.signature?.parameters?.parameterDeclarationList?.isNotEmpty() == true) ctx.report(anchor, "$name should be niladic")
        if (hasResults(fn)) ctx.report(anchor, "$name should return nothing")
        if (fn.typeParameters?.typeParameterDeclarationList?.isNotEmpty() == true) ctx.report(anchor, "$name should not have type params")
        if (name == "Example") return
        val exName = name.removePrefix("Example")
        val elems = exName.split("_", limit = 3)
        val ident = elems[0]
        val objs = if (ident.isEmpty()) emptyList() else lookup(ident, ctx)
        if (ident.isNotEmpty() && objs.isEmpty()) {
            ctx.report(anchor, "$name refers to unknown identifier: $ident")
            return
        }
        if (elems.size < 2) return
        if (ident.isEmpty()) {
            val residual = exName.removePrefix("_")
            if (!isExampleSuffix(residual)) ctx.report(anchor, "$name has malformed example suffix: $residual")
            return
        }
        val member = elems[1]
        if (!isExampleSuffix(member) && objs.none { hasMember(it, member, ctx) }) {
            ctx.report(anchor, "$name refers to unknown field or method: $ident.$member")
        }
        if (elems.size == 3 && !isExampleSuffix(elems[2])) ctx.report(anchor, "$name has malformed example suffix: ${elems[2]}")
    }

    /** vet's `lookup`: the package scope, else the scopes of every package the package imports. */
    private fun lookup(name: String, ctx: GoRuleContext): List<GoNamedElement> {
        val model = GoPackageModel.getInstance(ctx.project)
        model.scopeOf(ctx.file).lookup(name).takeIf { it.isNotEmpty() }?.let { return it }
        val out = ArrayList<GoNamedElement>()
        val paths = LinkedHashSet<String>()
        for (f in ctx.packageFiles) for (spec in f.imports) if (spec.path != "C") paths += spec.path
        for (path in paths) {
            val pkg = model.resolveImport(path, ctx.file) ?: continue
            out += model.scopeOf(pkg).lookup(name)
        }
        return out
    }

    private fun hasMember(obj: GoNamedElement, member: String, ctx: GoRuleContext): Boolean {
        val type = ctx.semantic.declarationType(obj)
        if (type is GoUnknownType) return true
        return ctx.semantic.lookupFieldOrMethod(type, member) != null || type !is PointerType && ctx.semantic.lookupFieldOrMethod(PointerType(type), member) != null
    }

    private fun checkExampleOutput(fn: GoFunctionDeclaration, ctx: GoRuleContext) {
        val text = ctx.file.viewProvider.contents
        val start = (fn.func ?: return).textRange.startOffset
        val comments = PsiTreeUtil.findChildrenOfType(fn, PsiComment::class.java).filter { it.textRange.startOffset >= start }.sortedBy { it.textRange.startOffset }
        val groups = ArrayList<MutableList<PsiComment>>()
        var trailing = false
        for (c in comments) {
            val last = groups.lastOrNull()?.last()
            val lines = if (last == null) -1 else GoVetPsi.newlines(text, last.textRange.endOffset, c.textRange.startOffset)
            val code = last != null && text.subSequence(last.textRange.endOffset, c.textRange.startOffset).any { !it.isWhitespace() }
            if (last != null && !code && (lines == 0 || lines == 1 && !trailing)) {
                groups.last() += c
            } else {
                groups += mutableListOf(c)
                trailing = codeBefore(text, c.textRange.startOffset)
            }
        }
        val outputs = groups.map { g -> OUTPUT.containsMatchIn(GoDocComments.text(g.map { it.text }) ?: "") }
        val message = if (outputs.count { it } > 1) "there can only be one output comment block per example" else "output comment block must be the last comment block"
        for ((i, g) in groups.withIndex()) {
            if (outputs[i] && i != groups.lastIndex) ctx.report(g.first(), message)
        }
    }

    /** Whether code precedes [offset] on its line (a trailing comment). */
    private fun codeBefore(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i] != '\n') {
            if (!text[i].isWhitespace()) return true
            i--
        }
        return false
    }

    // ---- fuzz targets

    private fun checkFuzz(fn: GoFunctionDeclaration, ctx: GoRuleContext) {
        val body = fn.block ?: return
        val calls = PsiTreeUtil.findChildrenOfType(body, GoCallExpr::class.java).toList()
        var params: List<GoType>? = null
        val targets = ArrayList<PsiElement>()
        for (call in calls) {
            if (targets.any { PsiTreeUtil.isAncestor(it, call, true) }) continue // inside a checked fuzz call
            if (!isFuzzTargetDot(call, "Fuzz", ctx)) continue
            val args = GoVetPsi.args(call)
            if (args.size != 1) continue
            targets += call
            val expr = args[0]
            val type = ctx.typeOf(expr)
            if (type is GoUnknownType) continue
            val sig = type.underlying() as? GoSignatureType
            if (sig == null) {
                ctx.report(expr, "argument to Fuzz must be a function")
                continue
            }
            if (sig.results.isNotEmpty()) ctx.report(expr, "fuzz target must not return any value")
            if (sig.params.isEmpty()) {
                ctx.report(expr, "fuzz target must have 1 or more argument")
                continue
            }
            val ok = validateFuzzArgs(sig, expr, ctx)
            if (ok && params == null) params = sig.params.map { it.type }
            for (inner in PsiTreeUtil.findChildrenOfType(expr, GoCallExpr::class.java)) {
                if (!isFuzzTargetDot(inner, "", ctx)) continue
                if (!isFuzzTargetDot(inner, "Name", ctx) && !isFuzzTargetDot(inner, "Failed", ctx)) ctx.report(inner, "fuzz target must not call any *F methods")
            }
        }
        val expected = params ?: return
        for (call in calls) {
            if (isFuzzTargetDot(call, "Add", ctx)) checkAdd(call, expected, ctx)
        }
    }

    private fun checkAdd(call: GoCallExpr, params: List<GoType>, ctx: GoRuleContext) {
        val args = GoVetPsi.args(call)
        if (args.size != params.size - 1) {
            ctx.report(call, "wrong number of values in call to (*testing.F).Add: ${args.size}, fuzz target expects ${params.size - 1}")
            return
        }
        val types = args.map { GoVetPsi.defaultType(ctx.typeOf(it)) }
        if (types.any { it is GoUnknownType }) return
        val mismatched = types.indices.filter { !GoTypePredicates.identical(types[it], params[it + 1]) }
        if (mismatched.size == 1) {
            val i = mismatched[0]
            ctx.report(args[i], "mismatched type in call to (*testing.F).Add: ${GoVetPsi.pathTypeString(types[i])}, fuzz target expects ${GoVetPsi.pathTypeString(params[i + 1])}")
        } else if (mismatched.size > 1) {
            val got = types.joinToString(" ", "[", "]") { GoVetPsi.pathTypeString(it) }
            val want = params.drop(1).joinToString(" ", "[", "]") { GoVetPsi.pathTypeString(it) }
            ctx.report(call, "mismatched types in call to (*testing.F).Add: $got, fuzz target expects $want")
        }
    }

    private fun validateFuzzArgs(sig: GoSignatureType, expr: GoExpression, ctx: GoRuleContext): Boolean {
        val lit = expr as? GoFunctionLit
        val decls = lit?.signature?.parameters?.parameterDeclarationList.orEmpty()
        /** The type node of the [i]th parameter of a function literal, else the whole argument. */
        fun range(i: Int): PsiElement {
            if (lit == null) return expr
            var seen = 0
            for (d in decls) {
                seen += maxOf(1, d.paramDefinitionList.size)
                if (i < seen) return d.type ?: expr
            }
            return expr
        }
        var ok = true
        if (!GoVetPsi.isPointerTo(sig.params[0].type, "testing", "T")) {
            ctx.report(range(0), "the first parameter of a fuzz target must be *testing.T")
            ok = false
        }
        for (i in 1 until sig.params.size) {
            if (!isAcceptedFuzzType(sig.params[i].type)) {
                ctx.report(range(i), "fuzzing arguments can only have the following types: $ACCEPTED_TEXT")
                ok = false
            }
        }
        return ok
    }

    private fun isAcceptedFuzzType(t: GoType): Boolean = when (t) {
        is GoBasicType -> t.kind in ACCEPTED_KINDS
        is GoSliceType -> (t.elem as? GoBasicType)?.kind == GoBasicKind.UINT8
        else -> false
    }

    /** `x.name(…)` with `x` of type `*testing.F`; any method when [name] is empty. */
    private fun isFuzzTargetDot(call: GoCallExpr, name: String, ctx: GoRuleContext): Boolean {
        val sel = call.expression as? GoReferenceExpression ?: return false
        val x = sel.expression ?: return false
        if (!GoVetPsi.isPointerTo(ctx.typeOf(x), "testing", "F")) return false
        return name.isEmpty() || sel.identifier.text == name
    }

    private companion object {
        val OUTPUT = Regex("""^\s*(unordered )?output:""", RegexOption.IGNORE_CASE)
        val ACCEPTED_KINDS = setOf(
            GoBasicKind.STRING, GoBasicKind.BOOL, GoBasicKind.FLOAT32, GoBasicKind.FLOAT64, GoBasicKind.INT, GoBasicKind.INT8, GoBasicKind.INT16,
            GoBasicKind.INT32, GoBasicKind.INT64, GoBasicKind.UINT, GoBasicKind.UINT8, GoBasicKind.UINT16, GoBasicKind.UINT32, GoBasicKind.UINT64,
        )
        const val ACCEPTED_TEXT = "string, bool, float32, float64, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64, []byte"
    }
}
