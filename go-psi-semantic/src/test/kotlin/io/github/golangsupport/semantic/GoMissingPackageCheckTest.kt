package io.github.golangsupport.semantic

import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.check.GoChecker

/**
 * `cannot find package "p"` on the path of an import no directory provides (seen live 2026-10-08: an import of a module missing
 * from go.mod was not marked at all). Fixture `testData/project/missingpkg`: a module without requirements.
 */
class GoMissingPackageCheckTest : GoProjectModelTestBase() {

    private fun goFile(relative: String): GoFile = PsiManager.getInstance(project).findFile(fixture(relative)) as GoFile

    private fun render(file: GoFile, list: List<GoDiagnostic>): List<String> =
        list.map { "[${it.code}] ${it.message} at '${file.text.substring(it.range.startOffset, it.range.endOffset)}'" }

    fun testUnresolvedImportsAreReportedOnThePath() {
        val file = goFile("missingpkg/main.go")
        val split = render(file, GoSemanticService.getInstance(project).check(file))
        assertEquals(
            listOf(
                "[missing-package] cannot find package \"github.com/labstack/echo/v5\" at '\"github.com/labstack/echo/v5\"'",
                "[missing-package] cannot find package \"nosuch/stdlike\" at '\"nosuch/stdlike\"'",
                "[missing-package] cannot find package \"example.com/missingpkg/nothere\" at '\"example.com/missingpkg/nothere\"'",
            ),
            split,
        )
        // the per-body assembly equals the single walk
        assertEquals(split, render(file, GoChecker(project, file).checkMonolithic()))
    }

    fun testCgoPseudoPackageIsNotReported() {
        val file = goFile("missingpkg/cgo.go")
        assertEquals(emptyList<String>(), render(file, GoSemanticService.getInstance(project).check(file)).filter { "missing-package" in it })
    }

    fun testStdAndProjectPackagesAreNotReported() {
        val file = goFile("simple/main.go")
        assertEquals(emptyList<String>(), render(file, GoSemanticService.getInstance(project).check(file)).filter { "missing-package" in it })
    }

    fun testLoneFileReportsOnlyStdLikePaths() {
        // no go.mod: a path outside the standard library is not modelled (GOPATH-style code), a std-like one is checked against GOROOT
        val file = myFixture.addFileToProject(
            "lone/a.go",
            "package lone\n\nimport (\n\t\"fmt\"\n\t\"example.com/far/away\"\n\t\"nosuch/pkg\"\n)\n\nvar _ = fmt.Sprint(away.X, pkg.Y)\n",
        ) as GoFile
        assertEquals(
            listOf("[missing-package] cannot find package \"nosuch/pkg\" at '\"nosuch/pkg\"'"),
            render(file, GoSemanticService.getInstance(project).check(file)),
        )
    }
}
