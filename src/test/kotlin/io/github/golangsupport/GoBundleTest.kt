package io.github.golangsupport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/** The two files of the bundle have to say the same things: a key of one and not of the other is a text that stays English, or `!key!`. */
class GoBundleTest {
    private fun load(name: String): Properties = Properties().apply {
        GoBundleTest::class.java.classLoader.getResourceAsStream(name)!!.reader(Charsets.UTF_8).use { load(it) }
    }

    private val english = load("messages/GoBundle.properties")
    private val russian = load("messages/GoBundle_ru.properties")

    @Test fun theSameKeysBothWays() {
        assertEquals("keys of the English file that the Russian one has not", emptySet<Any>(), english.keys - russian.keys)
        assertEquals("keys of the Russian file that the English one has not", emptySet<Any>(), russian.keys - english.keys)
    }

    @Test fun noEmptyTexts() {
        for ((name, properties) in listOf("English" to english, "Russian" to russian)) {
            for ((key, value) in properties) assertTrue("$name: $key is empty", value.toString().isNotBlank())
        }
    }

    /** `{0}` and `{1}` are replaced by position: a translation that lost one would show an empty place instead of the path. */
    @Test fun theSamePlaceholders() {
        val placeholder = Regex("""\{\d}""")
        for (key in english.keys) {
            val inEnglish = placeholder.findAll(english.getProperty(key as String)).map { it.value }.toSortedSet()
            val inRussian = placeholder.findAll(russian.getProperty(key)).map { it.value }.toSortedSet()
            assertEquals("$key: the placeholders differ", inEnglish, inRussian)
        }
    }

    /** The language of the page follows the setting; the bundle must give a text for both languages of every key. */
    @Test fun bothLanguagesAnswer() {
        try {
            for (language in listOf(PluginLanguage.ENGLISH, PluginLanguage.RUSSIAN)) {
                GoBundle.forced = language
                for (key in english.keys) {
                    val text = GoBundle.message(key as String)
                    assertFalse("$language: no text for $key", text.startsWith("!"))
                }
            }
            GoBundle.forced = PluginLanguage.RUSSIAN
            assertTrue(GoBundle.isRussian())
            assertEquals("Отладчик", GoBundle.message("page.debugger"))
            GoBundle.forced = PluginLanguage.ENGLISH
            assertEquals("Debugger", GoBundle.message("page.debugger"))
            // the enums keep their English in the settings file and change only what the combo shows
            assertEquals("Package directory", io.github.golangsupport.settings.GoDebugBinaryLocation.PACKAGE.toString())
            GoBundle.forced = PluginLanguage.RUSSIAN
            assertEquals("В каталог пакета", io.github.golangsupport.settings.GoDebugBinaryLocation.PACKAGE.label)
        } finally {
            GoBundle.forced = null
        }
    }
}
