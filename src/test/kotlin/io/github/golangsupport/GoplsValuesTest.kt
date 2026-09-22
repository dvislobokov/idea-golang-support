package io.github.golangsupport

import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.settings.GoplsCatalogue
import io.github.golangsupport.settings.GoplsDefaults
import io.github.golangsupport.settings.GoplsDocs
import io.github.golangsupport.settings.GoplsValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoplsValuesTest {
    @Test fun lists() {
        assertEquals("""["-tags=integration","-mod=mod"]""", GoplsValues.listToJson("-tags=integration  -mod=mod"))
        assertEquals("""["-ldflags=-X main.v=1"]""", GoplsValues.listToJson("\"-ldflags=-X main.v=1\""))
        assertEquals("[]", GoplsValues.listToJson("  "))
        assertEquals("-tags=integration -mod=mod", GoplsValues.jsonToList("""["-tags=integration","-mod=mod"]"""))
        assertEquals("", GoplsValues.jsonToList("not json"))
    }

    @Test fun environment() {
        assertEquals("""{"GOFLAGS":"-mod=mod","GOOS":"linux"}""", GoplsValues.envToJson("GOFLAGS=-mod=mod GOOS=linux"))
        assertNull(GoplsValues.envToJson("GOOS"))
        assertNull(GoplsValues.envToJson("=linux"))
        assertEquals("GOFLAGS=-mod=mod GOOS=linux", GoplsValues.jsonToEnv("""{"GOFLAGS":"-mod=mod","GOOS":"linux"}"""))
    }

    @Test fun flags() {
        assertEquals(mapOf("tidy" to false, "test" to true), GoplsValues.jsonToFlags("""{"tidy":false,"test":true,"odd":"yes"}"""))
        assertTrue(GoplsValues.jsonToFlags(null).isEmpty())
        val inEffect = mapOf("tidy" to true, "test" to false, "vendor" to true)
        // only what differs is an override: the rest follows the defaults of the next gopls
        assertEquals("""{"test":true,"tidy":false}""", GoplsValues.flagsToJson(mapOf("tidy" to false, "test" to true, "vendor" to true), inEffect))
        assertNull(GoplsValues.flagsToJson(inEffect, inEffect))
    }

    /** What the page stores for a lens goes on top of what the plugin sets, key by key. */
    @Test fun overridesOnTopOfDefaults() {
        val settings = GoSettings()
        val merged = GoplsCatalogue.merge(GoplsDefaults.of(settings), mapOf("codelenses" to """{"test":true,"tidy":false}""", "staticcheck" to "false"))
        val lenses = merged["codelenses"] as Map<*, *>
        assertEquals(true, lenses["test"])
        assertEquals(false, lenses["tidy"])
        assertEquals(true, lenses["vendor"])
        assertEquals(false, merged["staticcheck"])
    }

    @Test fun docs() {
        assertEquals("Completion budget", GoplsDocs.title("completionBudget"))
        assertEquals("Staticcheck", GoplsDocs.title("staticcheck"))
        assertEquals("Configures the default set of analyses.", GoplsDocs.summary("staticcheck", "staticcheck configures the default set of analyses. More words.\n\nSecond paragraph."))
        assertEquals("Sets <code>-tags</code> &amp; more", GoplsDocs.summary("buildFlags", "buildFlags sets `-tags` & more"))
        assertEquals("Run go generate", GoplsDocs.summary("generate", "\"generate\": Run go generate\n\nMore"))
        assertEquals("Controls nil checks.", GoplsDocs.summary("nil", "\"nil\" controls nil checks."))
        val html =GoplsDocs.html("See [analyzers](analyzers.md#x) and [go](https://go.dev).\n\nNext")
        assertTrue(html, "<a href=\"https://go.dev/gopls/analyzers#x\">analyzers</a>" in html)
        assertTrue(html, "<a href=\"https://go.dev\">go</a>" in html)
        assertTrue(html, "<br><br>Next" in html)
        assertFalse("[" in html)
        assertEquals("Inlay Hints", GoplsDocs.groupTitle("ui.inlayhint"))
        assertEquals("General", GoplsDocs.groupTitle("ui"))
    }
}
