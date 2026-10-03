package io.github.golangsupport

import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import io.github.golangsupport.settings.GoSettings
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

/** The language of the settings pages: the one of the IDE (the Russian language pack switches it), or chosen on Settings | Go. */
enum class PluginLanguage {
    AUTO, ENGLISH, RUSSIAN;

    /**
     * Its own name in its own language, whatever the language of the page. Not `toString()`: the XML serializer matches a stored value
     * against `toString()` of the constants while [GoSettings] is being loaded, and the bundle asks [GoSettings] for the language — the
     * service would wait for itself and nothing that touches the settings would ever start (seen in the dotnet plugin).
     */
    val label: String get() = GoBundle.message("language.$name")
}

/**
 * The texts of the settings pages, in English and in Russian (`messages/GoBundle*.properties`). Not a `DynamicBundle`: that one follows
 * the language of the IDE only, and the plugin is used in IDEs without a language pack as well, so the language is a setting of the
 * plugin with "as in the IDE" as its default. The menus, the actions and the tool windows stay English, as the ones of the IDE around
 * them; the help pages of the plugin (Go | Help Page) are Russian already.
 */
object GoBundle {
    private const val BASE = "messages.GoBundle"
    private val bundles = ConcurrentHashMap<String, ResourceBundle>()

    /** For the tests: the language regardless of the settings. */
    @Volatile var forced: PluginLanguage? = null

    /** `ru` or `en`. */
    fun language(): String {
        val chosen = forced ?: runCatching { ApplicationManager.getApplication()?.let { GoSettings.getInstance().language } }.getOrNull() ?: PluginLanguage.AUTO
        return when (chosen) {
            PluginLanguage.ENGLISH -> "en"
            PluginLanguage.RUSSIAN -> "ru"
            PluginLanguage.AUTO -> if (runCatching { DynamicBundle.getLocale().language }.getOrNull() == "ru") "ru" else "en"
        }
    }

    fun isRussian(): Boolean = language() == "ru"

    /** No fallback to the locale of the machine: English is the file without a suffix, whatever the machine speaks. */
    fun bundle(language: String): ResourceBundle = bundles.computeIfAbsent(language) {
        ResourceBundle.getBundle(
            BASE, if (it == "ru") Locale.of("ru") else Locale.ROOT, GoBundle::class.java.classLoader,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )
    }

    /** `{0}`, `{1}` are replaced as they are: no `MessageFormat`, so an apostrophe of a text needs no doubling. */
    fun message(key: String, vararg parameters: Any?): String = format(find(key) ?: "!$key!", parameters)

    /** The text of [key], or [fallback] where the key is not there (the options of gopls have their English in the catalogue of the server). */
    fun messageOr(key: String, fallback: String, vararg parameters: Any?): String = format(find(key) ?: fallback, parameters)

    private fun find(key: String): String? = try {
        bundle(language()).getString(key)
    } catch (_: MissingResourceException) {
        null
    }

    private fun format(text: String, parameters: Array<out Any?>): String =
        parameters.foldIndexed(text) { index, result, parameter -> result.replace("{$index}", parameter?.toString().orEmpty()) }
}
