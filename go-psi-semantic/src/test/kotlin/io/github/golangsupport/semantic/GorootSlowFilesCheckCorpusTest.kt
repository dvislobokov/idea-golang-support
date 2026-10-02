package io.github.golangsupport.semantic

import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * Dev tool, not a gate: first-touch and warm cost of `check(file)` on single GOROOT files, for
 * profiling. Runs only with `-Dgopsi.check.files=<list>` (paths relative to `$GOROOT/src`,
 * comma-separated, at most three; `default` = the huge generated files
 * `cmd/compile/internal/ssa/opGen.go`, `ssa/rewriteAMD64.go`, `ssagen/simdAMD64intrinsics.go`).
 * Each test method checks one file; the light project is shared, so later files find the packages
 * of earlier ones built. Prints `FIRST check` / `WARM check` times.
 *
 * ```
 * ./gradlew :go-psi-semantic:corpusTest --tests "*GorootSlowFilesCheckCorpusTest" -Dgopsi.check.files=default \
 *     "-Pgopsi.corpus.jvmArgs=-XX:StartFlightRecording=filename=check.jfr,settings=profile"
 * ```
 */
class GorootSlowFilesCheckCorpusTest : GoProjectModelTestBase() {

    private companion object {
        val DEFAULT_FILES = listOf(
            "cmd/compile/internal/ssa/opGen.go",
            "cmd/compile/internal/ssa/rewriteAMD64.go",
            "cmd/compile/internal/ssagen/simdAMD64intrinsics.go",
        )
    }

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    private fun files(): List<String> {
        val property = System.getProperty("gopsi.check.files")?.trim().orEmpty()
        if (property.isEmpty()) return emptyList()
        if (property == "default") return DEFAULT_FILES
        return property.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun checkOne(rel: String) {
        val path = ProjectTestUtil.goroot().resolve("src").resolve(rel)
        val psi = PsiManager.getInstance(project).findFile(vfs(path)) as GoFile
        val semantic = GoSemanticService.getInstance(project)
        val t0 = System.currentTimeMillis()
        val first = semantic.check(psi)
        val t1 = System.currentTimeMillis()
        val warm = semantic.check(psi)
        val t2 = System.currentTimeMillis()
        println("FIRST check: $rel ${t1 - t0} ms (${first.size} diagnostics); WARM check: ${t2 - t1} ms (${warm.size})")
        System.out.flush()
    }

    fun testFile1() = runIfSelected(0)

    fun testFile2() = runIfSelected(1)

    fun testFile3() = runIfSelected(2)

    private fun runIfSelected(i: Int) {
        files().getOrNull(i)?.let(::checkOne)
    }
}
