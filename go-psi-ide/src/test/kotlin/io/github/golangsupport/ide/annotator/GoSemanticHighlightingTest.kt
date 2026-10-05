package io.github.golangsupport.ide.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoColors

/**
 * Semantic highlighting: every identifier of `testData/highlighting/semantic.go` with the
 * attributes key the annotator assigns, as a golden list (`line:column text KEY`). Keys are
 * the plugin palette (`GoColors`); the colour page and schemes are the root module's.
 */
class GoSemanticHighlightingTest : GoSemanticIdeTestBase() {
    override val testDataSubdir: String = "highlighting"

    fun testIdentifierKinds() = checkGolden("semantic")

    /**
     * The keys of GoLand (`docs/goland-analysis/dumps/color-keys-go.txt`): exported / local declarations and calls, struct / interface
     * types, receiver apart from parameters, scope and reassigned-in-`:=` variables, calls of func-valued variables and fields, `nil`,
     * doc comment references, and the string contents (tag parts, printf verbs, escapes) of [GoStringContentAnnotator].
     */
    fun testGoLandKeys() = checkGolden("goland")

    /**
     * G10, the second GoLand probe (`highlight-internal-probe2-style.txt`): every resolving word of a doc comment (`time` and `Duration`
     * of `time.Duration`, not a bare `time` or `iota`), the shadowing variable's declaration and uses, `case n := <-ch` a local variable.
     */
    fun testGoLandProbe2() = checkGolden("g10")

    private fun checkGolden(name: String) {
        myFixture.configureByFile("$name.go")
        val document = myFixture.editor.document
        val infos = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.INFORMATION && it.forcedTextAttributesKey?.externalName?.startsWith("GO_") == true }
            .sortedWith(compareBy({ it.startOffset }, { it.endOffset }, { it.forcedTextAttributesKey!!.externalName }))
        val actual = infos.joinToString("\n") { info ->
            val line = document.getLineNumber(info.startOffset)
            val column = info.startOffset - document.getLineStartOffset(line)
            "${line + 1}:${column + 1} ${document.charsSequence.subSequence(info.startOffset, info.endOffset)} ${info.forcedTextAttributesKey!!.externalName}"
        }
        assertGolden("$name.txt", actual)
        // the very key objects of the palette, not look-alikes with the same external name
        val palette = GoColors::class.java.declaredFields.filter { it.type == TextAttributesKey::class.java }.map { it.isAccessible = true; it.get(null) }.toSet()
        for (info in infos) assertTrue(info.forcedTextAttributesKey!!.externalName, info.forcedTextAttributesKey in palette)
    }

    fun testUnresolvedIdentifiersAreNotColoured() {
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t_ = unknown\n}\n")
        val keys = myFixture.doHighlighting().filter { myFixture.file.text.substring(it.startOffset, it.endOffset) == "unknown" }
            .mapNotNull { it.forcedTextAttributesKey }
        assertEmpty(keys)
    }
}
