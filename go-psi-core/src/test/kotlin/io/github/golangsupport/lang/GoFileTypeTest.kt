package io.github.golangsupport.lang

import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.psi.GoFile

class GoFileTypeTest : GoCodeInsightTestBase() {

    fun testGoFileIsRecognized() {
        val file = myFixture.configureByText("main.go", "package main // c\n")
        assertTrue("Expected GoFile, got ${file.javaClass.name}", file is GoFile)
        assertSame(GoFileType, file.fileType)
        assertSame(GoLanguage, file.language)
    }
}
