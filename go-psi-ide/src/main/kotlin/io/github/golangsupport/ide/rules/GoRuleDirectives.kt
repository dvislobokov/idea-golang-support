package io.github.golangsupport.ide.rules

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * The linter directives of one file, the golangci-lint / staticcheck way:
 *
 * - `//nolint` or `//nolint:a,b` (no space after `//`, like golangci-lint v2): at the end of a line it covers that line, and the whole
 *   declaration when the line starts a top-level declaration, a type spec or a function; on a line of its own it covers the node that
 *   starts on the next line at the same column (golangci-lint's range expansion), or the declaration it documents.
 *   The names are linters (`errcheck`, `revive`), rule ids (`SA4006`) or `all`.
 * - `//lint:ignore Check1,Check2 reason` (staticcheck): the same placement rules, matched against rule ids, `*` globs allowed (`SA4*`).
 * - `//lint:file-ignore Check reason`: the whole file.
 *
 * Found by a text scan for the markers (no tree walk), computed once per pass and only when a rule reports something.
 */
internal class GoRuleDirectives private constructor(private val entries: List<Entry>) {

    class Entry(val start: Int, val end: Int, val names: List<String>?, val staticcheck: Boolean) {
        fun matches(rule: GoRule): Boolean = names == null || names.any { name ->
            if (staticcheck) glob(name, rule.id)
            else name.equals("all", true) || name.equals(rule.linter, true) || name.equals(rule.id, true) || rule.linterAliases.any { it.equals(name, true) }
        }

        private fun glob(pattern: String, id: String): Boolean =
            if (pattern.endsWith("*")) id.startsWith(pattern.dropLast(1), ignoreCase = true) else pattern.equals(id, true)
    }

    fun suppresses(rule: GoRule, offset: Int): Boolean = entries.any { offset >= it.start && offset <= it.end && it.matches(rule) }

    companion object {
        private val EMPTY = GoRuleDirectives(emptyList())
        private val NOLINT = Regex("""//nolint(?::([\w.,-]+))?(?=\s|$)""")
        private val LINT_IGNORE = Regex("""//lint:(ignore|file-ignore)\s+([\w.,*-]+)""")

        fun of(file: GoFile): GoRuleDirectives {
            val text = file.viewProvider.contents
            val entries = ArrayList<Entry>()
            scan(text, "//nolint") { offset ->
                val m = NOLINT.matchAt(text, offset) ?: return@scan
                val names = m.groups[1]?.value?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                place(file, text, offset, names, false)?.let(entries::add)
            }
            scan(text, "//lint:") { offset ->
                val m = LINT_IGNORE.matchAt(text, offset) ?: return@scan
                val names = m.groupValues[2].split(',').map(String::trim).filter(String::isNotEmpty)
                if (m.groupValues[1] == "file-ignore") {
                    if (commentAt(file, offset) != null) entries += Entry(0, text.length, names, true)
                } else {
                    place(file, text, offset, names, true)?.let(entries::add)
                }
            }
            return if (entries.isEmpty()) EMPTY else GoRuleDirectives(entries)
        }

        private inline fun scan(text: CharSequence, marker: String, found: (Int) -> Unit) {
            var i = indexOf(text, marker, 0)
            while (i >= 0) {
                found(i)
                i = indexOf(text, marker, i + marker.length)
            }
        }

        private fun indexOf(text: CharSequence, marker: String, from: Int): Int = text.indexOf(marker, from)

        /** The comment that starts exactly at [offset] (a marker inside a string or in the middle of a comment is no directive). */
        private fun commentAt(file: PsiFile, offset: Int): PsiComment? = (file.findElementAt(offset) as? PsiComment)?.takeIf { it.textRange.startOffset == offset }

        private fun place(file: GoFile, text: CharSequence, offset: Int, names: List<String>?, staticcheck: Boolean): Entry? {
            val comment = commentAt(file, offset) ?: return null
            val lineStart = lineStart(text, offset)
            val lineEnd = lineEnd(text, offset)
            val ownLine = text.subSequence(lineStart, offset).isBlank()
            if (!ownLine) {
                // trailing: the line, or the whole declaration that starts on it
                val first = firstCode(text, lineStart)
                val decl = file.findElementAt(first)?.let { declarationStartingAt(it, first) }
                val end = maxOf(lineEnd, decl?.textRange?.endOffset ?: lineEnd)
                return Entry(lineStart, end, names, staticcheck)
            }
            // on its own line: the declaration it documents (bound into the declaration by the doc-comment binder) ...
            val owner = comment.parent
            if (owner != null && owner !is PsiFile && leadsInto(comment, owner)) return Entry(lineStart, owner.textRange.endOffset, names, staticcheck)
            // ... or the node starting on the next line at the same column
            if (lineEnd >= text.length) return Entry(lineStart, lineEnd, names, staticcheck)
            val nextStart = lineEnd + 1
            val first = firstCode(text, nextStart)
            if (first >= text.length || first - nextStart != offset - lineStart) return Entry(lineStart, lineEnd, names, staticcheck)
            val leaf = file.findElementAt(first) ?: return Entry(lineStart, lineEnd, names, staticcheck)
            var node: PsiElement = leaf
            while (node.parent != null && node.parent !is PsiFile && node.parent.textRange.startOffset == first) node = node.parent
            return Entry(lineStart, maxOf(lineEnd, node.textRange.endOffset), names, staticcheck)
        }

        /** Whether [comment] is among the leading comments of [owner] (only comments and white space before it inside [owner]). */
        private fun leadsInto(comment: PsiComment, owner: PsiElement): Boolean {
            var c = owner.firstChild
            while (c != null && (c is PsiComment || c is PsiWhiteSpace)) {
                if (c === comment) return true
                c = c.nextSibling
            }
            return false
        }

        /** A top-level declaration, type spec, function or interface method whose code (after its doc comment) starts at [offset]. */
        private fun declarationStartingAt(leaf: PsiElement, offset: Int): PsiElement? {
            var e: PsiElement? = leaf
            var found: PsiElement? = null
            while (e != null && e !is PsiFile) {
                if (codeStart(e) != offset) break
                if (e.parent is PsiFile || e is GoTypeSpec || e is GoFunctionOrMethodDeclaration || e is GoMethodSpec) found = e
                e = e.parent
            }
            return found
        }

        private fun codeStart(e: PsiElement): Int {
            var c = e.firstChild
            while (c != null && (c is PsiComment || c is PsiWhiteSpace)) c = c.nextSibling
            return (c ?: e).textRange.startOffset
        }

        private fun firstCode(text: CharSequence, from: Int): Int {
            var i = from
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            return i
        }

        private fun lineStart(text: CharSequence, offset: Int): Int {
            var i = offset
            while (i > 0 && text[i - 1] != '\n') i--
            return i
        }

        private fun lineEnd(text: CharSequence, offset: Int): Int {
            var i = offset
            while (i < text.length && text[i] != '\n') i++
            return i
        }
    }
}
