package io.github.golangsupport.ide.annotator

import com.intellij.lang.annotation.HighlightSeverity
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.highlighting.GoColorSettingsPage
import io.github.golangsupport.ide.highlighting.GoHighlightingColors

/**
 * Semantic highlighting: every identifier of `testData/highlighting/semantic.go` with the
 * attributes key the annotator assigns, as a golden list (`line:column text KEY`).
 */
class GoSemanticHighlightingTest : GoSemanticIdeTestBase() {
    override val testDataSubdir: String = "highlighting"

    fun testIdentifierKinds() {
        myFixture.configureByFile("semantic.go")
        val document = myFixture.editor.document
        val infos = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.INFORMATION && it.forcedTextAttributesKey?.externalName?.startsWith("GO_") == true }
            .sortedBy { it.startOffset }
        val actual = infos.joinToString("\n") { info ->
            val line = document.getLineNumber(info.startOffset)
            val column = info.startOffset - document.getLineStartOffset(line)
            "${line + 1}:${column + 1} ${document.charsSequence.subSequence(info.startOffset, info.endOffset)} ${info.forcedTextAttributesKey!!.externalName}"
        }
        assertGolden("semantic.txt", actual)
    }

    fun testUnresolvedIdentifiersAreNotColoured() {
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t_ = unknown\n}\n")
        val keys = myFixture.doHighlighting().filter { myFixture.file.text.substring(it.startOffset, it.endOffset) == "unknown" }
            .mapNotNull { it.forcedTextAttributesKey }
        assertEmpty(keys)
    }

    fun testColorSettingsPageListsSemanticKeys() {
        val page = GoColorSettingsPage()
        val keys = page.attributeDescriptors.map { it.key }.toSet()
        for (key in listOf(GoHighlightingColors.TYPE, GoHighlightingColors.FUNCTION_CALL, GoHighlightingColors.LOCAL_VARIABLE, GoHighlightingColors.BUILTIN_FUNCTION)) {
            assertTrue(key.externalName, key in keys)
        }
        val tags = page.additionalHighlightingTagToDescriptorMap.keys
        val used = Regex("<([a-z]+)>").findAll(page.demoText).map { it.groupValues[1] }.toSet()
        assertEquals(tags, used)
    }
}
