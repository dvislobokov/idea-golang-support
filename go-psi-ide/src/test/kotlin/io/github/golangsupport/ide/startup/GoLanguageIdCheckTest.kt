package io.github.golangsupport.ide.startup

import junit.framework.TestCase

class GoLanguageIdCheckTest : TestCase() {
    fun testWrongIdIsOnlyLowercaseGo() {
        assertTrue(GoLanguageIdCheck.isWrongId("go"))
        assertFalse(GoLanguageIdCheck.isWrongId("Go"))
        assertFalse(GoLanguageIdCheck.isWrongId("golang"))
        assertFalse(GoLanguageIdCheck.isWrongId("JAVA"))
        assertFalse(GoLanguageIdCheck.isWrongId(null))
    }

    fun testOffendersNamesPluginsOnceInFirstSeenOrder() {
        val result = GoLanguageIdCheck.offenders(
            listOf(
                "Good" to "Go",
                "Bad" to "go",
                "Other" to "kotlin",
                "Worse" to "go",
                "Bad" to "go",
                "NoLanguage" to null,
            ),
        )
        assertEquals(listOf("Bad", "Worse"), result)
    }

    fun testNothingToReport() {
        assertTrue(GoLanguageIdCheck.offenders(emptyList()).isEmpty())
    }
}
