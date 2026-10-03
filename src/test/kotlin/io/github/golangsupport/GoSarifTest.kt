package io.github.golangsupport

import com.google.gson.JsonParser
import io.github.golangsupport.ci.GoSarif
import io.github.golangsupport.ci.GoSarifLevel
import io.github.golangsupport.ci.GoSarifRegion
import io.github.golangsupport.ci.GoSarifResult
import io.github.golangsupport.ci.GoSarifRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GoSarifTest {

    private val rules = listOf(
        GoSarifRule("GoUnusedVariable", "Unused variable", "Reports variables that are declared but never used.", "Go", GoSarifLevel.WARNING),
        GoSarifRule("GoModVersions", "Go and toolchain versions", "", "Go modules", GoSarifLevel.WARNING),
        GoSarifRule("GoTypeMismatch", "Type mismatch", "Reports values of a type that does not fit.", "Go", GoSarifLevel.ERROR),
    )

    private val results = listOf(
        GoSarifResult("GoUnusedVariable", GoSarifLevel.WARNING, "Unused variable 'x'", "pkg/a%20b.go", GoSarifRegion(4, 2, 4, 3)),
        GoSarifResult("GoTypeMismatch", GoSarifLevel.ERROR, "Cannot use \"s\" (untyped string) as int", "main.go", GoSarifRegion(7, 10, 7, 13)),
        GoSarifResult("GoUnusedVariable", GoSarifLevel.WARNING, "Unused variable 'y' <-chan", "main.go", GoSarifRegion(3, 2, 3, 3)),
    )

    @Test
    fun reportMatchesGolden() {
        val actual = GoSarif.write("0.2.51", rules, results, "file:///C:/work/app/", listOf("GoNilDereference on main.go: IllegalStateException: boom"))
        val golden = Path.of("src/test/resources/ci/go-inspect.sarif.json")
        // a missing golden is written for review (delete it to regenerate after an intended change of the format)
        if (!Files.exists(golden)) Files.createDirectories(golden.parent).also { Files.writeString(golden, actual); fail("golden written, review it: $golden") }
        assertEquals(Files.readString(golden).replace("\r\n", "\n"), actual)
    }

    @Test
    fun ruleIndexPointsAtTheRuleOfTheResult() {
        val root = JsonParser.parseString(GoSarif.write(null, rules, results)).asJsonObject
        val run = root.getAsJsonArray("runs")[0].asJsonObject
        val ruleIds = run.getAsJsonObject("tool").getAsJsonObject("driver").getAsJsonArray("rules").map { it.asJsonObject["id"].asString }
        for (r in run.getAsJsonArray("results")) {
            val o = r.asJsonObject
            assertEquals(o["ruleId"].asString, ruleIds[o["ruleIndex"].asInt])
        }
        assertEquals("2.1.0", root["version"].asString)
        assertTrue(!run.has("originalUriBaseIds"))
    }

    @Test
    fun emptyRunIsStillAValidReport() {
        val run = JsonParser.parseString(GoSarif.write(null, emptyList(), emptyList())).asJsonObject.getAsJsonArray("runs")[0].asJsonObject
        assertEquals(0, run.getAsJsonArray("results").size())
        assertEquals(GoSarif.TOOL_NAME, run.getAsJsonObject("tool").getAsJsonObject("driver")["name"].asString)
    }

    @Test
    fun regionIsOneBasedWithExclusiveEnd() {
        val text = "package main\n\nfunc f() {\n\tx := 1\n}\n"
        val start = text.indexOf("x :=")
        assertEquals(GoSarifRegion(4, 2, 4, 3), GoSarif.region(text, start, start + 1))
        assertEquals(GoSarifRegion(1, 1, 1, 8), GoSarif.region(text, 0, 7))
        // across lines, and an empty range still covers one column
        assertEquals(GoSarifRegion(3, 10, 5, 2), GoSarif.region(text, text.indexOf('{'), text.lastIndexOf('}') + 1))
        assertEquals(GoSarifRegion(1, 3, 1, 4), GoSarif.region(text, 2, 2))
        // offsets past the end are clamped, not thrown
        assertEquals(6, GoSarif.region(text, text.length, text.length + 5).startLine)
    }

    @Test
    fun descriptionHtmlBecomesText() {
        val html = "<html><body>Reports <code>x &lt; y</code>.<!-- tooltip end --><p>Second&nbsp;paragraph</p><ul><li>one</li></ul></body></html>"
        assertEquals("Reports x < y. Second paragraph one", GoSarif.plainText(html))
        assertEquals("", GoSarif.plainText(null))
    }
}
