package io.github.golangsupport.ide.directives

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile

/**
 * Files and directories of the package directory after `//go:embed ` (GoLand: `analysis.go`, `analysis_test.go`, …), filtered by the
 * typed prefix; `static/` continues in that directory. Only what go would embed by name: no `.`/`_` names (unless `all:`), no names
 * with the characters go rejects, no directories of another module (a `go.mod` inside), no symlinks, no `..`. A prefix with glob
 * characters is left to the user. Directory listings only, so dumb-aware.
 */
class GoEmbedCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiComment().withLanguage(GoLanguage), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val comment = parameters.position as? PsiComment ?: return
                if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, comment.project)) return
                val file = parameters.originalFile as? GoFile ?: return
                val dir = file.virtualFile?.parent ?: return
                val inComment = parameters.offset - comment.textRange.startOffset
                if (inComment < 0 || inComment > comment.textLength) return
                val word = GoEmbedCompletion.wordAt(comment.text.substring(0, inComment)) ?: return
                val items = GoEmbedCompletion.candidates(dir, word)
                if (items.isEmpty()) return
                val matcher = result.withPrefixMatcher(PlainPrefixMatcher(word.path))
                items.forEachIndexed { i, c ->
                    val icon = if (c.file.isDirectory) AllIcons.Nodes.Folder else c.file.fileType.icon
                    val item = LookupElementBuilder.create(c.file, c.path).withIcon(icon)
                        .withTailText(if (c.file.isDirectory) "/" else null, true)
                    matcher.addElement(PrioritizedLookupElement.withPriority(item, (items.size - i).toDouble()))
                }
                result.stopHere()
            }
        })
    }
}

object GoEmbedCompletion {

    /** The pattern being typed: [path] after an optional `all:`, [quoted] when it started with `"` or a backtick. */
    class Word(val path: String, val all: Boolean, val quoted: Boolean)

    /** One offered name: the [path] it inserts (directory part of the typed word + the name). */
    class Candidate(val file: VirtualFile, val path: String)

    /**
     * The pattern word that ends [before] (the `//go:embed` comment text up to the caret), or null when the caret is not at a pattern:
     * not an embed directive, still in `//go:embed`, or a word with glob characters.
     */
    fun wordAt(before: String): Word? {
        if (!GoEmbed.isEmbed(before) || before.length <= "//go:embed".length) return null
        var start = before.length
        var quoted = false
        // a quoted pattern runs from its opening quote: count quotes from the directive to tell open from closed
        var open: Char? = null
        var openAt = -1
        for (i in "//go:embed".length until before.length) {
            val c = before[i]
            if (open == null && (c == '"' || c == '`') && (i == 0 || before[i - 1].isWhitespace())) { open = c; openAt = i }
            else if (open != null && c == open) open = null
        }
        if (open != null) {
            start = openAt + 1
            quoted = true
        } else {
            while (start > 0 && !before[start - 1].isWhitespace()) start--
        }
        if (start <= "//go:embed".length) return null
        var text = before.substring(start)
        if (!quoted && (text.startsWith("\"") || text.startsWith("`"))) return null
        val all = text.startsWith("all:")
        if (all) text = text.substring(4)
        if (text.any { it == '*' || it == '?' || it == '[' || it == '\\' }) return null
        if (text.startsWith("/") || text.split('/').any { it == ".." || it == "." }) return null
        return Word(text, all, quoted)
    }

    /** The children of the directory the typed [word] points into (relative to [packageDir]), files first, each group by name. */
    fun candidates(packageDir: VirtualFile, word: Word): List<Candidate> {
        val slash = word.path.lastIndexOf('/')
        val dirPart = if (slash >= 0) word.path.substring(0, slash + 1) else ""
        val dir = if (dirPart.isEmpty()) packageDir else GoDirectives.relative(packageDir, dirPart.removeSuffix("/")) ?: return emptyList()
        if (!dir.isDirectory || dir != packageDir && isOtherModule(dir)) return emptyList()
        return dir.children
            .filter { embeddable(it, word) }
            .sortedWith(compareBy<VirtualFile> { it.isDirectory }.thenBy { it.name })
            .map { Candidate(it, dirPart + it.name) }
    }

    private fun embeddable(f: VirtualFile, word: Word): Boolean {
        val name = f.name
        if (!word.all && (name.startsWith(".") || name.startsWith("_"))) return false
        if (name.any { it in BAD_CHARS } || !word.quoted && name.any { it.isWhitespace() }) return false
        if (f.`is`(VFileProperty.SYMLINK) || f.`is`(VFileProperty.SPECIAL)) return false
        return !f.isDirectory || !isOtherModule(f)
    }

    /** A directory with its own `go.mod` belongs to another module: go never embeds from it. */
    private fun isOtherModule(dir: VirtualFile): Boolean = dir.findChild("go.mod")?.isDirectory == false

    /** Characters go refuses in embedded file names (`cmd/go/internal/load`, `isBadEmbedName`'s checks via `module.CheckFilePath`). */
    private const val BAD_CHARS = "\"*<>?`'|:\\"
}
