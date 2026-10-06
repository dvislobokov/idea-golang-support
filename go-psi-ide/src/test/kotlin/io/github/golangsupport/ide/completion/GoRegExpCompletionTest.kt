package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.injection.GoRegExpSyntax

/**
 * RE2 escapes in the RegExp injected into `regexp` calls: the platform's RegExp completion reads its rows from
 * [io.github.golangsupport.ide.injection.GoRegExpLanguageHost] ([GoRegExpSyntax]).
 */
class GoRegExpCompletionTest : GoCompletionTestBase() {

    private fun regexp(pattern: String) = lookups("package main\n\nimport \"regexp\"\n\nvar re = regexp.MustCompile($pattern)\n")

    fun testEscapesAfterBackslash() {
        val items = regexp("`\\<caret>`")
        assertContainsAll(items, "d", "D", "w", "s", "b", "A", "z", "x{10FFFF}", "Q", "E", "p{Greek}", "p{Lu}", "p{Han}")
        // no Java-only escapes
        assertContainsNone(items, "Z", "G", "R", "h", "p{javaLowerCase}", "p{IsAlphabetic}")
        assertEquals("digits (== [0-9])", presentation("d").typeText)
    }

    fun testInterpretedStringAndMatchString() {
        assertContainsAll(lookups("package main\n\nimport \"regexp\"\n\nvar ok, _ = regexp.MatchString(\"^\\\\<caret>\", \"x\")\n"), "d", "w")
    }

    fun testPropertyNames() {
        assertContainsAll(regexp("`\\p{G<caret>}`"), "Greek", "Georgian", "Gothic")
        // the only match is inserted (the editor is the injected fragment's)
        assertNull(complete("package main\n\nimport \"regexp\"\n\nvar re = regexp.MustCompile(`\\p{Gre<caret>}`)\n"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("\\p{Greek}"))
    }

    fun testTables() {
        assertEquals(GoRegExpSyntax.CHARACTER_CLASSES.size, GoRegExpSyntax.CHARACTER_CLASSES.map { it[0] }.toSet().size)
        assertEquals("Unicode category Lu: uppercase letter", GoRegExpSyntax.propertyDescription("Lu"))
        assertEquals("Unicode script Greek", GoRegExpSyntax.propertyDescription("^Greek"))
        assertNull(GoRegExpSyntax.propertyDescription("javaLowerCase"))
    }
}
