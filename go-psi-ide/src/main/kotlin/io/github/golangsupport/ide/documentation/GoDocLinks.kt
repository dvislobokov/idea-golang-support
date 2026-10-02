package io.github.golangsupport.ide.documentation

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoLookup

/**
 * A go/doc link (Go 1.19 doc comments): `[Name]`, `[Name1.Name2]`, `[pkg.Name]`, `[pkg.Name1.Name2]`, `[import/path.Name]`, any of them
 * with a leading `*`. [names] are the dot-separated names after the path; [nameRanges] where each one is, in the text the link was
 * found in.
 */
class GoDocLink(val path: String?, val names: List<String>, val nameRanges: List<TextRange>)

/**
 * Doc links: where they are in a comment and what they name. Resolution follows go/doc: `[Name]` is a package-level name of the
 * comment's package (or a predeclared one, or an imported package); `[A.B]` is a field or method `B` of the type `A` when the package
 * declares `A`, otherwise `B` of the package imported as `A` (or with the import path `A`); `[pkg.T.M]` is a member of a type of
 * another package.
 */
object GoDocLinks {

    private val LINK = Regex("""\[\*?([\p{L}\p{Nd}_./\-]+)]""")
    private val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{Nd}_]*""")
    private val PATH = Regex("""[\p{L}\p{Nd}_.\-]+(/[\p{L}\p{Nd}_.\-]+)+""")
    /** Comment directives (`//go:generate`, `//nolint:x`, `//line f:1`), as go/ast drops them from doc text. */
    private val DIRECTIVE = Regex("""^//(line |extern |export |[a-z0-9]+:[a-z0-9])""")

    /** The link inside the brackets (without `*`), [offset] being where [inner] starts; null when it is not link syntax. */
    fun parse(inner: String, offset: Int): GoDocLink? {
        val lastSlash = inner.lastIndexOf('/')
        val path: String?
        val namesStart: Int
        if (lastSlash >= 0) {
            val dot = inner.indexOf('.', lastSlash)
            if (dot < 0) return null
            path = inner.substring(0, dot)
            if (!PATH.matches(path)) return null
            namesStart = dot + 1
        } else {
            path = null
            namesStart = 0
        }
        val names = inner.substring(namesStart).split('.')
        if (names.size > (if (path != null) 2 else 3) || names.any { !IDENTIFIER.matches(it) }) return null
        val ranges = ArrayList<TextRange>(names.size)
        var at = offset + namesStart
        for (n in names) {
            ranges += TextRange(at, at + n.length)
            at += n.length + 1
        }
        return GoDocLink(path, names, ranges)
    }

    /**
     * The doc links of a `//` comment line outside function bodies, ranges relative to the comment. Not in directives, indented
     * (code) lines and `[Text]: URL` link definitions; the brackets must stand apart from words, as go/doc wants (`a[i]` is no link).
     */
    fun linksIn(comment: PsiComment): List<GoDocLink> {
        val text = comment.text
        if ('[' !in text || !text.startsWith("//") || DIRECTIVE.containsMatchIn(text)) return emptyList()
        if (text.startsWith("//\t") || text.startsWith("//  ")) return emptyList()
        if (PsiTreeUtil.getParentOfType(comment, GoBlock::class.java) != null) return emptyList()
        return linksInLine(text, if (text.startsWith("// ")) 3 else 2)
    }

    /** The links of one comment line whose text starts at [contentStart]. */
    fun linksInLine(text: String, contentStart: Int): List<GoDocLink> {
        val result = ArrayList<GoDocLink>()
        for (m in LINK.findAll(text, contentStart)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > contentStart && isWordChar(text[start - 1])) continue
            if (end < text.length && (isWordChar(text[end]) || text[end] == '[')) continue
            // `[Text]: URL` at the start of the line is a link definition.
            if (start == contentStart && text.startsWith(": ", end)) continue
            val group = m.groups[1]!!
            parse(group.value, group.range.first)?.let { result += it }
        }
        return result
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** What each of [link]'s names refers to (same order as [GoDocLink.names]; null where nothing is found), seen from [context]'s file. */
    fun resolve(context: PsiElement, link: GoDocLink): List<PsiElement?> {
        val file = context.containingFile?.originalFile as? GoFile ?: return link.names.map { null }
        val project = file.project
        val model = GoPackageModel.getInstance(project)
        val names = link.names
        fun importNamed(name: String): GoImportSpec? = file.imports.firstOrNull { !it.isDot && !it.isBlank && GoScopes.importName(it) == name }
        fun packageScope(path: String): GoPackageModel.PackageScope? = model.resolveImport(path, file)?.let(model::scopeOf)
        fun padded(list: List<PsiElement?>): List<PsiElement?> = list + List(maxOf(0, names.size - list.size)) { null }

        if (link.path != null) {
            val scope = packageScope(link.path) ?: return padded(emptyList())
            val first = scope.lookup(names[0]).firstOrNull()
            return padded(listOf(first, if (names.size > 1) (first as? GoTypeSpec)?.let { member(file, scope, it, names[1]) } else null).take(names.size))
        }
        val own = model.scopeOf(file)
        return when (names.size) {
            1 -> listOf(own.lookup(names[0]).firstOrNull() ?: GoUniverse.declaration(project, names[0]) ?: importNamed(names[0]))
            2 -> {
                val type = own.lookupType(names[0]) ?: (GoUniverse.declaration(project, names[0]) as? GoTypeSpec)
                if (type != null) {
                    listOf(type, member(file, own, type, names[1]))
                } else {
                    val spec = importNamed(names[0])
                    val scope = packageScope(spec?.path ?: names[0])
                    listOf(spec, scope?.lookup(names[1])?.firstOrNull())
                }
            }
            else -> {
                val spec = importNamed(names[0])
                val scope = packageScope(spec?.path ?: names[0])
                val type = scope?.lookupType(names[1])
                listOf(spec, type, type?.let { member(file, scope, it, names[2]) })
            }
        }
    }

    /** What the whole link [text] (as written between the brackets, `*` allowed) names: its last name. */
    fun resolveText(context: PsiElement, text: String): PsiElement? {
        val link = parse(text.removePrefix("*"), 0) ?: return null
        return resolve(context, link).lastOrNull()
    }

    /** A method or field [name] of [type]: the package's own methods first, then promoted ones and fields through the type checker. */
    private fun member(file: GoFile, scope: GoPackageModel.PackageScope, type: GoTypeSpec, name: String): GoNamedElement? {
        val typeName = type.name ?: return null
        scope.methodsOf(typeName).firstOrNull { it.name == name }?.let { return it }
        val semantic = GoSemanticService.getInstance(file.project)
        return when (val selection = semantic.lookupFieldOrMethod(semantic.declarationType(type), name, file)) {
            is GoLookup.Selection.Field -> selection.member.declaration
            is GoLookup.Selection.Method -> selection.method.declaration
            else -> null
        }
    }
}
