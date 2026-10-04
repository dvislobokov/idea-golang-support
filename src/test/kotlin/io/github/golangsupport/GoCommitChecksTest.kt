package io.github.golangsupport

import io.github.golangsupport.ci.GoCommitChecks
import io.github.golangsupport.ci.GoSarifLevel
import io.github.golangsupport.ci.GoSarifRegion
import io.github.golangsupport.ci.GoSarifResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoCommitChecksTest {
    private fun result(level: GoSarifLevel, uri: String) = GoSarifResult("GoRule", level, "m", uri, GoSarifRegion(1, 1, 1, 2))

    @Test
    fun onlyGoFilesGoWouldBuild() {
        assertTrue(GoCommitChecks.isChecked("main.go"))
        assertTrue(GoCommitChecks.isChecked("internal/store/x_test.go"))
        assertTrue(GoCommitChecks.isChecked("cmd\\app\\main.go"))
        assertFalse(GoCommitChecks.isChecked("go.mod"))
        assertFalse(GoCommitChecks.isChecked("README.md"))
        assertFalse(GoCommitChecks.isChecked("vendor/github.com/x/y.go"))
        assertFalse(GoCommitChecks.isChecked("pkg/testdata/bad.go"))
        assertFalse(GoCommitChecks.isChecked(".tools/gen.go"))
        assertFalse(GoCommitChecks.isChecked("_old/a.go"))
        assertTrue("a file name may start with _ and is still read by this check", GoCommitChecks.isChecked("pkg/_a.go"))
        assertFalse(GoCommitChecks.isChecked(""))
    }

    @Test
    fun weakWarningsDoNotStopTheCommit() {
        assertNull(GoCommitChecks.summary(emptyList()))
        assertNull(GoCommitChecks.summary(listOf(result(GoSarifLevel.NOTE, "a.go"))))
        assertEquals(1, GoCommitChecks.blocking(listOf(result(GoSarifLevel.NOTE, "a.go"), result(GoSarifLevel.WARNING, "a.go"))).size)
    }

    @Test
    fun summaryText() {
        assertEquals("1 warning in 1 Go file", GoCommitChecks.summary(listOf(result(GoSarifLevel.WARNING, "a.go"), result(GoSarifLevel.NOTE, "b.go"))))
        assertEquals("1 error in 1 Go file", GoCommitChecks.summary(listOf(result(GoSarifLevel.ERROR, "a.go"))))
        assertEquals("2 errors and 3 warnings in 2 Go files", GoCommitChecks.summary(listOf(
            result(GoSarifLevel.ERROR, "a.go"), result(GoSarifLevel.ERROR, "b.go"),
            result(GoSarifLevel.WARNING, "a.go"), result(GoSarifLevel.WARNING, "a.go"), result(GoSarifLevel.WARNING, "b.go"),
        )))
    }
}
