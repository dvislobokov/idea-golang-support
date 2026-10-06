package io.github.golangsupport.semantic

import java.io.File

/**
 * Harness over the files copied from `$GOROOT/src/internal/types/testdata` (see
 * `testData/types/goroot/SOURCES.txt`): every `/* ERROR */` site must produce a matching
 * diagnostic and no unannotated diagnostic may be reported. Known-unsupported sites are listed
 * in `testData/types/goroot/allowlist.txt`; the per-file numbers are printed.
 */
class GoTypesTestdataTest : GoErrorSiteTestBase() {

    fun testGoTypesTestdata() {
        val root = File(testDataPath("types/goroot").toString())
        val allowlist = loadAllowlist(testDataPath("types/goroot/allowlist.txt"))
        val files = root.walkTopDown().filter { it.isFile && it.extension == "go" }.sortedBy { it.path }.toList()
        assertTrue(files.size >= 40)
        val results = files.map { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            checkFile(f, rel, allowlist, "types_" + rel.removeSuffix(".go").replace('/', '_'))
        }
        println(report(results))
        val fps = results.flatMap { it.falsePositives }
        val missed = results.flatMap { it.missed }
        val stale = allowlist.keys.filter { key -> results.none { r -> r.missed.any { it.startsWith("$key:") } || r.allowlisted > 0 && key.startsWith(r.path + ":") } }
        if (stale.isNotEmpty()) println("stale allowlist entries (now matched or file skipped): $stale")
        assertTrue("false positives:\n" + fps.joinToString("\n"), fps.isEmpty())
        assertTrue("unmatched ERROR sites (add to the allowlist with a reason or fix the checker):\n" + missed.joinToString("\n"), missed.isEmpty())
        // Coverage may only improve (Phase 5c reached 91%, 0.0.9 reached 92.7%, builtin/constant checks 94.9%, generics gaps and Go 1.27 on top).
        val sites = results.sumOf { it.sites }
        val matched = results.sumOf { it.matched }
        assertTrue("ERROR-site coverage dropped to $matched/$sites", matched * 100 >= sites * MIN_COVERAGE_PERCENT)
    }

    private companion object {
        const val MIN_COVERAGE_PERCENT = 94
    }
}
