package io.github.golangsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.sdk.GoFileTypeCheck
import io.github.golangsupport.sdk.GoFileTypeNotificationProvider

/** A user association that shadows ours (a leftover of another Go plugin, a .go opened as text before the install): seen, bannered, claimed back. */
class GoFileTypeCheckTest : BasePlatformTestCase() {
    fun testAHijackedAssociationIsSeenBanneredAndClaimedBack() {
        val ftm = FileTypeManager.getInstance()
        assertEquals("nothing is off to begin with", emptyList<String>(), GoFileTypeCheck.wrongClaims().map { it.what })
        val go = myFixture.addFileToProject("a.go", "package a\n").virtualFile
        val mod = myFixture.addFileToProject("go.mod", "module a\n").virtualFile
        assertFalse(GoFileTypeCheck.isMistyped(go))
        assertNull(GoFileTypeNotificationProvider().collectNotificationData(project, go))

        ApplicationManager.getApplication().runWriteAction {
            ftm.associateExtension(PlainTextFileType.INSTANCE, GoFileType.defaultExtension)
            ftm.associatePattern(PlainTextFileType.INSTANCE, GoModFileType.GO_MOD)
        }
        try {
            assertEquals(listOf("*.go", GoModFileType.GO_MOD), GoFileTypeCheck.wrongClaims().map { it.what })
            assertTrue(GoFileTypeCheck.isMistyped(go))
            assertTrue(GoFileTypeCheck.isMistyped(mod))
            assertNotNull("the banner above the file", GoFileTypeNotificationProvider().collectNotificationData(project, go))

            GoFileTypeCheck.fix(project)
            assertEquals(emptyList<String>(), GoFileTypeCheck.wrongClaims().map { it.what })
            assertEquals(GoFileType, go.fileType)
            assertEquals(GoModFileType, mod.fileType)
            assertFalse(GoFileTypeCheck.isMistyped(go))
        } finally {
            // the light project and the file type manager are shared: leave them as found
            ApplicationManager.getApplication().runWriteAction {
                ftm.associateExtension(GoFileType, GoFileType.defaultExtension)
                ftm.associatePattern(GoModFileType, GoModFileType.GO_MOD)
            }
        }
    }
}
