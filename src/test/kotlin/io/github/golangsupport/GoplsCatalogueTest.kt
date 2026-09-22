package io.github.golangsupport

import io.github.golangsupport.settings.GoplsCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** On a slice of what `gopls api-json` of gopls 0.23 prints (src/test/resources/gopls-api.json). */
class GoplsCatalogueTest {
    private val options = GoplsCatalogue.parse(javaClass.getResource("/gopls-api.json")!!.readText())
    private fun option(name: String) = options.first { it.name == name }

    @Test fun theCatalogueOfTheServer() {
        assertEquals(setOf("staticcheck", "completionBudget", "hoverKind", "buildFlags", "codelenses", "env", "symbolScope"), options.map { it.name }.toSet())
        // by group, then by name: `build` before `ui...`
        assertEquals("build", options.first().hierarchy)

        val staticcheck = option("staticcheck")
        assertTrue(staticcheck.isBool)
        assertEquals("false", staticcheck.default)
        assertEquals("experimental", staticcheck.status)
        assertEquals(listOf("true", "false"), staticcheck.choices)

        val hover = option("hoverKind")
        assertTrue(hover.isEnum && hover.isPlainText)
        assertTrue("SingleLine" in hover.choices && "\"SingleLine\"" !in hover.choices)

        val lenses = option("codelenses")
        assertEquals("map[enum]bool", lenses.type)
        assertTrue(lenses.enumKeys.any { it.name == "vulncheck" && it.default == "false" })
        assertTrue(lenses.choices.isEmpty())
    }

    @Test fun whatIsTypedBecomesJson() {
        assertEquals("true", GoplsCatalogue.toJson(option("staticcheck"), " true "))
        assertNull(GoplsCatalogue.toJson(option("staticcheck"), "yes"))
        assertEquals("\"200ms\"", GoplsCatalogue.toJson(option("completionBudget"), "200ms"))
        assertEquals("\"SingleLine\"", GoplsCatalogue.toJson(option("hoverKind"), "SingleLine"))
        assertEquals("[\"-tags=integration\"]", GoplsCatalogue.toJson(option("buildFlags"), "[ \"-tags=integration\" ]"))
        // a list is a list, a map is a map, and neither is a word
        assertNull(GoplsCatalogue.toJson(option("buildFlags"), "-tags=integration"))
        assertNull(GoplsCatalogue.toJson(option("buildFlags"), "{\"a\": true}"))
        assertNull(GoplsCatalogue.toJson(option("env"), "[1]"))
        assertEquals("{\"GOFLAGS\":\"-mod=mod\"}", GoplsCatalogue.toJson(option("env"), "{\"GOFLAGS\": \"-mod=mod\"}"))
    }

    @Test fun shownAsTyped() {
        assertEquals("100ms", GoplsCatalogue.display("\"100ms\"", plain = true))
        assertEquals("[]", GoplsCatalogue.display("[]", plain = false))
        assertEquals("false", GoplsCatalogue.display("false", plain = true))
    }

    @Test fun overridesGoOnTopOfWhatThePluginSets() {
        val base = mapOf<String, Any>("staticcheck" to true, "codelenses" to mapOf("tidy" to true, "vulncheck" to true), "usePlaceholders" to true)
        val merged = GoplsCatalogue.merge(base, mapOf("staticcheck" to "false", "codelenses" to "{\"test\":true,\"tidy\":false}", "hoverKind" to "\"SingleLine\"", "broken" to "{oops"))
        assertEquals(false, merged["staticcheck"])
        // a map is merged: the lens that was added does not remove the ones the plugin has set
        assertEquals(mapOf("tidy" to false, "vulncheck" to true, "test" to true), merged["codelenses"])
        assertEquals("SingleLine", merged["hoverKind"])
        assertEquals(true, merged["usePlaceholders"])
        assertFalse("broken" in merged)
    }
}
