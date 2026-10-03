package messages

import io.github.golangsupport.GoBundle
import java.util.Collections
import java.util.Enumeration
import java.util.ResourceBundle

/**
 * The titles of the Settings | Go pages for plugin.xml (`bundle="messages.GoSettingsTitles"`, `key="page.…"`). The platform demands a
 * display name in the descriptor (seen live: PluginException otherwise) and reads it through a resource bundle of the IDE's locale; a class
 * bundle answers in the language chosen in the plugin's settings instead, the same as the pages themselves ([GoBundle]).
 */
class GoSettingsTitles : ResourceBundle() {
    override fun handleGetObject(key: String): Any = GoBundle.message(key)

    override fun getKeys(): Enumeration<String> = Collections.enumeration(GoBundle.bundle("en").keySet())
}
