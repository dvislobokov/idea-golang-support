package io.github.golangsupport.benchmark

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.util.SimpleModificationTracker
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * Basic completion latency in a generated 3000-line project file with 500 package-level symbols
 * (100 types with a method each, 150 functions, 150 variables, 100 constants) that imports
 * `net/http`, `fmt` and `strings`:
 *
 * - `member*`: after `.` on a `*http.Request` value (fields and methods of a GOROOT type).
 * - `statement*`: at an empty statement position (every package-level symbol, locals, imports,
 *   keywords, universe).
 *
 * `*Cold` bumps the Go trackers before every iteration (all semantic caches discarded); `*Warm`
 * keeps them. Unit: ms per `completeBasic` (the lookup is hidden untimed between runs). Completion
 * runs on a copy of the file, so even "warm" re-types the copy's expressions: the target of
 * per-function-body inference; cold additionally shows the package-level recomputation.
 */
class GoCompletionLatencyBenchmark : GoSemanticIdeTestBase() {

    fun testMemberCold() = measure("memberCold", "req.<caret>", cold = true, expected = "Header")

    fun testMemberWarm() = measure("memberWarm", "req.<caret>", cold = false, expected = "Header")

    fun testStatementCold() = measure("statementCold", "<caret>", cold = true, expected = "Func149")

    fun testStatementWarm() = measure("statementWarm", "<caret>", cold = false, expected = "Func149")

    private fun measure(case: String, site: String, cold: Boolean, expected: String) {
        val text = bigFile(site)
        assertTrue("generated file too short: ${text.lines().size}", text.lines().size >= 3000)
        myFixture.configureByText("big.go", text)
        val trackers = GoTrackers.getInstance(project)
        var items: List<String> = emptyList()
        BenchmarkSupport.runTimed(
            "GoCompletionLatencyBenchmark.$case",
            "ms",
            1.0,
            prepare = {
                LookupManager.getInstance(project).hideActiveLookup()
                if (cold) {
                    trackers.invalidateAll()
                    trackers.anyGoChange.incModificationCount()
                    (trackers.forFile(myFixture.file) as SimpleModificationTracker).incModificationCount()
                }
            },
        ) {
            BenchmarkSupport.timed { myFixture.completeBasic() }.also { items = myFixture.lookupElementStrings.orEmpty() }
        }
        LookupManager.getInstance(project).hideActiveLookup()
        assertTrue("'$expected' not offered (${items.size} items): ${items.take(20)}", expected in items)
        println("  $case: ${items.size} items")
    }

    private companion object {
        const val TYPES = 100
        const val FUNCS = 150
        const val VARS = 150
        const val CONSTS = 100

        /** 500 package-level symbols, padded with realistic bodies to 3000+ lines; [site] goes into the last function. */
        fun bigFile(site: String) = buildString {
            append("package big\n\nimport (\n\t\"fmt\"\n\t\"net/http\"\n\t\"strings\"\n)\n\n")
            for (i in 0 until CONSTS) append("const Const$i = $i\n")
            append('\n')
            for (i in 0 until VARS) append("var Var$i = \"v$i\"\n")
            for (i in 0 until TYPES) {
                append("\ntype Type$i struct {\n\tID   int\n\tName string\n\tReq  *http.Request\n}\n")
                append("\nfunc (t *Type$i) Describe() string {\n\treturn fmt.Sprintf(\"%d %s\", t.ID, strings.ToUpper(t.Name))\n}\n")
            }
            for (i in 0 until FUNCS) {
                append("\nfunc Func$i(w http.ResponseWriter, r *http.Request) {\n")
                append("\tt := &Type${i % TYPES}{ID: Const${i % CONSTS}, Name: Var${i % VARS}, Req: r}\n")
                append("\tparts := strings.Split(r.URL.Path, \"/\")\n")
                append("\tfor j, p := range parts {\n")
                append("\t\tif p == \"\" {\n\t\t\tcontinue\n\t\t}\n")
                append("\t\tfmt.Fprintf(w, \"%d=%s;\", j, p)\n")
                append("\t}\n")
                append("\tw.Header().Set(\"X-Name\", t.Describe())\n")
                append("\tw.WriteHeader(http.StatusOK)\n")
                append("}\n")
            }
            append("\nfunc Use(req *http.Request) {\n\tfmt.Println(req.Method)\n\t$site\n}\n")
        }
    }
}
