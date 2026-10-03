package io.github.golangsupport

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoInlineColors
import io.github.golangsupport.lang.GoInlineColors.Names
import io.github.golangsupport.lang.GoSyntaxHighlighter
import java.awt.Color

/** The grey text in the colours of the code ([GoInlineColors]): the runs and their keys, the blend, the attributes from a scheme. */
class GoInlineColorsTest : BasePlatformTestCase() {
    private fun keys(text: String, before: String = "", names: Names = Names.NONE): List<Pair<String, String?>> =
        GoInlineColors.runs(text, before, names).also { runs -> assertEquals(text, runs.joinToString("") { it.text }) }.map { it.text to it.key?.externalName }

    fun testErrorCheck() {
        val runs = keys("if err != nil {\n\treturn err\n}", names = Names(locals = setOf("err")))
        assertEquals(
            listOf(
                "if " to "GO_KEYWORD", "err " to "GO_LOCAL_VARIABLE", "!= " to "GO_OPERATOR", "nil " to "GO_BUILTIN_CONSTANT",
                "{\n\t" to "GO_BRACES", "return " to "GO_KEYWORD", "err\n" to "GO_LOCAL_VARIABLE", "}" to "GO_BRACES",
            ),
            runs,
        )
    }

    fun testMakeWithTheTypedPrefix() {
        val runs = keys("e([]string, 0, len(keys))", before = "    arr := mak", names = Names(parameters = setOf("keys")))
        assertEquals("e" to "GO_BUILTIN_FUNCTION", runs[0])
        assertTrue(runs.toString(), "string" to "GO_BUILTIN_TYPE" in runs)
        assertTrue(runs.toString(), "0" to "GO_NUMBER" in runs)
        assertTrue(runs.toString(), "len" to "GO_BUILTIN_FUNCTION" in runs)
        assertTrue(runs.toString(), "keys" to "GO_PARAMETER" in runs)
    }

    fun testQualifiedType() {
        val runs = keys(" strings.Builder", before = "\tvar sb", names = Names(packages = setOf("strings")))
        assertEquals(listOf(" strings" to "GO_PACKAGE", "." to "GO_DOT", "Builder" to "GO_TYPE_REFERENCE"), runs)
    }

    fun testCallsStringsFieldsAndKeys() {
        val runs = keys("fmt.Errorf(\"load %s: %w\", u.Name, err)", names = Names(packages = setOf("fmt"), locals = setOf("u", "err")))
        assertEquals("fmt" to "GO_PACKAGE", runs[0])
        assertTrue(runs.toString(), "Errorf" to "GO_FUNCTION_CALL" in runs)
        assertTrue(runs.toString(), "\"load %s: %w\"" to "GO_STRING" in runs)
        assertTrue(runs.toString(), "Name" to "GO_FIELD" in runs)
        val literal = keys("Name: name, Email: email", before = "    u := User{")
        assertEquals("Name" to "GO_FIELD", literal[0])
        assertTrue(literal.toString(), "Email" to "GO_FIELD" in literal)
        val switch = keys("switch c {\ncase Red:\n\treturn \"Red\"\n}")
        assertFalse(switch.toString(), "Red" to "GO_FIELD" in switch)
    }

    fun testUnknownNamesAreText() {
        val runs = keys("x")
        assertEquals(listOf("x" to DefaultLanguageHighlighterColors.IDENTIFIER.externalName), runs)
    }

    fun testBlend() {
        assertEquals(Color(100, 50, 0), GoInlineColors.blend(Color(200, 100, 0), Color(0, 0, 0), 0.5))
        assertEquals(Color(200, 100, 0), GoInlineColors.blend(Color(200, 100, 0), Color(0, 0, 0), 0.0))
        assertEquals(Color(10, 20, 30), GoInlineColors.blend(Color(200, 100, 0), Color(10, 20, 30), 1.0))
    }

    fun testAttributesAreMutedTowardsTheBackground() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val keyword = GoInlineColors.attributes(scheme, GoSyntaxHighlighter.KEYWORD)
        val own = scheme.getAttributes(GoSyntaxHighlighter.KEYWORD)?.foregroundColor ?: scheme.defaultForeground
        assertEquals(GoInlineColors.blend(own, scheme.defaultBackground), keyword.foregroundColor)
        assertEquals(scheme.getAttributes(DefaultLanguageHighlighterColors.INLINE_SUGGESTION)?.foregroundColor, GoInlineColors.attributes(scheme, null).foregroundColor)
    }
}
