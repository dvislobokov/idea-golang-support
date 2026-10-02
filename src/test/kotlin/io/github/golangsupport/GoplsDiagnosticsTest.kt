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
    fun aRangeInsideTheFileFits() {
        assertTrue(GoplsDiagnosticsSupport.fits(TextRange(0, 0), 0))
        assertTrue(GoplsDiagnosticsSupport.fits(TextRange(135160, 135163), 135163))
    }
}
