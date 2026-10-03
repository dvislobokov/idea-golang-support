package io.github.golangsupport.ide.rules.builtin

import io.github.golangsupport.ide.rules.GoFunctionRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleFunction
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleOption

/** revive `function-result-limit`: a function (declaration or literal) with more than `max` results (default 3). Opt-in, like in revive. */
class GoFunctionResultLimitRule : GoFunctionRule() {
    override val id: String get() = "revive:function-result-limit"
    override val linter: String get() = "revive"
    override val title: String get() = "Too many function results"
    override val description: String get() = "A function returning more than <code>max</code> values (default 3): return a struct instead."
    override val defaultLevel: GoRuleLevel get() = GoRuleLevel.WEAK_WARNING
    override val enabledByDefault: Boolean get() = false
    override val enabledWithLinter: Boolean get() = false
    override val options: List<GoRuleOption<*>> get() = OPTIONS

    override fun checkFunction(function: GoRuleFunction, ctx: GoRuleContext) {
        val result = function.signature?.result ?: return
        val declarations = result.parameters?.parameterDeclarationList
        val count = declarations?.sumOf { maxOf(1, it.paramDefinitionList.size) } ?: if (result.type != null) 1 else 0
        val max = ctx.option(MAX)
        if (count > max) ctx.report(result, "maximum number of return results per function exceeded; max $max but got $count")
    }

    companion object {
        val MAX: GoRuleOption<Int> = GoRuleOption.int("max", 3, "Maximum number of results")
        private val OPTIONS = listOf(MAX)
    }
}
