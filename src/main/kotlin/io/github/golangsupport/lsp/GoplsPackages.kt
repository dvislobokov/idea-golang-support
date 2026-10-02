package io.github.golangsupport.lsp

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import io.github.golangsupport.GoIcons
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.GoCompletionOrder
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoPrefixMatcher
import io.github.golangsupport.lang.GoSemanticColors
import io.github.golangsupport.lang.GoTokenTypes
import io.github.golangsupport.settings.GoSettings
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The packages a file may import and does not: the standard library, the modules of go.mod, the packages of the workspace, as gopls
 * lists them (`gopls.list_known_packages`). Kept per directory, which is a package: the list changes with go.mod and with the imports
 * of the file, not with every key, so what was asked a while ago is given at once and asked again behind it.
 */
@Service(Service.Level.PROJECT)
class GoplsKnownPackages {
    private class Entry(val at: Long, val packages: List<String>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap<String, AtomicBoolean>()

    fun of(client: LspClient, file: VirtualFile): List<String> {
        val key = file.parent?.path ?: return emptyList()
        val entry = entries[key] ?: return ask(client, file)?.also { entries[key] = Entry(System.currentTimeMillis(), it) }.orEmpty()
        if (System.currentTimeMillis() - entry.at > FRESH_MS && refreshing.computeIfAbsent(key) { AtomicBoolean() }.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    ask(client, file)?.let { entries[key] = Entry(System.currentTimeMillis(), it) }
                } finally {
                    refreshing[key]?.set(false)
                }
            }
        }
        return entry.packages
    }

    private fun ask(client: LspClient, file: VirtualFile): List<String>? = try {
        val arguments = listOf<Any>(mapOf("URI" to client.getDocumentIdentifier(file).uri))
        val result = client.sendRequestSync<Any?>(TIMEOUT_MS) { it.workspaceService.executeCommand(ExecuteCommandParams(COMMAND, arguments)) }
        GoplsCommandArguments.json(result)?.getAsJsonArray("Packages")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val COMMAND = "gopls.list_known_packages"
        private const val TIMEOUT_MS = 1_500
        private const val FRESH_MS = 15_000L

        fun getInstance(project: Project): GoplsKnownPackages = project.service()
    }
}

/**
 * The names of packages that are not imported, in the completion list: `htt` gives `http` of `net/http`, and choosing it writes the
 * import. gopls completes `http.Cl` of a package that is not imported, but not the name of the package itself (checked with
 * tools/gopls/completion.py), so until the name was typed in full there was nothing in the list to choose (reported by the user).
 */
class GoplsPackageCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? GoFile ?: return
        if (GoFeatures.native(GoFeature.COMPLETION, file.project)) return
        if (!GoSettings.getInstance().completionUnimportedPackages) return
        val virtualFile = file.virtualFile ?: return
        if (parameters.position.node?.elementType != GoTokenTypes.IDENTIFIER) return
        val text = parameters.editor.document.immutableCharSequence
        val typed = GoCompletionOrder.typed(text, parameters.offset)
        if (typed.isEmpty() || !GoImports.isPackagePlace(text, parameters.offset - typed.length)) return
        val client = Gopls.client(file.project) ?: return
        val matcher = GoPrefixMatcher(typed)
        val names = result.withPrefixMatcher(matcher)
        for (path in GoplsKnownPackages.getInstance(file.project).of(client, virtualFile)) {
            val name = GoSemanticColors.packageName(path)
            if (!name.startsWith(matcher.latin, ignoreCase = true)) continue
            val item = LookupElementBuilder.create(path, name).withIcon(GoIcons.Package).withTailText("  $path", true).withTypeText("import", true).withInsertHandler(IMPORT)
            // below the names of the file that begin the same way, above what gopls has matched by letters in the middle
            val priority = GoCompletionOrder.priority(matcher.latin, name) -(if (GoImports.isStandard(path)) STANDARD else OTHER)
            names.addElement(PrioritizedLookupElement.withPriority(item, priority))
        }
    }

    private companion object {
        const val STANDARD = 0.25
        const val OTHER = 0.3

        val IMPORT = InsertHandler<LookupElement> { context, item ->
            val path = item.`object` as? String ?: return@InsertHandler
            val insertion = GoImports.add(context.document.immutableCharSequence, path) ?: return@InsertHandler
            context.document.insertString(insertion.offset, insertion.text)
            context.commitDocument()
        }
    }
}
