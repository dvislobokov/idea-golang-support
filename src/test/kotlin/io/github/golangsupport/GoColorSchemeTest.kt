package io.github.golangsupport

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoColors
import io.github.golangsupport.lang.GoSyntaxHighlighter
import java.awt.Color

/** The colours of GoLand reach every bundled scheme: the dark ones through Darcula, the light ones through Default. */
class GoColorSchemeTest : BasePlatformTestCase() {
    fun testTheColoursOfGoAreInEveryBundledScheme() {
        val keys = mapOf(
            GoSyntaxHighlighter.PACKAGE to (Color(0xAFBF7E) to Color(0x6B7A1F)),
            GoSyntaxHighlighter.TYPE_REFERENCE to (Color(0x6FAFBD) to Color(0x007E8A)),
            GoSyntaxHighlighter.FUNCTION_CALL to (Color(0x57AAF7) to Color(0x00627A)),
            GoSyntaxHighlighter.FIELD to (Color(0xC77DBB) to Color(0x871094)),
        )
        val problems = ArrayList<String>()
        for (scheme in EditorColorsManager.getInstance().allSchemes) {
            val dark = com.intellij.ui.ColorUtil.isDark(scheme.defaultBackground)
            for ((key, colours) in keys) {
                val expected = if (dark) colours.first else colours.second
                val actual = scheme.getAttributes(key)?.foregroundColor
                if (actual != expected) problems += "${scheme.name}: ${key.externalName} is ${actual?.let { Integer.toHexString(it.rgb and 0xffffff) }}, expected ${Integer.toHexString(expected.rgb and 0xffffff)}"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * A scheme derived from Darcula that sets the generic keys itself, as Islands Dark does (seen live: calls, packages and types stayed
     * white there while Darcula had the colours). Whatever such a scheme says about "a function call", Go keeps its own colour.
     */
    fun testADerivedSchemeWithItsOwnGenericColoursDoesNotHideTheColoursOfGo() {
        val darcula = EditorColorsManager.getInstance().getScheme("Darcula")
        val derived = com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl(darcula)
        val plain = com.intellij.openapi.editor.markup.TextAttributes()
        for (generic in listOf(Default.FUNCTION_CALL, Default.IDENTIFIER, Default.CLASS_REFERENCE, Default.INSTANCE_FIELD)) derived.setAttributes(generic, plain)
        assertEquals(Color(0x57AAF7), derived.getAttributes(GoSyntaxHighlighter.FUNCTION_CALL)?.foregroundColor)
        assertEquals(Color(0xAFBF7E), derived.getAttributes(GoSyntaxHighlighter.PACKAGE)?.foregroundColor)
        assertEquals(Color(0x6FAFBD), derived.getAttributes(GoSyntaxHighlighter.TYPE_REFERENCE)?.foregroundColor)
        assertEquals(Color(0xC77DBB), derived.getAttributes(GoSyntaxHighlighter.FIELD)?.foregroundColor)
    }

    /** The keys of GoLand inherit the colours Go had before them, and take GoLand's own where it differs (Islands Dark). */
    fun testTheKeysOfGoLandInheritTheBaseColoursOrTakeGoLandsOwn() {
        val darcula = EditorColorsManager.getInstance().getScheme("Darcula")!!
        fun fg(key: com.intellij.openapi.editor.colors.TextAttributesKey) = darcula.getAttributes(key)?.foregroundColor
        assertEquals(Color(0x57AAF7), fg(GoColors.EXPORTED_FUNCTION_CALL))
        assertEquals(Color(0x56A8F5), fg(GoColors.LOCAL_FUNCTION))
        assertEquals(Color(0xC77DBB), fg(GoColors.STRUCT_EXPORTED_MEMBER))
        assertEquals(Color(0xC77DBB), fg(GoColors.PACKAGE_LOCAL_CONSTANT))
        assertEquals(Color(0x6FAFBD), fg(GoColors.LOCAL_STRUCT_REFERENCE))
        assertEquals(Color(0x4EADE5), fg(GoColors.METHOD_RECEIVER))
        assertEquals(Color(0x7DBDA2), fg(GoColors.SHADOWING_VARIABLE))
        assertEquals(Color(0x6F737A), fg(GoColors.TAG_TEXT))
        assertEquals(com.intellij.openapi.editor.markup.EffectType.LINE_UNDERSCORE, darcula.getAttributes(GoColors.REASSIGNMENT_IN_SHORT_VAR_DECLARATION)?.effectType)
        assertEquals(darcula.getAttributes(GoColors.VALID_STRING_ESCAPE), darcula.getAttributes(GoColors.FORMAT_VERB))
    }
}
