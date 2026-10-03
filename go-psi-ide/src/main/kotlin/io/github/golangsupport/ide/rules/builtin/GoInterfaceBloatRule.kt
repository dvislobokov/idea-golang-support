package io.github.golangsupport.ide.rules.builtin

import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleOption
import io.github.golangsupport.ide.rules.GoTypeSpecRule
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoTypeSpec

/** interfacebloat: a named interface with more than `max` entries (methods and embedded elements, as the linter counts; default 10). */
class GoInterfaceBloatRule : GoTypeSpecRule() {
    override val id: String get() = "interfacebloat"
    override val linter: String get() = "interfacebloat"
    override val title: String get() = "Interface with too many methods"
    override val description: String get() = "An interface with more than <code>max</code> methods (default 10): split it."
    override val defaultLevel: GoRuleLevel get() = GoRuleLevel.WEAK_WARNING
    override val options: List<GoRuleOption<*>> get() = OPTIONS

    override fun checkTypeSpec(spec: GoTypeSpec, ctx: GoRuleContext) {
        val iface = spec.type as? GoInterfaceType ?: return
        val count = iface.methodSpecList.size + iface.constraintElemList.size
        val max = ctx.option(MAX)
        if (count > max) ctx.report(spec.identifier, "the interface has more than $max methods: $count")
    }

    companion object {
        val MAX: GoRuleOption<Int> = GoRuleOption.int("max", 10, "Maximum number of methods")
        private val OPTIONS = listOf(MAX)
    }
}
