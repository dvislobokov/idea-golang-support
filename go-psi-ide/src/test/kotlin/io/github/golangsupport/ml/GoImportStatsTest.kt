package io.github.golangsupport.ml

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import io.github.completionml.core.imports.ImportsModel
import io.github.golangsupport.ide.completion.GoCompletionAssistSettings
import io.github.golangsupport.ide.completion.GoCompletionTestBase
import io.github.golangsupport.ide.completion.GoCompletionWeigher
import io.github.golangsupport.ide.inspections.GoAddImportFix
import io.github.golangsupport.ide.inspections.GoUnresolvedReferenceInspection
import io.github.golangsupport.lang.psi.GoFile
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The order of import candidates by the corpus statistics ([GoImportStats]): the add-import fix and the unimported packages of the
 * completion list over a toy `imports` artifact written with [ImportsModel.write] (`rand` is `crypto/rand` 80 % / `math/rand` 20 %, `math/rand`
 * goes with `time`, `crypto/rand` with `crypto/sha256`; `math/rand/v2` and `template` unknown), and a few stable answers of the real
 * `ml-models/go/go-imports-e20.cml` (skipped without it).
 */
class GoImportStatsTest : GoCompletionTestBase() {
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("go-import-stats", null)
        ImportsModel.write(
            File(dir, GoImportStats.MODEL), "go", mapOf("lambda" to "1.0", "ctx_norm" to "sum"), 100,
            listOf("crypto/rand", "math/rand", "crypto/sha256", "time"), listOf(40, 10, 30, 50),
            names = mapOf("rand" to (50 to listOf("crypto/rand" to q(0.8), "math/rand" to q(0.2)))),
            co = mapOf("math/rand" to listOf("time" to 48), "crypto/rand" to listOf("crypto/sha256" to 32)),
        )
        use(dir)
    }

    override fun tearDown() {
        try {
            GoCompletionAssistSettings.getInstance().importStatsEnabled = true
            GoMlSettings.getInstance().modelDirectory = ""
            GoImportStats.getInstance().reset()
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun q(p: Double) = (-ln(p) * 16).roundToInt()

    private fun use(modelDir: File) {
        GoMlSettings.getInstance().modelDirectory = modelDir.path
        GoImportStats.getInstance().reset()
        assertNotNull("no model in $modelDir", GoImportStats.getInstance().loadSynchronously())
    }

    /** The paths of the add-import fix for the qualifier [qualifier] of the configured file, in the order offered. */
    private fun fixCandidates(qualifier: String): List<String> {
        val file = myFixture.file as GoFile
        val offset = file.text.indexOf("$qualifier.")
        assertTrue(qualifier, offset >= 0)
        return GoAddImportFix.candidates(file, TextRange(offset, offset + qualifier.length), 5)?.second ?: error("no qualifier $qualifier")
    }

    /** The import paths of the completion items with the lookup string [name], in the order of the list. */
    private fun completionPaths(name: String): List<String> =
        myFixture.lookupElements.orEmpty().filter { it.lookupString == name }.map { GoCompletionWeigher.infoOf(it)?.importPath ?: "?" }

    fun testFixCandidatesFollowTheStatistics() {
        myFixture.configureByText("a.go", "package a\n\nfunc f() int {\n\treturn rand.Int()\n}\n")
        // the prior: crypto/rand 80 %; math/rand/v2 is unknown to the toy model and keeps the end
        assertEquals(listOf("crypto/rand", "math/rand", "math/rand/v2"), fixCandidates("rand"))
        // the context: math/rand next to time (ln 0.2 + 3.0 > ln 0.8)
        myFixture.configureByText("b.go", "package a\n\nimport \"time\"\n\nfunc f() int {\n\t_ = time.Now()\n\treturn rand.Int()\n}\n")
        assertEquals(listOf("math/rand", "crypto/rand", "math/rand/v2"), fixCandidates("rand"))
        myFixture.configureByText("c.go", "package a\n\nimport \"crypto/sha256\"\n\nfunc f() int {\n\t_ = sha256.New()\n\treturn rand.Int()\n}\n")
        assertEquals(listOf("crypto/rand", "math/rand", "math/rand/v2"), fixCandidates("rand"))
    }

    fun testThePopupOfTheFixOffersTheStatisticsChoiceFirst() {
        myFixture.enableInspections(GoUnresolvedReferenceInspection())
        myFixture.configureByText("a.go", "package a\n\nimport \"time\"\n\nfunc f() int {\n\t_ = time.Now()\n\treturn <caret>rand.Int()\n}\n")
        // the Alt+Enter list is sorted by the platform; the popup of the hint (and the default of the fix) follows the fix's own list
        val fix = myFixture.findSingleIntention("Import \"math/rand\"").let { IntentionActionDelegate.unwrap(it) }.let { QuickFixWrapper.unwrap(it) ?: it }
        assertEquals(listOf("math/rand", "crypto/rand", "math/rand/v2"), (fix as GoAddImportFix).all)
    }

    fun testUnimportedPackagesInCompletionFollowTheStatistics() {
        complete("""
            package main

            func main() {
                ra<caret>
            }
        """)
        assertEquals(listOf("crypto/rand", "math/rand", "math/rand/v2"), completionPaths("rand"))
        complete("""
            package main

            import "time"

            func main() {
                _ = time.Now()
                ra<caret>
            }
        """)
        assertEquals(listOf("math/rand", "crypto/rand", "math/rand/v2"), completionPaths("rand"))
    }

    fun testUnknownNamesKeepTheOrder() {
        val text = """
            package main

            import "net/http"

            func main() {
                _ = http.StatusOK
                templ<caret>
            }
        """
        GoCompletionAssistSettings.getInstance().importStatsEnabled = false
        complete(text)
        val plain = completionPaths("template")
        assertEquals(plain.toString(), 2, plain.size)
        GoCompletionAssistSettings.getInstance().importStatsEnabled = true
        complete(text)
        assertEquals(plain, completionPaths("template"))
        assertNull(myFixture.lookupElements.orEmpty().first { it.lookupString == "template" }.let { GoCompletionWeigher.infoOf(it)!!.importStatsScore })
        // the fix candidates of an unknown name are not touched either
        myFixture.configureByText("a.go", "package a\n\nimport \"net/http\"\n\nfunc f() {\n\t_ = http.StatusOK\n\t_ = template.New(\"x\")\n}\n")
        assertEquals(listOf("html/template", "text/template"), fixCandidates("template"))
    }

    fun testSettingOffLeavesTheOrderAlone() {
        GoCompletionAssistSettings.getInstance().importStatsEnabled = false
        myFixture.configureByText("a.go", "package a\n\nimport \"time\"\n\nfunc f() int {\n\t_ = time.Now()\n\treturn rand.Int()\n}\n")
        // the plugin's own order: the standard library in GOROOT order
        assertEquals(listOf("crypto/rand", "math/rand", "math/rand/v2"), fixCandidates("rand"))
        complete("""
            package main

            import "time"

            func main() {
                _ = time.Now()
                ra<caret>
            }
        """)
        assertEquals(listOf("crypto/rand", "math/rand", "math/rand/v2"), completionPaths("rand"))
        assertNull(myFixture.lookupElements.orEmpty().first { it.lookupString == "rand" }.let { GoCompletionWeigher.infoOf(it)!!.importStatsScore })
        assertEquals("off", GoImportStats.getInstance().status())
    }

    fun testOrderHelper() {
        val stats = GoImportStats.getInstance()
        assertEquals(listOf("crypto/rand", "math/rand", "x/rand"), stats.order("rand", listOf("x/rand", "math/rand", "crypto/rand"), emptyList()))
        assertEquals(listOf("math/rand", "crypto/rand", "x/rand"), stats.order("rand", listOf("x/rand", "crypto/rand", "math/rand"), listOf("time")))
        assertEquals(listOf("b", "a"), stats.order("nothing", listOf("b", "a"), emptyList()))
        assertEquals(listOf("y/rand", "x/rand"), stats.order("rand", listOf("y/rand", "x/rand"), emptyList()))
        assertNotNull(stats.score("rand", "math/rand", listOf("time")))
        assertNull(stats.score("rand", "x/rand", emptyList()))
        assertNull(stats.score("nothing", "math/rand", emptyList()))
    }

    fun testRealModel() {
        val real = listOf(File("../ml-models/go"), File("ml-models/go")).firstOrNull { File(it, GoImportStats.MODEL).isFile }
        if (real == null) { println("GoImportStatsTest.testRealModel: no ml-models/go/${GoImportStats.MODEL}, skipped"); return }
        use(real)
        val model = GoImportStats.getInstance().model()!!
        assertEquals("net/http", model.rankImports("Client", emptyList()).first().path)
        assertEquals("net/http", model.rankImports("Client", listOf("encoding/json")).first().path)
        assertEquals("text/template", model.rankImports("template", emptyList()).first().path)
        assertEquals("html/template", model.rankImports("template", listOf("net/http")).first().path)
        assertEquals("crypto/rand", model.rankImports("rand", listOf("crypto/sha256")).first().path)
        assertEquals("net/http/pprof", model.rankImports("pprof", listOf("net/http")).first().path)
        assertEquals("runtime/pprof", model.rankImports("pprof", emptyList()).first().path)
        // through the fix: the real GOROOT has both template packages
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t_ = template.New(\"x\")\n}\n")
        assertEquals("text/template", fixCandidates("template").first())
        myFixture.configureByText("b.go", "package a\n\nimport \"net/http\"\n\nfunc f() {\n\t_ = http.StatusOK\n\t_ = template.New(\"x\")\n}\n")
        assertEquals("html/template", fixCandidates("template").first())
        myFixture.configureByText("c.go", "package a\n\nimport \"net/http\"\n\nfunc f() {\n\t_ = http.StatusOK\n\t_ = pprof.Profile\n}\n")
        assertEquals("net/http/pprof", fixCandidates("pprof").first())
    }
}
