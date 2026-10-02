package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * No false positives: every inspection, with its default options, over real GOROOT files
 * (linux/amd64, the pinned toolchain) reports nothing. The files are clean in the checker corpus
 * gate (`testData/metrics/goroot-src-check.json`).
 */
class GoInspectionsGorootTest : GoSemanticIdeTestBase() {

    fun testNoProblemsInGorootFiles() {
        val tools = GoInspectionClasses.ALL.map { it.getDeclaredConstructor().newInstance() }
        val manager = InspectionManager.getInstance(project)
        val problems = ArrayList<String>()
        for (relative in FILES) {
            val path = goroot().resolve("src").resolve(relative)
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("missing $path")
            val file = PsiManager.getInstance(project).findFile(vf) as GoFile
            // The checker sees the file inside its GOROOT package (otherwise it would be trivially quiet).
            assertNotNull(relative, GoSemanticService.getInstance(project).packageOf(file))
            for (tool in tools) {
                tool.checkFile(file, manager, false)?.forEach { d ->
                    problems += "$relative:${d.lineNumber + 1}: [${tool.shortName}] ${d.descriptionTemplate}"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Sanity check of the harness: the same path reports a planted error in a GOROOT-like package. */
    fun testHarnessReportsPlantedError() {
        val file = myFixture.addFileToProject("planted/a.go", "package planted\n\nimport \"strings\"\n\nfunc f() int {\n\treturn strings.ToUpper(\"a\")\n}\n") as GoFile
        val problems = GoInspectionClasses.ALL.map { it.getDeclaredConstructor().newInstance() }
            .flatMap { it.checkFile(file, InspectionManager.getInstance(project), false)?.toList().orEmpty() }
        assertEquals(listOf("cannot use strings.ToUpper(\"a\") (value of type string) as int value in return statement"), problems.map { it.descriptionTemplate })
    }

    private companion object {
        val FILES = listOf(
            "strings/strings.go",
            "net/http/server.go",
            "go/types/expr.go",
            "fmt/print.go",
            "bytes/buffer.go",
            "sort/sort.go",
            "encoding/json/decode.go",
            "bufio/bufio.go",
            "sync/mutex.go",
            "errors/wrap.go",
        )
    }
}
