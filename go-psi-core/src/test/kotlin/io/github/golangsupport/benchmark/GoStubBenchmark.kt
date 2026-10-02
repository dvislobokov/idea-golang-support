package io.github.golangsupport.benchmark

import io.github.golangsupport.GoStubTestCase
import io.github.golangsupport.lang.psi.GoFile

/** Stub build for every `.go` file of `$GOROOT/src/net/http`. Unit: ms per 1000 stubs. */
class GoStubBenchmark : GoStubTestCase() {

    fun testNetHttpStubs() {
        val files: List<GoFile> = BenchmarkSupport.goFilesIn("net/http").map { (name, text) -> createLightGoFile(name, text) }
        // Parse and count outside the timed section: only the stub builder is measured.
        files.forEach { it.node }
        val stubs = files.sumOf { countStubs(buildFromPsi(it)) }
        assertTrue("no stubs", stubs > 0)
        BenchmarkSupport.run("GoStubBenchmark.nethttp", "ms/1000stubs", stubs * PASSES / 1000.0) {
            // One pass is ~15 ms: repeat it to get a stable time.
            repeat(PASSES) { for (file in files) buildFromPsi(file) }
        }
    }

    private companion object {
        const val PASSES = 5
    }
}
