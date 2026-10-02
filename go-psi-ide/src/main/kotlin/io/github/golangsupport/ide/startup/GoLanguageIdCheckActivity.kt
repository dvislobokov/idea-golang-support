package io.github.golangsupport.ide.startup

import com.intellij.lang.LanguageExtensionPoint
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logs a warning, once per IDE session, naming plugins that register extensions with `language="go"` instead
 * of `"Go"`. Reads the (lazy) bean descriptors of a fixed list of language-keyed extension points; nothing is
 * instantiated, so the cost is a few list reads.
 */
@ApiStatus.Internal
class GoLanguageIdCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!done.compareAndSet(false, true)) return
        val registrations = ArrayList<Pair<String, String?>>()
        for (epName in GoLanguageIdCheck.EXTENSION_POINTS) {
            val extensions = runCatching { ExtensionPointName.create<Any>(epName).extensionList }.getOrNull() ?: continue
            for (extension in extensions) {
                if (extension !is LanguageExtensionPoint<*>) continue
                val plugin = extension.pluginDescriptor
                if (plugin.pluginId.idString == OWN_ID) continue
                registrations += (plugin.name ?: plugin.pluginId.idString) to extension.language
            }
        }
        for (name in GoLanguageIdCheck.offenders(registrations)) {
            LOG.warn(
                "Plugin '$name' registers extensions with language=\"go\", but the id of the Go language is \"Go\" " +
                    "(case-sensitive); those extensions are never used. Use language=\"Go\".",
            )
        }
    }

    private companion object {
        const val OWN_ID = "io.github.golangsupport"
        val LOG = Logger.getInstance(GoLanguageIdCheckActivity::class.java)
        val done = AtomicBoolean(false)
    }
}
