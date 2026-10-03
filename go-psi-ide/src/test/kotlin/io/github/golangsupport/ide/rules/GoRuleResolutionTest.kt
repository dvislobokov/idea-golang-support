package io.github.golangsupport.ide.rules

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.rules.builtin.GoPackageCommentsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic of the engine: the merge order of the layers, option parsing, the run variants of a snapshot, package doc detection. */
class GoRuleResolutionTest {

    private class Rule(
        override val id: String, override val linter: String, override val scope: GoRuleScope = GoRuleScope.CALL,
        override val enabledByDefault: Boolean = true, override val enabledWithLinter: Boolean = true,
        override val needs: Set<GoRuleNeed> = SYNTAX_ONLY, override val overlapsGopls: Boolean = false,
    ) : GoRule() {
        override val title: String get() = id
        override val options: List<GoRuleOption<*>> get() = listOf(MAX, NAMES)
        override fun check(element: PsiElement, ctx: GoRuleContext) {}
    }

    private companion object {
        val MAX = GoRuleOption.int("max", 3)
        val NAMES = GoRuleOption.stringList("names", listOf("a"))
    }

    @Test
    fun enabledLayers() {
        val rule = Rule("r", "lint")
        val optIn = Rule("o", "lint", enabledByDefault = false, enabledWithLinter = false)
        assertTrue(GoRuleSet.resolve(rule, GoRuleConfig.EMPTY, null) != null)
        assertNull(GoRuleSet.resolve(optIn, GoRuleConfig.EMPTY, null))
        // linter on: the rule's own choice for that case; linter off: off
        assertNull(GoRuleSet.resolve(optIn, GoRuleConfig(linters = mapOf("lint" to true)), null))
        assertNull(GoRuleSet.resolve(rule, GoRuleConfig(linters = mapOf("lint" to false)), null))
        // the rule entry beats the linter, the user beats both
        assertTrue(GoRuleSet.resolve(optIn, GoRuleConfig(linters = mapOf("lint" to false), rules = mapOf("o" to GoRuleOverride(enabled = true))), null) != null)
        assertNull(GoRuleSet.resolve(rule, GoRuleConfig(rules = mapOf("r" to GoRuleOverride(enabled = true))), GoRuleOverride(enabled = false)))
        // default: none / all
        assertNull(GoRuleSet.resolve(rule, GoRuleConfig(defaultEnabled = false), null))
        assertTrue(GoRuleSet.resolve(Rule("x", "lint", enabledByDefault = false), GoRuleConfig(defaultEnabled = true), null) != null)
    }

    @Test
    fun levelAndOptionLayers() {
        val rule = Rule("r", "lint")
        val config = GoRuleConfig(
            linterOptions = mapOf("lint" to mapOf("max" to 5, "names" to listOf("x", "y"))),
            rules = mapOf("r" to GoRuleOverride(level = GoRuleLevel.ERROR, options = mapOf("max" to "7"))),
        )
        val fromConfig = GoRuleSet.resolve(rule, config, null)!!
        assertEquals(GoRuleLevel.ERROR, fromConfig.level)
        assertEquals(7, fromConfig.options[MAX])
        assertEquals(listOf("x", "y"), fromConfig.options[NAMES])
        val fromUser = GoRuleSet.resolve(rule, config, GoRuleOverride(level = GoRuleLevel.INFO, options = mapOf("max" to "9", "names" to "p, q")))!!
        assertEquals(GoRuleLevel.INFO, fromUser.level)
        assertEquals(9, fromUser.options[MAX])
        assertEquals(listOf("p", "q"), fromUser.options[NAMES])
        val defaults = GoRuleSet.resolve(rule, GoRuleConfig.EMPTY, GoRuleOverride(options = mapOf("max" to "not a number")))!!
        assertEquals(GoRuleLevel.WARNING, defaults.level)
        assertEquals(3, defaults.options[MAX])
    }

    @Test
    fun levelNames() {
        assertEquals(GoRuleLevel.WEAK_WARNING, GoRuleLevel.parse("weak warning"))
        assertEquals(GoRuleLevel.WARNING, GoRuleLevel.parse("Warn"))
        assertEquals(GoRuleLevel.INFO, GoRuleLevel.parse("info"))
        assertNull(GoRuleLevel.parse("fatal"))
    }

    @Test
    fun runVariantsFilterOnce() {
        val syntax = Rule("s", "l")
        val typed = Rule("t", "l", needs = setOf(GoRuleNeed.TYPES))
        val gopls = Rule("g", "l", scope = GoRuleScope.EXPRESSION, overlapsGopls = true)
        val snapshot = GoRuleSnapshot(listOf(syntax, typed, gopls).map { GoActiveRule(it, GoRuleLevel.WARNING, GoRuleOptions.EMPTY) })
        fun ids(dumb: Boolean, goplsMode: Boolean) = snapshot.forRun(dumb, goplsMode).flatten().map { it.rule.id }.sorted()
        assertEquals(listOf("g", "s", "t"), ids(dumb = false, goplsMode = false))
        assertEquals(listOf("g", "s"), ids(dumb = true, goplsMode = false))
        assertEquals(listOf("s", "t"), ids(dumb = false, goplsMode = true))
        assertTrue(snapshot.forRun(false, false) === snapshot.forRun(false, false))
    }

    @Test
    fun packageDoc() {
        assertTrue(GoPackageCommentsRule.hasPackageDoc("// Package p does.\npackage p\n"))
        assertTrue(GoPackageCommentsRule.hasPackageDoc("/*\nPackage p.\n*/\npackage p\n"))
        assertFalse(GoPackageCommentsRule.hasPackageDoc("// Package p does.\n\npackage p\n"))
        assertFalse(GoPackageCommentsRule.hasPackageDoc("//go:build linux\npackage p\n"))
        assertFalse(GoPackageCommentsRule.hasPackageDoc("package p\n// Package p, too late.\n"))
    }
}
