package io.github.golangsupport

import io.github.golangsupport.build.GoBuildProblems
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the editor shows the messages of the last build: without the server, or when gopls no longer underlines compiler errors (step 8g). */
class GoBuildProblemsTest {
    @Test
    fun withoutTheLanguageServerTheBuildMessagesShow() {
        assertTrue(GoBuildProblems.shouldShow(noLanguageServerModule = true, languageServerEnabled = true, nativeDiagnostics = false))
        assertTrue(GoBuildProblems.shouldShow(noLanguageServerModule = false, languageServerEnabled = false, nativeDiagnostics = false))
    }

    @Test
    fun withGoplsOnDutyForDiagnosticsTheyDoNot() {
        assertFalse(GoBuildProblems.shouldShow(noLanguageServerModule = false, languageServerEnabled = true, nativeDiagnostics = false))
    }

    @Test
    fun withTheNativeDiagnosticsTheyComeBack() {
        assertTrue(GoBuildProblems.shouldShow(noLanguageServerModule = false, languageServerEnabled = true, nativeDiagnostics = true))
    }
}
