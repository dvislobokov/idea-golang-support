package io.github.golangsupport.compare

/**
 * Error-code coverage: the examples of `$GOROOT/src/internal/types/errors/codes.go` (`tools/compare/codes/extract.py` writes one package
 * per example under build/compare/codes) opened one by one, with what the editor shows on each. The expected code comes from go/types
 * (`tools/compare/oracle`), the verdicts from `tools/compare/codes/compare.py`.
 *
 *     GO_HARNESS=1 GO_HARNESS_ROOT=build/compare/codes ./gradlew.bat :test --tests "io.github.golangsupport.compare.GoErrorCodesHarnessTest" --offline --rerun
 */
class GoErrorCodesHarnessTest : GoHarnessTestBase() {

    override fun setUp() {
        super.setUp()
        // the examples of InvalidDeclCycle & co. are cyclic on purpose
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    fun testExamples() {
        if (!enabled) return
        val dirs = (root.findChild("ex") ?: error("no ex/ in ${root.path}: run tools/compare/codes/extract.py")).children.filter { it.isDirectory }.sortedBy { it.name }
        val result = LinkedHashMap<String, List<Problem>>()
        for (dir in dirs) {
            val vf = dir.findChild("example.go") ?: continue
            myFixture.configureFromExistingVirtualFile(vf)
            result[dir.name] = problems()
        }
        log("${result.size} examples, ${result.count { it.value.isNotEmpty() }} with problems")
        writeJson("codes-plugin.json", result)
    }
}
