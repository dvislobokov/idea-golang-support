package io.github.golangsupport

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.ide.inspections.GoUnusedLabelInspection
import io.github.golangsupport.ide.rules.GoRuleConfig
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleOverride
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.builtin.GoErrcheckRule
import io.github.golangsupport.ide.rules.builtin.GoFunctionResultLimitRule
import io.github.golangsupport.settings.GoCheckKind
import io.github.golangsupport.settings.GoCheckLevel
import io.github.golangsupport.settings.GoChecksStore
import io.github.golangsupport.settings.GoChecksText
import io.github.golangsupport.settings.GoLintersConfigurable

/** The Built-in table reads every inspection and rule of the plugin and writes to the project profile and to GoRuleSettings. */
class GoChecksStoreTest : BasePlatformTestCase() {
    private val limit = "revive:function-result-limit"

    override fun tearDown() {
        try {
            GoRuleSettings.getInstance(project).apply { reset(limit); reset(GoErrcheckRule.ID) }
        } finally {
            super.tearDown()
        }
    }

    private fun profile() = InspectionProjectProfileManager.getInstance(project).currentProfile

    /**
     * Into the profile of the light project itself: the platform's modifiable copy of it would not have the tools a test enables
     * (they are added to the test profile, not to its supplier). The production path commits the same writer through that copy.
     */
    private fun save(model: io.github.golangsupport.settings.GoChecksModel) = GoChecksStore.save(project, model) { it.write(profile()) }

    fun testEveryInspectionAndRuleOfThePluginIsARow() {
        val model = GoChecksStore.load(project)
        val inspections = model.checks.filter { it.kind == GoCheckKind.INSPECTION }.associateBy { it.id }
        assertTrue(inspections.keys.toString(), inspections.keys.containsAll(listOf("GoUnusedLabel", "GoLostCancel", "GoUncheckedError", "GoImportCycle", "GoModPaths", "GoRules")))
        assertTrue("no inspection of the platform", inspections.keys.all { it.startsWith("Go") })
        assertEquals("Go", inspections.getValue("GoUnusedLabel").group)
        assertEquals("Go · Data flow", inspections.getValue("GoLostCancel").group)
        assertEquals("Go · Lint", inspections.getValue("GoUncheckedError").group)
        assertEquals("Go · Project", inspections.getValue("GoImportCycle").group)
        assertEquals("Go modules", inspections.getValue("GoModPaths").group)
        assertEquals("Lint rules", inspections.getValue("GoRules").group)
        assertTrue("an analysis inspection is quiet with gopls", inspections.getValue("GoUnusedVariable").goplsQuiet)
        assertFalse("go.mod checks do not depend on gopls", inspections.getValue("GoModPaths").goplsQuiet)
        assertTrue(inspections.getValue("GoUnusedLabel").description().isNotBlank())

        val rules = model.checks.filter { it.kind == GoCheckKind.RULE }
        assertEquals(GoRuleSet.getInstance(project).allRules.map { it.id }.sorted(), rules.map { it.id }.sorted())
        val limitRow = rules.single { it.id == limit }
        assertEquals("Lint rules · revive", limitRow.group)
        assertEquals("GoRules", limitRow.master)
        assertFalse(limitRow.baseEnabled)
        assertEquals(listOf("max"), limitRow.options.map { it.name })
        assertEquals("3", limitRow.options.single().baseText)
        assertFalse(model.isModified)
    }

    fun testApplyWritesTheInspectionToTheProjectProfile() {
        // the profile of a light test has only the inspections a test enables
        myFixture.enableInspections(GoUnusedLabelInspection())
        val key = HighlightDisplayKey.find("GoUnusedLabel")!!
        val wasEnabled = profile().isToolEnabled(key, null)
        val model = GoChecksStore.load(project)
        model.setEnabled("GoUnusedLabel", !wasEnabled)
        model.setLevel("GoUnusedLabel", GoCheckLevel.ERROR)
        try {
            save(model)
            assertEquals(!wasEnabled, profile().isToolEnabled(key, null))
            assertEquals(HighlightDisplayLevel.ERROR, profile().getErrorLevel(key, null as PsiElement?))
            val again = GoChecksStore.load(project)
            assertEquals(GoCheckLevel.ERROR, again.state("GoUnusedLabel").level)
            assertFalse("what was written is what is read", again.isModified)
            again.reset("GoUnusedLabel")
            save(again)
            assertTrue(profile().isToolEnabled(key, null))
            assertEquals(HighlightDisplayLevel.WARNING, profile().getErrorLevel(key, null as PsiElement?))
        } finally {
            val restore = GoChecksStore.load(project)
            restore.setEnabled("GoUnusedLabel", wasEnabled)
            restore.setLevel("GoUnusedLabel", GoCheckLevel.DEFAULT)
            save(restore)
        }
    }

    fun testApplyWritesRulesToTheRuleSettingsAndOnlyWhatDiffersFromTheBase() {
        val settings = GoRuleSettings.getInstance(project)
        val model = GoChecksStore.load(project)
        model.setEnabled(limit, true)
        model.setOption(limit, "max", "5")
        model.setLevel(GoErrcheckRule.ID, GoCheckLevel.ERROR)
        GoChecksStore.save(project, model)
        val limitOverride = settings.override(limit)!!
        assertEquals(true, limitOverride.enabled)
        assertEquals(mapOf("max" to "5"), limitOverride.options)
        assertNull(limitOverride.level)
        val errcheck = settings.override(GoErrcheckRule.ID)!!
        assertNull("on by default: nothing stored about it", errcheck.enabled)
        assertEquals(GoRuleLevel.ERROR, errcheck.level)

        val again = GoChecksStore.load(project)
        assertEquals("5", again.optionText(limit, "max"))
        assertTrue(again.state(limit).enabled)
        assertFalse(again.isModified)
        again.resetGroup("Lint rules · revive")
        again.reset(GoErrcheckRule.ID)
        GoChecksStore.save(project, again)
        assertNull(settings.override(limit))
        assertNull(settings.override(GoErrcheckRule.ID))
    }

    fun testARuleConfiguredByGolangciIsMarkedSoAndTakesItsBaseFromThere() {
        val config = GoRuleConfig(rules = mapOf(GoFunctionResultLimitRule().id to GoRuleOverride(enabled = true, level = GoRuleLevel.ERROR, options = mapOf("max" to 2))))
        val check = GoChecksStore.ruleCheck(GoFunctionResultLimitRule(), config, "GoRules")
        assertTrue(check.configured)
        assertTrue(check.baseEnabled)
        assertEquals(GoCheckLevel.ERROR, check.baseLevel)
        assertEquals("2", check.options.single().baseText)
        val plain = GoChecksStore.ruleCheck(GoFunctionResultLimitRule(), null, "GoRules")
        assertFalse(plain.configured)
        assertFalse(plain.baseEnabled)
        assertEquals(GoCheckLevel.WEAK_WARNING, plain.baseLevel)
    }

    fun testTheSourceColumn() {
        val model = GoChecksStore.load(project)
        val unused = model.check("GoUnusedVariable")!!
        assertEquals(GoBundle.message("checks.source.gopls"), GoChecksText.source(model, unused))
        model.setEnabled("GoRules", false)
        assertEquals(GoBundle.message("checks.source.master"), GoChecksText.source(model, model.check(GoErrcheckRule.ID)!!))
        assertEquals(GoBundle.message("checks.level.defaultOf", GoCheckLevel.ERROR.label), GoChecksText.level(model.check("GoModPaths")!!, GoCheckLevel.DEFAULT))
    }

    fun testLevelMapping() {
        assertEquals(GoCheckLevel.ERROR, GoChecksStore.level(HighlightDisplayLevel.ERROR))
        assertEquals(GoCheckLevel.WARNING, GoChecksStore.level(HighlightDisplayLevel.WARNING))
        assertEquals(GoCheckLevel.WEAK_WARNING, GoChecksStore.level(HighlightDisplayLevel.WEAK_WARNING))
        assertEquals(GoCheckLevel.INFO, GoChecksStore.level(HighlightDisplayLevel.DO_NOT_SHOW))
        assertEquals(GoCheckLevel.INFO, GoChecksStore.level(HighlightSeverity.INFORMATION))
        for (level in GoCheckLevel.entries.filter { it != GoCheckLevel.DEFAULT }) {
            assertEquals(level, GoChecksStore.level(GoChecksStore.displayLevel(level)))
            assertEquals(level, GoChecksStore.level(GoChecksStore.ruleLevel(level)!!))
        }
        assertNull(GoChecksStore.ruleLevel(GoCheckLevel.DEFAULT))
    }

    /** The page as the dialog uses it: a toggle in the table makes it modified, apply stores it, reset reads it back. */
    fun testThePageAppliesTheTable() {
        val page = GoLintersConfigurable(project)
        try {
            page.createComponent()
            page.reset()
            assertFalse(page.isModified)
            val panel = GoLintersConfigurable::class.java.getDeclaredField("checks").apply { isAccessible = true }.get(page) as io.github.golangsupport.settings.GoChecksPanel
            panel.model.setEnabled(limit, true)
            assertTrue(page.isModified)
            page.apply()
            assertEquals(true, GoRuleSettings.getInstance(project).override(limit)?.enabled)
            assertFalse(page.isModified)
        } finally {
            page.disposeUIResources()
        }
    }
}
