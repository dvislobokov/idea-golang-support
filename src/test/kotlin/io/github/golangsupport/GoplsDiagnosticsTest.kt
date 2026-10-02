package io.github.golangsupport

import com.intellij.openapi.util.TextRange
import io.github.golangsupport.lsp.GoplsDiagnosticsSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A diagnostic of gopls published for a longer version of the file is dropped, not thrown at (seen live: 135163-character file, range 135910..135917). */
class GoplsDiagnosticsTest {
    @Test
    fun aRangePastTheEndOfTheFileDoesNotFit() {
        assertFalse(GoplsDiagnosticsSupport.fits(TextRange(135910, 135917), 135163))
        assertFalse(GoplsDiagnosticsSupport.fits(TextRange(135160, 135164), 135163))
    }

    @Test
    fun onlyTheSyntaxErrorsOfGoplsYieldToTheParser() {
        assertFalse(GoplsDiagnosticsSupport.accepts("syntax", nativeSyntaxErrors = true, nativeDiagnostics = false))
        assertTrue(GoplsDiagnosticsSupport.accepts("syntax", nativeSyntaxErrors = false, nativeDiagnostics = false))
        assertTrue("type errors stay until the native diagnostics", GoplsDiagnosticsSupport.accepts("compiler", nativeSyntaxErrors = true, nativeDiagnostics = false))
        assertTrue(GoplsDiagnosticsSupport.accepts(null, nativeSyntaxErrors = true, nativeDiagnostics = false))
    }

    @Test
    fun onlyTheCompilerErrorsOfGoplsYieldToTheNativeInspections() {
        assertFalse(GoplsDiagnosticsSupport.accepts("compiler", nativeSyntaxErrors = false, nativeDiagnostics = true))
        assertFalse(GoplsDiagnosticsSupport.accepts("compiler", nativeSyntaxErrors = true, nativeDiagnostics = true))
        assertTrue(GoplsDiagnosticsSupport.accepts("compiler", nativeSyntaxErrors = false, nativeDiagnostics = false))
        assertTrue("the syntax switch is its own", GoplsDiagnosticsSupport.accepts("syntax", nativeSyntaxErrors = false, nativeDiagnostics = true))
        assertTrue(GoplsDiagnosticsSupport.accepts(null, nativeSyntaxErrors = true, nativeDiagnostics = true))
    }

    /** The analyzers of gopls and the load problems of the module have no native counterpart: they stay whatever the switches say. */
    @Test
    fun analyzersAndLoadProblemsStayInEveryCombination() {
        for (source in listOf("printf", "unusedparams", "SA1019", "ST1000", "unusedvariable", "go list", "go mod tidy")) {
            for (syntax in listOf(false, true)) for (diagnostics in listOf(false, true)) {
                assertTrue("$source syntax=$syntax diagnostics=$diagnostics", GoplsDiagnosticsSupport.accepts(source, syntax, diagnostics))
            }
        }
    }

    @Test
    fun aRangeInsideTheFileFits() {
        assertTrue(GoplsDiagnosticsSupport.fits(TextRange(0, 0), 0))
        assertTrue(GoplsDiagnosticsSupport.fits(TextRange(135160, 135163), 135163))
    }
}
