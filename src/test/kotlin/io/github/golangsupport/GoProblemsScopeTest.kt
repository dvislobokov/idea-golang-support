package io.github.golangsupport

import io.github.golangsupport.ci.GoSarifLevel
import io.github.golangsupport.problems.GoFinding
import io.github.golangsupport.problems.GoProblemsScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the background analysis of the project reads, by path: pure, no platform. */
class GoProblemsScopeTest {
    @Test fun goFilesAndModulesOfTheProject() =
        assertTrue(listOf("main.go", "pkg/a.go", "go.mod", "go.work", "svc/api/go.mod", "svc/api/h.go").all(GoProblemsScope::isAnalysedPath))

    @Test fun whatGoItselfSkips() =
        assertFalse(listOf("vendor/x/x.go", "pkg/testdata/t.go", ".git/x.go", "_old/o.go", "web/node_modules/a/b.go", "a/.cache/go.mod").any(GoProblemsScope::isAnalysedPath))

    @Test fun otherFilesAndEmptyPaths() =
        assertFalse(listOf("README.md", "frontend/app.js", "build/out.txt", "", "/", "pkg/").any(GoProblemsScope::isAnalysedPath))

    @Test fun aDirectoryNamedLikeAGoFileIsADirectory() = assertTrue(GoProblemsScope.isAnalysedPath("x.go/y.go"))

    @Test fun importPathFromTheModule() {
        assertEquals("example.com/shop", GoProblemsScope.importPath("example.com/shop", ""))
        assertEquals("example.com/shop/store/db", GoProblemsScope.importPath("example.com/shop", "store/db"))
    }

    @Test fun errorsOnlyWhenWarningsAreOff() {
        val error = GoFinding(0, 0, GoSarifLevel.ERROR, "GoUnresolvedReference", "Unresolved reference", "undefined: x")
        val warning = error.copy(level = GoSarifLevel.WARNING)
        val weak = error.copy(level = GoSarifLevel.NOTE)
        assertEquals(listOf(true, false, false), listOf(error, warning, weak).map { it.shown(includeWarnings = false) })
        assertEquals(listOf(true, true, true), listOf(error, warning, weak).map { it.shown(includeWarnings = true) })
        assertEquals("undefined: x (Unresolved reference)", error.text)
    }
}
