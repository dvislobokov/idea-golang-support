package io.github.golangsupport

import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.builtin.GoBoolComparisonRule
import io.github.golangsupport.ide.rules.builtin.GoErrcheckRule
import io.github.golangsupport.ide.rules.builtin.GoFunctionResultLimitRule
import io.github.golangsupport.ide.rules.builtin.GoInterfaceBloatRule
import io.github.golangsupport.ide.rules.builtin.GoPackageCommentsRule
import io.github.golangsupport.lint.config.GolangciConfigs
import io.github.golangsupport.lint.config.GolangciRuleConfigSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `.golangci.yml` -> the rule engine's configuration: which native rules run, with which options and levels. */
class GolangciRuleConfigTest {
    private val rules = listOf(GoErrcheckRule(), GoBoolComparisonRule(), GoFunctionResultLimitRule(), GoInterfaceBloatRule(), GoPackageCommentsRule())

    private fun configOf(yaml: String) = GolangciRuleConfigSource.toRuleConfig(GolangciConfigs.parse(yaml.trimIndent(), GolangciConfigs.Format.YAML).configOrNull!!, rules)

    @Test fun standardSetWithReviveRules() {
        val config = configOf(
            """
            version: "2"
            linters:
              enable: [revive, interfacebloat]
              settings:
                interfacebloat:
                  max: 5
                revive:
                  rules:
                    - name: function-result-limit
                      severity: error
                      arguments: [2]
            """,
        )
        assertEquals(true, config.rules["errcheck"]!!.enabled)
        assertEquals(true, config.rules["S1002"]!!.enabled)
        assertEquals(true, config.rules["revive:function-result-limit"]!!.enabled)
        assertEquals(2, config.rules["revive:function-result-limit"]!!.options["max"])
        assertEquals(GoRuleLevel.ERROR, config.rules["revive:function-result-limit"]!!.level)
        // revive with its own rules list runs only those
        assertEquals(false, config.rules["revive:package-comments"]!!.enabled)
        assertEquals(true, config.rules["interfacebloat"]!!.enabled)
        assertEquals(5, config.linterOptions["interfacebloat"]!!["max"])
    }

    @Test fun lintersOffByConfig() {
        val config = configOf(
            """
            version: "2"
            linters:
              default: none
              enable: [errcheck]
            """,
        )
        assertEquals(true, config.rules["errcheck"]!!.enabled)
        assertEquals(false, config.rules["S1002"]!!.enabled)
        assertEquals(false, config.rules["interfacebloat"]!!.enabled)
        assertNull(config.rules["S1002"]!!.level)
    }

    @Test fun staticcheckChecksPickSingleRules() {
        val config = configOf(
            """
            version: "2"
            linters:
              settings:
                staticcheck:
                  checks: ["all", "-S1002"]
            """,
        )
        assertEquals(false, config.rules["S1002"]!!.enabled)
        assertEquals(true, config.rules["errcheck"]!!.enabled)
    }
}
