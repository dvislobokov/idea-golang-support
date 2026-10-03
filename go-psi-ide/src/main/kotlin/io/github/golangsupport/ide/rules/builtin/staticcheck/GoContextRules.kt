package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * staticcheck SA1012: `nil` passed as the first argument where a `context.Context` is expected (any function or method, its
 * signature decides). Fixes: `context.TODO()`, `context.Background()`.
 */
class GoNilContextRule : GoCallRule() {
    override val id: String get() = "SA1012"
    override val linter: String get() = "staticcheck"
    override val title: String get() = "nil context.Context passed"
    override val description: String get() =
        "<code>nil</code> passed as a <code>context.Context</code>: pass <code>context.TODO()</code> when unsure which context to use."
    override val needs: Set<GoRuleNeed> get() = TYPES

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val first = GoStaticcheckPsi.arguments(call)?.firstOrNull() ?: return
        val nil = GoLintPsi.unparen(first) as? GoReferenceExpression ?: return
        if (nil.expression != null || nil.identifier?.text != "nil") return
        if ((ctx.typeOf(first) as? GoBasicType)?.kind != GoBasicKind.UNTYPED_NIL) return
        val signature = ctx.semantic.calleeSignature(call) ?: return
        val param = signature.params.firstOrNull() ?: return
        if (!GoAnalysisPsi.isNamed(param.type, "context", "Context")) return
        ctx.report(first, "do not pass a nil Context, even if a function permits it; pass context.TODO if you are unsure about which Context to use",
            GoUseContextFix("TODO"), GoUseContextFix("Background"))
    }

    private companion object {
        val TYPES = setOf(GoRuleNeed.TYPES)
    }
}

/** staticcheck SA1029: a built-in type (`string`, `int`, ...) or a non-comparable type as the key of `context.WithValue`. */
class GoContextKeyTypeRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1029"
    override val title: String get() = "Built-in type as context.WithValue key"
    override val description: String get() =
        "The key of <code>context.WithValue</code> has a built-in type: packages using the same key collide. Define an unexported key type: " +
            "<code>type userKey struct{}</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "context.WithValue") return
        val key = arguments.getOrNull(1) ?: return
        val raw = ctx.typeOf(key)
        if (raw === GoUnknownType || raw is GoBasicType && raw.kind == GoBasicKind.UNTYPED_NIL) return
        val type = GoTypePredicates.defaultType(raw)
        if (type is GoBasicType) {
            if (type.kind == GoBasicKind.INVALID) return
            ctx.report(key, "should not use built-in type ${type.name} as key for value; define your own type to avoid collisions")
            return
        }
        if (type !is GoTypeParamType && GoTypePredicates.isKnown(type) && !GoTypePredicates.comparable(type)) {
            ctx.report(key, "keys used with context.WithValue must be comparable, but type ${ctx.semantic.render(type, true)} is not comparable")
        }
    }

    private companion object {
        val NAMES = setOf("WithValue")
    }
}
