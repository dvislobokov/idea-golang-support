package io.github.golangsupport

import io.github.golangsupport.lint.GoVulnCache
import io.github.golangsupport.lint.GoVulnOutput
import io.github.golangsupport.lint.GoVulnSummary
import io.github.golangsupport.mod.GoRequire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `govulncheck -json` stream (written by hand after the protocol v1.0.0 schema), its derived findings, and the disk cache. */
class GoVulnOutputTest {
    companion object {
        /** Pretty-printed objects one after another, as govulncheck prints them; module-, package- and symbol-level findings. */
        val SAMPLE = """
            {
              "config": {
                "protocol_version": "v1.0.0",
                "scanner_name": "govulncheck",
                "scanner_version": "v1.1.4",
                "db": "https://vuln.go.dev",
                "go_version": "go1.22.1",
                "scan_level": "symbol",
                "scan_mode": "source"
              }
            }
            {
              "progress": {
                "message": "Scanning your code and 46 packages across 3 dependent modules for known vulnerabilities..."
              }
            }
            {
              "osv": {
                "schema_version": "1.3.1",
                "id": "GO-2022-1059",
                "aliases": ["CVE-2022-32149", "GHSA-69ch-w2m2-3vjp"],
                "summary": "Denial of service via crafted Accept-Language header in golang.org/x/text/language",
                "affected": [{"package": {"name": "golang.org/x/text", "ecosystem": "Go"}, "ranges": [{"type": "SEMVER", "events": [{"introduced": "0"}, {"fixed": "0.3.8"}]}]}]
              }
            }
            {
              "osv": {
                "id": "GO-2023-1840",
                "summary": "Unsafe behavior in setuid/setgid binaries in runtime",
                "affected": [{"package": {"name": "stdlib", "ecosystem": "Go"}}]
              }
            }
            {
              "osv": {
                "id": "GO-2024-2611",
                "summary": "Infinite loop in JSON unmarshaling in google.golang.org/protobuf"
              }
            }
            {
              "finding": {
                "osv": "GO-2022-1059",
                "fixed_version": "v0.3.8",
                "trace": [{"module": "golang.org/x/text", "version": "v0.3.7"}]
              }
            }
            {
              "finding": {
                "osv": "GO-2022-1059",
                "fixed_version": "v0.3.8",
                "trace": [{"module": "golang.org/x/text", "version": "v0.3.7", "package": "golang.org/x/text/language"}]
              }
            }
            {
              "finding": {
                "osv": "GO-2022-1059",
                "fixed_version": "v0.3.8",
                "trace": [
                  {"module": "golang.org/x/text", "version": "v0.3.7", "package": "golang.org/x/text/language", "function": "ParseAcceptLanguage",
                   "position": {"filename": "language/parse.go", "offset": 1200, "line": 200, "column": 6}},
                  {"module": "example.com/app", "package": "example.com/app/web", "function": "lang", "position": {"filename": "web/lang.go", "offset": 99, "line": 12, "column": 37}},
                  {"module": "example.com/app", "package": "example.com/app", "function": "main", "position": {"filename": "main.go", "offset": 40, "line": 8, "column": 10}}
                ]
              }
            }
            {
              "finding": {
                "osv": "GO-2023-1840",
                "fixed_version": "v1.20.5",
                "trace": [{"module": "stdlib", "version": "v1.20.1", "package": "runtime"}]
              }
            }
            {
              "finding": {
                "osv": "GO-2024-2611",
                "fixed_version": "v1.33.0",
                "trace": [{"module": "google.golang.org/protobuf", "version": "v1.32.0"}]
              }
            }
        """.trimIndent()
    }

    @Test fun parsesTheStream() {
        val report = GoVulnOutput.parse(SAMPLE)!!
        assertEquals("v1.1.4", report.scannerVersion)
        assertEquals(setOf("GO-2022-1059", "GO-2023-1840", "GO-2024-2611"), report.osvs.keys)
        assertEquals(listOf("CVE-2022-32149", "GHSA-69ch-w2m2-3vjp"), report.osvs.getValue("GO-2022-1059").aliases)
        assertEquals(5, report.findings.size)
        val frame = report.findings[2].trace[0]
        assertEquals("language.ParseAcceptLanguage", frame.symbol)
        assertEquals(200, frame.line)
    }

    @Test fun notAReport() {
        assertNull(GoVulnOutput.parse(""))
        assertNull(GoVulnOutput.parse("govulncheck: loading packages: go.mod not found"))
        assertNull(GoVulnOutput.parse("{\"progress\":{\"message\":\"x\"}}"))
        // no findings: a clean module
        assertEquals(0, GoVulnOutput.parse("{\"config\":{\"protocol_version\":\"v1.0.0\"}}")!!.findings.size)
    }

    @Test fun truncatedStreamKeepsWhatWasRead() {
        val cut = SAMPLE.substring(0, SAMPLE.indexOf("\"fixed_version\": \"v1.33.0\""))
        assertEquals(4, GoVulnOutput.parse(cut)!!.findings.size)
    }

    @Test fun packagesOfImports() {
        val packages = GoVulnOutput.parse(SAMPLE)!!.packages()
        assertEquals(listOf("golang.org/x/text/language" to "GO-2022-1059", "runtime" to "GO-2023-1840"), packages.map { it.pkg to it.osv })
        val text = packages[0]
        assertEquals("Package 'golang.org/x/text/language' of module golang.org/x/text@v0.3.7 is affected by GO-2022-1059: Denial of service via crafted Accept-Language header in golang.org/x/text/language",
            GoVulnOutput.importMessage(text))
        assertTrue(packages[1].isStdlib)
        assertEquals("Package 'runtime' of the standard library (Go 1.20.1) is affected by GO-2023-1840: Unsafe behavior in setuid/setgid binaries in runtime (fixed in Go 1.20.5)",
            GoVulnOutput.importMessage(packages[1]))
    }

    @Test fun callSitesOfTheModule() {
        val sites = GoVulnOutput.parse(SAMPLE)!!.callSites("example.com/app")
        assertEquals(1, sites.size)
        val site = sites[0]
        assertEquals("web/lang.go" to 12, site.file to site.line)
        assertEquals("ParseAcceptLanguage", site.callee)
        assertTrue(site.direct)
        assertEquals("Call to vulnerable function language.ParseAcceptLanguage (GO-2022-1059)", GoVulnOutput.callMessage(site))
        assertEquals("Call to web.lang reaches vulnerable function language.ParseAcceptLanguage (GO-2022-1059)", GoVulnOutput.callMessage(site.copy(direct = false, calleeSymbol = "web.lang")))
        assertTrue(GoVulnOutput.parse(SAMPLE)!!.callSites("example.com/other").isEmpty())
    }

    @Test fun methodSymbolsNameTheReceiver() {
        val json = "{\"config\":{}}{\"finding\":{\"osv\":\"GO-1\",\"trace\":[{\"module\":\"net/http\",\"package\":\"net/http\",\"function\":\"ParseForm\",\"receiver\":\"*Request\"}]}}"
        assertEquals("http.Request.ParseForm", GoVulnOutput.parse(json)!!.findings[0].trace[0].symbol)
    }

    @Test fun summaryCountsByLevel() {
        val summary = GoVulnOutput.parse(SAMPLE)!!.summary()
        assertEquals(GoVulnSummary(called = 1, imported = 1, required = 1), summary)
        assertEquals("1 vulnerability reachable from the code, 1 more in imported packages, 1 more in required modules", summary.text())
        assertEquals("No known vulnerabilities", GoVulnSummary(0, 0, 0).text())
    }

    @Test fun describeForTheBuildWindow() {
        val requires = listOf(GoRequire("golang.org/x/text", "v0.3.7", false, 4), GoRequire("google.golang.org/protobuf", "v1.32.0", true, 5))
        val lines = GoVulnOutput.describe(GoVulnOutput.parse(SAMPLE)!!, "example.com/app", requires)
        assertEquals(listOf(
            "web/lang.go:12:37: Call to vulnerable function language.ParseAcceptLanguage (GO-2022-1059)",
            "go.mod:1:1: the standard library is affected by GO-2023-1840: Unsafe behavior in setuid/setgid binaries in runtime; fixed in Go 1.20.5",
            "go.mod:6:1: google.golang.org/protobuf@v1.32.0 is affected by GO-2024-2611: Infinite loop in JSON unmarshaling in google.golang.org/protobuf; fixed in v1.33.0",
        ), lines)
    }

    @Test fun argumentsCarryTheBuildTags() {
        assertEquals(listOf("-json", "./..."), GoVulnOutput.arguments(emptyList()))
        assertEquals(listOf("-json", "-tags=integration,e2e", "./..."), GoVulnOutput.arguments(listOf("integration", "e2e")))
    }

    @Test fun cacheRoundTripAndFreshness() {
        val key = GoVulnCache.key("module m".toByteArray(), "sum".toByteArray())
        assertEquals(key, GoVulnCache.key("module m".toByteArray(), "sum".toByteArray()))
        assertNotEquals(key, GoVulnCache.key("module m".toByteArray(), null))
        val stored = GoVulnCache.decode(GoVulnCache.encode(GoVulnCache.Stored(key, 1000, SAMPLE)))!!
        assertEquals(key to 1000L, stored.key to stored.at)
        assertEquals(SAMPLE, stored.stdout)
        assertNull(GoVulnCache.decode("something else\n"))
        assertTrue(GoVulnCache.fresh(key, 1000, key, 1000 + 59 * 60_000, 3_600_000))
        assertFalse(GoVulnCache.fresh(key, 1000, key, 1000 + 61 * 60_000, 3_600_000))
        assertFalse(GoVulnCache.fresh(key, 1000, "other", 1001, 3_600_000))
        assertNotEquals(GoVulnCache.fileName("/a"), GoVulnCache.fileName("/b"))
    }
}
