package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.scope.GoPackageModel
import java.util.IdentityHashMap

/**
 * Names of the package's declarations in a comment at the top level, as GoLand offers them (`// Cir` → `Circle`): types, functions,
 * methods, constants and variables of every file of the package, exported ones first, each group in the order of the files and of
 * the declarations in them; the name of the declaration the comment documents comes first. Explicit completion only: the confidence
 * keeps the popup off in comments. Index-free (package scope and stubs; other files' AST is not loaded), so dumb-aware.
 */
class GoCommentCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiComment().withLanguage(GoLanguage), GoCommentNamesProvider())
    }
}

private class GoCommentNamesProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val comment = parameters.position as? PsiComment ?: return
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, comment.project)) return
        val file = parameters.originalFile as? GoFile ?: return
        if (GoCompletionContributor.isCodeFragment(file)) return
        val inComment = parameters.offset - comment.textRange.startOffset
        if (inComment < 2 || inComment > comment.textLength) return
        val before = comment.text.substring(0, inComment)
        if (!GoCommentCompletion.isNameComment(before)) return
        val holder = GoCommentCompletion.holderOf(comment) ?: return
        val prefix = before.takeLastWhile { Character.isJavaIdentifierPart(it) }
        val own = GoCommentCompletion.documented(comment, holder)?.name
        val names = GoCommentCompletion.packageNames(file)
        val ordered = buildList {
            own?.let { n -> names.firstOrNull { it.name == n }?.let(::add) }
            names.filterTo(this) { it.name != own }
        }
        if (ordered.isEmpty()) return
        val matcher = result.withPrefixMatcher(prefix)
        ordered.forEachIndexed { i, e ->
            val name = e.name ?: return@forEachIndexed
            val item = LookupElementBuilder.create(e, name).withIcon(GoIdeIcons.forElement(e))
            matcher.addElement(PrioritizedLookupElement.withPriority(item, (ordered.size - i).toDouble()))
        }
        // the platform's word completion would repeat the names of this file
        result.stopHere()
    }
}

object GoCommentCompletion {

    /** [before] (the comment text up to the caret) is ordinary comment prose: not a `//go:`, `//line` or `// +build` directive. */
    fun isNameComment(before: String): Boolean {
        if (before.startsWith("//")) {
            val body = before.substring(2)
            if (DIRECTIVE.containsMatchIn(before) || body.trimStart().startsWith("+build")) return false
        }
        return true
    }

    /** `//go:embed`, `//line x`, `//export Name`: lowercase word + `:` without a space, as go/ast tells directives. */
    private val DIRECTIVE = Regex("^//(line |extern |export |[a-z0-9]+:)")

    /**
     * The element the comment belongs to when it is at the top level: the file, or a package-level declaration (or spec) it leads,
     * the package clause (package doc). Null inside function bodies, struct and interface types and parameter lists.
     */
    fun holderOf(comment: PsiComment): PsiElement? {
        val parent = comment.parent ?: return null
        if (parent is GoFile) return parent
        if (parent is GoPackageClause) return parent.takeIf { leads(comment, it) }
        if (!isTopLevelHolder(parent) || !leads(comment, parent)) return null
        val grand = parent.parent
        return if (grand is GoFile || isTopLevelHolder(grand) && grand?.parent is GoFile) parent else null
    }

    private fun isTopLevelHolder(e: PsiElement?) = e is GoFunctionOrMethodDeclaration || e is GoTypeDeclaration || e is GoTypeSpec ||
        e is GoVarDeclaration || e is GoVarSpec || e is GoConstDeclaration || e is GoConstSpec

    /** Only comments and white space before [comment] inside [holder]: a doc comment, not one after the declaration's keyword. */
    private fun leads(comment: PsiComment, holder: PsiElement): Boolean {
        var e = holder.firstChild
        while (e != null && e !== comment) {
            if (e !is PsiComment && e !is PsiWhiteSpace) return false
            e = e.nextSibling
        }
        return e === comment
    }

    /** The declaration [comment] documents: its holder's first named element, or the declaration right below a file-level comment. */
    fun documented(comment: PsiComment, holder: PsiElement): GoNamedElement? {
        if (holder is GoPackageClause) return null
        val decl = if (holder is GoFile) {
            var next = comment.nextSibling
            while (next is PsiComment || next is PsiWhiteSpace && next.text.count { it == '\n' } <= 1) next = next.nextSibling
            next?.takeIf { isTopLevelHolder(it) } ?: return null
        } else holder
        return decl as? GoNamedElement ?: PsiTreeUtil.findChildOfType(decl, GoNamedElement::class.java)
    }

    /**
     * The package-level declarations of [file]'s package (methods too), exported first, then the rest; within each, [file] first, then
     * the other files of the package, each in source order. Distinct by name (the first wins). Other files are read from stubs.
     */
    fun packageNames(file: GoFile): List<GoNamedElement> {
        val scope = GoPackageModel.getInstance(file.project).scopeOf(file)
        val original = file.originalFile as? GoFile ?: file
        val files = listOf(original) + scope.files.filter { it != original && it.viewProvider.virtualFile != original.viewProvider.virtualFile }
        val all = files.flatMap(::declarationsInOrder)
        val seen = HashSet<String>()
        val distinct = all.filter { e -> e.name.let { it != null && it != "_" && seen.add(it) } }
        val (exported, rest) = distinct.partition { e -> e.name!!.first().isUpperCase() }
        return exported + rest
    }

    /** The package-level named elements of [file] in source order: by stub order when the AST is not loaded, else by offset. */
    private fun declarationsInOrder(file: GoFile): List<GoNamedElement> {
        val elements: List<GoNamedElement> = file.types + file.functions + file.methods + file.consts + file.vars
        val stub = file.stub ?: return elements.sortedBy { it.textOffset }
        val order = IdentityHashMap<PsiElement, Int>()
        fun walk(s: StubElement<*>) {
            for (child in s.childrenStubs) {
                order[child.psi] = order.size
                walk(child)
            }
        }
        walk(stub)
        return elements.sortedBy { order[it] ?: Int.MAX_VALUE }
    }
}
