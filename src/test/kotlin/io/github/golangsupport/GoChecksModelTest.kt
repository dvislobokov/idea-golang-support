package io.github.golangsupport

import io.github.golangsupport.settings.GoCheck
import io.github.golangsupport.settings.GoCheckKind
import io.github.golangsupport.settings.GoCheckLevel
import io.github.golangsupport.settings.GoCheckOption
import io.github.golangsupport.settings.GoCheckState
import io.github.golangsupport.settings.GoChecksModel
import io.github.golangsupport.settings.GoChecksRow
import io.github.golangsupport.settings.GoRuleOverrideText
import io.github.golangsupport.settings.GoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Built-in table of Settings | Go | Linters without Swing: rows, filter, group actions, what apply would write. */
class GoChecksModelTest {
    private val unusedLabel = GoCheck(GoCheckKind.INSPECTION, "GoUnusedLabel", "Unused label", "Go", true, GoCheckLevel.WARNING, description = { "Reports labels nobody jumps to" })
    private val lostCancel = GoCheck(GoCheckKind.INSPECTION, "GoLostCancel", "Lost cancel", "Go · Data flow", true, GoCheckLevel.WARNING, goplsQuiet = true)
    private val modPaths = GoCheck(GoCheckKind.INSPECTION, "GoModPaths", "Missing replace or use directory", "Go modules", true, GoCheckLevel.ERROR)
    private val lintRules = GoCheck(GoCheckKind.INSPECTION, "GoRules", "Go lint rules", "Lint rules", true, GoCheckLevel.WARNING)
    private val resultLimit = GoCheck(
        GoCheckKind.RULE, "revive:function-result-limit", "Too many function results", "Lint rules · revive", false, GoCheckLevel.WARNING,
        options = listOf(GoCheckOption.of("max", 3, "Maximum number of results", null)), master = "GoRules",
    )
    private val errcheck = GoCheck(GoCheckKind.RULE, "errcheck", "Unchecked error", "Lint rules · errcheck", true, GoCheckLevel.WARNING, configured = true, master = "GoRules")

    private fun model(states: Map<String, GoCheckState> = emptyMap()) =
        GoChecksModel(listOf(resultLimit, errcheck, lintRules, modPaths, lostCancel, unusedLabel), states)

    private fun GoChecksModel.render(): List<String> = rows.map { if (it is GoChecksRow.Header) "# ${it.group}" else (it as GoChecksRow.Item).check.id }

    @Test fun groupsAreSortedWithAHeaderEachAndTheInspectionOfTheRulesFirstInItsGroup() {
        assertEquals(
            listOf("# Go", "GoUnusedLabel", "# Go · Data flow", "GoLostCancel", "# Go modules", "GoModPaths", "# Lint rules", "GoRules",
                "# Lint rules · errcheck", "errcheck", "# Lint rules · revive", "revive:function-result-limit"),
            model().render(),
        )
    }

    @Test fun theFilterLooksAtNameIdDescriptionAndGroup() {
        val model = model()
        model.filter = "cancel"
        assertEquals(listOf("# Go · Data flow", "GoLostCancel"), model.render())
        model.filter = "nobody jumps"
        assertEquals(listOf("# Go", "GoUnusedLabel"), model.render())
        model.filter = "REVIVE"
        assertEquals(listOf("# Lint rules · revive", "revive:function-result-limit"), model.render())
        model.filter = "nothing like this"
        assertTrue(model.rows.isEmpty())
        model.filter = " "
        assertEquals(12, model.rows.size)
    }

    @Test fun aRowWithoutAStateStartsFromItsBaseAndTheModelIsNotModified() {
        val model = model()
        assertFalse(model.state("revive:function-result-limit").enabled)
        assertEquals(GoCheckLevel.DEFAULT, model.state("GoUnusedLabel").level)
        assertFalse(model.isModified)
        assertTrue(model.changed().isEmpty())
    }

    @Test fun theGroupCheckboxSwitchesTheWholeGroupAndResetGoesBackToTheBase() {
        val model = model(mapOf("GoUnusedLabel" to GoCheckState(false, GoCheckLevel.ERROR)))
        assertFalse(model.groupEnabled("Go"))
        model.setGroupEnabled("Go", true)
        assertTrue(model.groupEnabled("Go"))
        assertEquals(listOf("GoUnusedLabel"), model.changed().map { it.id })
        model.setGroupEnabled("Lint rules · revive", true)
        assertTrue(model.state("revive:function-result-limit").enabled)
        model.resetGroup("Go")
        assertEquals(GoCheckState(true, GoCheckLevel.DEFAULT), model.state("GoUnusedLabel"))
        model.resetAll()
        assertFalse(model.state("revive:function-result-limit").enabled)
        assertEquals(listOf("GoUnusedLabel"), model.changed().map { it.id })
    }

    @Test fun theRulesAreMutedWithTheInspectionThatRunsThem() {
        val model = model()
        assertFalse(model.mutedByMaster(errcheck))
        model.setEnabled("GoRules", false)
        assertTrue(model.mutedByMaster(errcheck))
        assertFalse(model.mutedByMaster(unusedLabel))
    }

    @Test fun optionsRoundTripAndTheBaseValueIsNotStored() {
        val model = model()
        val id = "revive:function-result-limit"
        assertEquals("3", model.optionText(id, "max"))
        model.setOption(id, "max", " 5 ")
        assertEquals("5", model.optionText(id, "max"))
        assertEquals(mapOf("max" to "5"), model.state(id).options)
        assertTrue(model.isModified)
        model.setOption(id, "max", "3")
        assertEquals(emptyMap<String, String>(), model.state(id).options)
        model.setOption(id, "max", "five")
        assertEquals("max: 'five' is not a number", model.problem())
        model.setOption(id, "max", "")
        assertNull(model.problem())
        model.setOption(id, "unknown", "1")
        assertEquals(emptyMap<String, String>(), model.state(id).options)
    }

    @Test fun theOverrideOfARuleKeepsOnlyWhatDiffersFromTheBase() {
        assertEquals(GoRuleOverrideText(null, null, emptyMap()), GoChecksModel.ruleOverride(errcheck, GoCheckState(true)))
        assertEquals(GoRuleOverrideText(false, GoCheckLevel.ERROR, emptyMap()), GoChecksModel.ruleOverride(errcheck, GoCheckState(false, GoCheckLevel.ERROR)))
        assertEquals(
            GoRuleOverrideText(true, null, mapOf("max" to "4")),
            GoChecksModel.ruleOverride(resultLimit, GoCheckState(true, GoCheckLevel.DEFAULT, mapOf("max" to "4", "other" to "1"))),
        )
        assertEquals(GoRuleOverrideText(null, null, emptyMap()), GoChecksModel.ruleOverride(resultLimit, GoCheckState(false, GoCheckLevel.DEFAULT, mapOf("max" to "3"))))
    }

    @Test fun optionTypesComeFromTheDefaults() {
        assertEquals(GoCheckOption.Type.INT, GoCheckOption.of("max", 3, "", null).type)
        assertEquals(GoCheckOption.Type.BOOL, GoCheckOption.of("check-blank", false, "", null).type)
        assertEquals(GoCheckOption.Type.STRING, GoCheckOption.of("style", "x", "", null).type)
        val list = GoCheckOption.of("exclude", listOf("fmt.Print"), "", listOf("a", "b"))
        assertEquals(GoCheckOption.Type.LIST, list.type)
        assertEquals("a, b", list.baseText)
        assertEquals("check-blank: 'maybe' is not true or false", GoCheckOption.of("check-blank", false, "", null).problem("maybe"))
    }

    @Test fun experimentsAreNamesSeparatedByCommas() {
        assertEquals("rangefunc,noswissmap", GoSettings.normalizeExperiments(" rangefunc, noswissmap ,"))
        assertNull(GoSettings.invalidExperiment("rangefunc,noswissmap aliastypeparams"))
        assertEquals("range-func", GoSettings.invalidExperiment("ok,range-func"))
        assertNull(GoSettings.invalidExperiment(""))
    }
}
