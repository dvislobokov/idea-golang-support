package io.github.golangsupport.semantic

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.check.GoChecker
import io.github.golangsupport.semantic.check.GoIncrementalChecker
import java.io.File
import java.nio.file.Files

/**
 * Per-body diagnostics ([GoIncrementalChecker]): `check(file)` assembled from a cached
 * package-level pass and cached per-body results equals the single-walk checker
 * ([GoChecker.checkMonolithic]), and an edit inside one function recomputes only that function.
 */
class GoIncrementalCheckTest : GoErrorSiteTestBase() {

    private val semantic get() = GoSemanticService.getInstance(project)

    private fun render(file: GoFile, list: List<GoDiagnostic>): String =
        list.joinToString("\n") { "${it.range} [${it.code}] ${it.message.lineSequence().first()} '${file.text.substring(it.range.startOffset, minOf(it.range.endOffset, it.range.startOffset + 30))}'" }

    /** Split (cold, then warm from the caches) equals monolithic, same order. */
    private fun assertSplitEqualsMonolithic(file: GoFile, label: String = file.name) {
        val split = semantic.check(file)
        val mono = GoChecker(project, file).checkMonolithic()
        if (split != mono) fail("$label: split != monolithic\n--- split:\n${render(file, split)}\n--- monolithic:\n${render(file, mono)}")
        val warm = semantic.check(file)
        if (warm != mono) fail("$label: warm split != monolithic\n--- split:\n${render(file, warm)}\n--- monolithic:\n${render(file, mono)}")
    }

    // --- equivalence ---

    fun testSplitEqualsMonolithicOnCheckFixtures() {
        val files = File(testDataPath("check").toString()).listFiles { f -> f.extension == "go" }!!.sortedBy { it.name }
        assertTrue(files.size >= 10)
        for (f in files) {
            val psi = myFixture.addFileToProject("inc_check_${f.nameWithoutExtension}/${f.name}", f.readText().replace("\r\n", "\n")) as GoFile
            assertSplitEqualsMonolithic(psi, "check/${f.name}")
        }
    }

    fun testSplitEqualsMonolithicOnGoTypesTestdata() {
        val root = File(testDataPath("types/goroot").toString())
        val files = root.walkTopDown().filter { it.isFile && it.extension == "go" }.sortedBy { it.path }.toList()
        assertTrue(files.size >= 40)
        var diagnostics = 0
        for (f in files) {
            val rel = f.relativeTo(root).path.replace('\\', '/')
            val psi = myFixture.addFileToProject("inc_types_" + rel.removeSuffix(".go").replace('/', '_') + "/" + f.name, f.readText().replace("\r\n", "\n")) as GoFile
            assertSplitEqualsMonolithic(psi, rel)
            diagnostics += semantic.check(psi).size
        }
        assertTrue("the testdata produces diagnostics: $diagnostics", diagnostics > 500)
    }

    fun testSplitEqualsMonolithicOnGorootFiles() {
        val src = ProjectTestUtil.goroot().resolve("src")
        val goTypes = Files.list(src.resolve("go/types")).use { s ->
            s.filter { it.fileName.toString().endsWith(".go") && !it.fileName.toString().endsWith("_test.go") }.sorted().toList()
        }
        assertTrue(goTypes.size > 20)
        for (path in listOf(src.resolve("net/http/server.go")) + goTypes) {
            val psi = PsiManager.getInstance(project).findFile(vfs(path)) as GoFile
            assertSplitEqualsMonolithic(psi, src.relativize(path).toString())
        }
    }

    // --- incrementality ---

    private fun edit(file: GoFile, marker: String, replacement: String) {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val offset = doc.text.indexOf(marker)
        assertTrue("marker $marker not found", offset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(offset, offset + marker.length, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
    }

    private fun body(file: GoFile, name: String): GoBlock =
        PsiTreeUtil.getChildrenOfTypeAsList(file, GoFunctionOrMethodDeclaration::class.java).single { it.name == name }.block!!

    private fun result(file: GoFile, name: String) = GoIncrementalChecker.bodyResult(body(file, name))

    private fun texts(file: GoFile, code: String): List<String> =
        semantic.check(file).filter { it.code == code }.map { file.text.substring(it.range.startOffset, it.range.endOffset) }

    private val source = """
        package inc

        import (
            "fmt"
            "strings"
        )

        var Global int

        func G() {
            y := 1
            /*G*/
            _ = y
            fmt.Println()
        }

        func F() {
            x := 1
            /*F*/
            s := strings.ToUpper("a")
            _ = s
        }

        func H() int {
            /*H*/
            return 0
        }

        var bad int = "s"

        var PL = func() int {
            q := 1
            /*PL*/
            return q
        }
    """.trimIndent() + "\n"

    private fun add(text: String = source): GoFile = myFixture.addFileToProject("inc/a.go", text) as GoFile

    fun testBodiesAreTheOutermostBodiesInDocumentOrder() {
        val file = add()
        val bodies = GoIncrementalChecker.bodies(file)
        assertEquals(listOf(body(file, "G"), body(file, "F"), body(file, "H")), bodies.take(3))
        assertEquals("the package-level literal is a unit of its own", 4, bodies.size)
    }

    fun testEditInOneFunctionRecomputesOnlyThatFunction() {
        val file = add()
        assertSplitEqualsMonolithic(file)
        val f = result(file, "F")
        val g = result(file, "G")
        val h = result(file, "H")
        val gBody = body(file, "G")

        edit(file, "/*F*/", "_ = 1 + 2")
        assertSplitEqualsMonolithic(file)
        assertSame("G's body PSI survives an edit in F", gBody, body(file, "G"))
        assertSame("an edit in F keeps G's result", g, result(file, "G"))
        assertSame("an edit in F keeps H's result", h, result(file, "H"))
        assertNotSame("an edit in F recomputes F's result", f, result(file, "F"))

        val f2 = result(file, "F")
        edit(file, "/*G*/", "_ = 3")
        assertSplitEqualsMonolithic(file)
        assertSame("an edit in G keeps F's result", f2, result(file, "F"))
        assertNotSame("an edit in G recomputes G's result", g, result(file, "G"))
    }

    fun testPackageLevelEditRecomputesEveryBody() {
        val file = add()
        assertSplitEqualsMonolithic(file)
        val f = result(file, "F")
        val g = result(file, "G")
        edit(file, "var Global int", "var Global string")
        assertSplitEqualsMonolithic(file)
        assertNotSame(f, result(file, "F"))
        assertNotSame(g, result(file, "G"))
    }

    fun testUnusedImportFollowsTheLastUseInABody() {
        val file = add()
        assertEquals(emptyList<String>(), texts(file, "unused-import"))
        val g = result(file, "G")
        edit(file, "s := strings.ToUpper(\"a\")", "s := \"a\"")
        assertEquals(listOf("\"strings\""), texts(file, "unused-import"))
        assertSplitEqualsMonolithic(file)
        assertSame("G's result is reused", g, result(file, "G"))
        edit(file, "s := \"a\"", "s := strings.ToLower(\"a\")")
        assertEquals(emptyList<String>(), texts(file, "unused-import"))
        assertSplitEqualsMonolithic(file)
    }

    fun testUnusedVariableInAFunctionStaysCorrectAfterAnEditInAnother() {
        val file = add()
        assertEquals(listOf("x"), texts(file, "unused-variable"))
        val f = result(file, "F")
        // G precedes F: the edit moves F and every cached range after it.
        edit(file, "/*G*/", "z := 2\n    _ = z\n    /*G2*/")
        assertSame(f, result(file, "F"))
        assertEquals(listOf("x"), texts(file, "unused-variable"))
        assertEquals("package-level diagnostics move with the text", listOf("\"s\""), texts(file, "assignability"))
        assertSplitEqualsMonolithic(file)
        edit(file, "/*G2*/", "w := 3")
        assertEquals(listOf("w", "x"), texts(file, "unused-variable"))
        assertSplitEqualsMonolithic(file)
    }

    fun testMissingReturnAndPackageLevelLiteral() {
        val file = add()
        assertEquals(emptyList<String>(), texts(file, "missing-return"))
        edit(file, "/*PL*/", "if q > 0 { return 1 }\n    /*PL2*/")
        assertSplitEqualsMonolithic(file)
        edit(file, "return q\n", "\n")
        assertEquals(listOf("}"), texts(file, "missing-return"))
        assertSplitEqualsMonolithic(file)
        edit(file, "/*H*/\n    return 0", "/*H*/")
        assertEquals(listOf("}", "}"), texts(file, "missing-return"))
        assertSplitEqualsMonolithic(file)
    }

    fun testInitCycleThroughABodyFollowsBodyEdits() {
        val file = add(
            """
            package cyc

            var a = f()

            func f() int {
                return /*R*/0
            }

            func other() {
                /*O*/
            }
            """.trimIndent() + "\n",
        )
        assertEquals(emptyList<String>(), texts(file, "init-cycle"))
        edit(file, "/*R*/0", "a")
        assertEquals(listOf("a"), texts(file, "init-cycle"))
        assertSplitEqualsMonolithic(file)
        edit(file, "/*O*/", "_ = 1")
        assertEquals(listOf("a"), texts(file, "init-cycle"))
        edit(file, "return a", "return 1")
        assertEquals(emptyList<String>(), texts(file, "init-cycle"))
        assertSplitEqualsMonolithic(file)
    }
}
