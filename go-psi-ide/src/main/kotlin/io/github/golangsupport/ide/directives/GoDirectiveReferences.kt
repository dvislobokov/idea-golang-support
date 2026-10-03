package io.github.golangsupport.ide.directives

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.scope.GoPackageModel

/**
 * A word of a directive comment that names something: Ctrl+click, Find Usages (for declarations) and Rename reach it. Poly-variant,
 * since an embed glob matches many files. Soft: no target is no error. Only [renamable] ones follow a rename of the target.
 */
class GoDirectiveReference(
    comment: PsiComment, range: TextRange, private val renamable: Boolean, private val targets: (PsiComment) -> List<PsiElement>,
) : PsiReferenceBase<PsiComment>(comment, range, true), PsiPolyVariantReference {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = PsiElementResolveResult.createResults(targets(element))

    override fun resolve(): PsiElement? = multiResolve(false).singleOrNull()?.element

    override fun isReferenceTo(element: PsiElement): Boolean = multiResolve(false).any { element.manager.areElementsEquivalent(it.element, element) }

    override fun handleElementRename(newElementName: String): PsiElement {
        val leaf = element as? LeafPsiElement ?: return element
        if (!renamable) return element
        return leaf.replaceWithText(leaf.text.replaceRange(rangeInElement.startOffset, rangeInElement.endOffset, newElementName)).psi
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/** The references of directive comments; separate from [GoDirectiveReferenceContributor] so tests reach them without the registrar. */
object GoDirectiveReferences {

    fun of(comment: PsiComment): List<PsiReference> {
        val text = comment.text
        if (!text.startsWith("//go:")) return emptyList()
        val file = comment.containingFile?.originalFile as? GoFile ?: return emptyList()
        return when {
            GoEmbed.isEmbed(text) -> embed(comment, text)
            GoDirectives.isDirective(text, "linkname") -> linkname(comment, text, file)
            GoDirectives.isDirective(text, "generate") -> generate(comment, text)
            else -> emptyList()
        }
    }

    private fun directory(comment: PsiComment): VirtualFile? = comment.containingFile?.originalFile?.virtualFile?.parent

    private fun psiOf(comment: PsiComment, files: List<VirtualFile>): List<PsiElement> {
        val manager = PsiManager.getInstance(comment.project)
        return files.mapNotNull { if (it.isDirectory) manager.findDirectory(it) else manager.findFile(it) }
    }

    private fun embed(comment: PsiComment, text: String): List<PsiReference> = GoEmbed.patterns(text).first.mapNotNull { p ->
        if (GoEmbed.validate(p) != null) return@mapNotNull null
        GoDirectiveReference(comment, p.range, false) { c -> directory(c)?.let { psiOf(c, GoEmbed.match(it, p.pattern)) }.orEmpty() }
    }

    private fun linkname(comment: PsiComment, text: String, file: GoFile): List<PsiReference> {
        val fields = GoDirectives.fields(text, "//go:linkname".length, "").first
        val result = ArrayList<PsiReference>(2)
        val local = fields.getOrNull(0) ?: return result
        result += GoDirectiveReference(comment, local.range, true) { c ->
            val f = c.containingFile?.originalFile as? GoFile
            listOfNotNull(f?.let { GoPackageModel.getInstance(it.project).scopeOf(it).lookup(local.value).firstOrNull() })
        }
        val target = fields.getOrNull(1) ?: return result
        val value = target.value
        val slash = value.lastIndexOf('/')
        // `gopkg.in/yaml.v3.Foo` has dots in the path: every dot after the last slash is a candidate end of it; the first that finds a package wins
        for (dot in value.indices.filter { it > slash + 1 && value[it] == '.' }) {
            if (linkTarget(file, value.substring(0, dot), value.substring(dot + 1)) == null) continue
            val range = TextRange(target.range.startOffset + dot + 1, target.range.endOffset)
            result += GoDirectiveReference(comment, range, false) { c ->
                val f = c.containingFile?.originalFile as? GoFile
                listOfNotNull(f?.let { linkTarget(it, value.substring(0, dot), value.substring(dot + 1)) })
            }
            break
        }
        return result
    }

    /** `name` or `Type.method` (also `(*Type).method`) of the package with import path [path], read from stubs. */
    private fun linkTarget(file: GoFile, path: String, name: String): PsiElement? {
        val model = GoPackageModel.getInstance(file.project)
        val scope = model.resolveImport(path, file)?.let(model::scopeOf) ?: return null
        val parts = name.split('.')
        if (parts.size == 1) return scope.lookup(name).firstOrNull()
        if (parts.size != 2) return null
        val type = parts[0].removePrefix("(").removePrefix("*").removeSuffix(")")
        return scope.methodsOf(type).firstOrNull { it.name == parts[1] }
    }

    private fun generate(comment: PsiComment, text: String): List<PsiReference> {
        val fields = GoDirectives.fields(text, "//go:generate".length, "\"'").first
        return fields.drop(1).mapNotNull { f ->
            var value = f.value
            var offset = f.range.startOffset
            if (value.startsWith("-")) {
                val eq = value.indexOf('=')
                if (eq < 0) return@mapNotNull null
                value = value.substring(eq + 1)
                offset += eq + 1
            }
            if (value.isEmpty() || '$' in value || value.startsWith("/") || "://" in value) return@mapNotNull null
            val path = value.removeSuffix("...").removeSuffix("/").ifEmpty { "." }
            if ('/' !in path && !FILE_EXT.containsMatchIn(path)) return@mapNotNull null
            if (directory(comment)?.let { GoDirectives.relative(it, path) } == null) return@mapNotNull null
            GoDirectiveReference(comment, TextRange(offset, offset + value.length), false) { c ->
                directory(c)?.let { d -> GoDirectives.relative(d, path)?.let { psiOf(c, listOf(it)) } }.orEmpty()
            }
        }
    }

    private val FILE_EXT = Regex("""\.[A-Za-z0-9]{1,6}$""")
}

/** `psi.referenceContributor`: `//go:embed` patterns, `//go:linkname` names and `//go:generate` paths; with the navigation group of the gate. */
class GoDirectiveReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiComment().withLanguage(GoLanguage), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val comment = element as? PsiComment ?: return PsiReference.EMPTY_ARRAY
                if (!comment.text.startsWith("//go:")) return PsiReference.EMPTY_ARRAY
                if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, comment.project)) return PsiReference.EMPTY_ARRAY
                return GoDirectiveReferences.of(comment).toTypedArray()
            }
        })
    }
}
