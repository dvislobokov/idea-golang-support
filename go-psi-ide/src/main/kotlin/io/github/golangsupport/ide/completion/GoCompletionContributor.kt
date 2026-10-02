package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.completion.GoCompletionContext.Kind
import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoStructType

/**
 * Go code completion (docs/IDE-FEATURES.md, "Phase 6d: completion"). Providers are keyed by the
 * leaf: the path string of an import spec, a label reference, the package clause name, and any
 * other identifier, whose position [GoCompletionContext] classifies. Every source is index-free
 * (scopes, stub accessors of package files, the project model and directory listings), so the
 * contributor is dumb-aware.
 */
class GoCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().withElementType(GoTokenSets.STRING_LITERALS).withSuperParent(2, GoImportSpec::class.java),
            GoImportPathProvider(),
        )
        extend(CompletionType.BASIC, psiElement(GoTypes.IDENTIFIER).withParent(GoLabelRef::class.java), GoLabelProvider())
        extend(CompletionType.BASIC, psiElement(GoTypes.IDENTIFIER).withParent(GoPackageClause::class.java), GoPackageClauseProvider())
        extend(CompletionType.BASIC, psiElement(GoTypes.IDENTIFIER).withLanguage(GoLanguage), GoIdentifierProvider())
    }

    /** A trimmed dummy identifier keeps `x.<caret>(` and `T{<caret>}` parsing like the final code. */
    override fun beforeCompletion(context: CompletionInitializationContext) {
        if (context.file is GoFile) context.dummyIdentifier = CompletionUtil.DUMMY_IDENTIFIER_TRIMMED
    }

    /** Nothing when the host serves completion from another source ([GoIdeFeature.COMPLETION] off) or in a code fragment. */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, parameters.position.project)) return
        if (isCodeFragment(parameters.originalFile)) return
        super.fillCompletionVariants(parameters, result)
    }

    companion object {
        private val LOG = logger<GoCompletionContributor>()

        /**
         * A Go file made from text and kept in no directory (a non-physical file, or a light one without a parent): the expression
         * editor of a debugger, a preview. It has no package around it, and the host that made it completes it itself.
         */
        fun isCodeFragment(file: PsiFile): Boolean = file.virtualFile?.parent == null

        /** Converts candidates, lets a registered [GoCompletionRanker] score them, and adds them to [result]. */
        fun emit(candidates: List<GoCandidate>, context: GoCompletionContext, result: CompletionResultSet) {
            if (candidates.isEmpty()) return
            val elements: List<LookupElement> = candidates.map { GoLookupElementFactory.create(it, context) }
            applyRankers(elements, context, result.prefixMatcher.prefix)
            result.addAllElements(elements)
        }

        private fun applyRankers(elements: List<LookupElement>, context: GoCompletionContext, prefix: String) {
            val rankers = GoCompletionRanker.EP_NAME.extensionList
            if (rankers.isEmpty()) return
            val infos = elements.map { GoCompletionWeigher.infoOf(it)!! }
            val candidates = elements.zip(infos).map { (e, info) ->
                GoCompletionCandidate(e.lookupString, info.kind.name, info.level, info.expectedMatch, info.element)
            }
            val expected = context.semantics.expectedType?.let(GoLookupElementFactory::typeText)
            val rankingContext = GoCompletionRankingContext(context.originalFile, context.offset, context.kind.name, prefix, expected)
            for (ranker in rankers) {
                val scores = try {
                    ranker.rank(rankingContext, candidates)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Go completion ranker ${ranker.javaClass.name} failed", e)
                    null
                } ?: continue
                if (scores.size != candidates.size) continue
                infos.forEachIndexed { i, info -> info.rankerScore = scores[i] }
                return
            }
        }
    }
}

/** Identifiers in expressions, types, selectors, struct literal keys, top-level and statement keywords. */
private class GoIdentifierProvider : CompletionProvider<CompletionParameters>(), DumbAware {
    override fun addCompletions(parameters: CompletionParameters, processing: ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        val out = ArrayList<GoCandidate>()
        when (context.kind) {
            Kind.STATEMENT, Kind.EXPRESSION -> {
                structKeys(context, out)
                expression(context, result, out)
            }
            Kind.STRUCT_KEY -> {
                // A key of a map or slice literal is an ordinary expression.
                if (!structKeys(context, out)) expression(context, result, out)
            }
            Kind.TYPE -> {
                val scope = ArrayList<GoCandidate>()
                val place = context.typeReference ?: context.leaf
                GoScopeCandidates(context).collect(place, GoScopeCandidates.Filter.TYPES, scope)
                out += scope
                GoKeywordCandidates.collect(context, out)
                unimportedPackages(context, result.prefixMatcher, scope, out)
            }
            Kind.RECEIVER_TYPE -> receiverTypes(context, out)
            Kind.SELECTOR -> GoMemberCandidates(context).collect(out, typesOnly = false)
            Kind.TYPE_SELECTOR -> GoMemberCandidates(context).collect(out, typesOnly = true)
            Kind.TOP_LEVEL, Kind.SWITCH_BODY -> GoKeywordCandidates.collect(context, out)
            else -> return
        }
        GoCompletionContributor.emit(out, context, result)
    }

    private fun expression(context: GoCompletionContext, result: CompletionResultSet, out: MutableList<GoCandidate>) {
        val scope = ArrayList<GoCandidate>()
        val place: GoCompositeElement? = context.reference ?: context.typeReference
        GoScopeCandidates(context).collect(place ?: context.leaf, GoScopeCandidates.Filter.ALL, scope)
        out += scope
        GoKeywordCandidates.collect(context, out)
        GoSnippets.collect(context, scope, out)
        unimportedPackages(context, result.prefixMatcher, scope, out)
    }

    /** Field names of a struct literal (promoted and embedded ones included) not used yet; false when the literal is not a struct. */
    private fun structKeys(context: GoCompletionContext, out: MutableList<GoCandidate>): Boolean {
        val literal = context.literalValue ?: return false
        val type = context.semantics.literalType(literal)?.let(GoCompletionSemantics::derefUnderlying) as? GoStructType ?: return false
        val elements = GoPsiUtil.children(literal, GoElement::class.java)
        val current = PsiTreeUtil.getParentOfType(context.leaf, GoElement::class.java)
        // Positional literals (`T{1, 2}`) take no keys.
        if (!context.keyOnly && elements.any { it !== current && it.key == null }) return true
        val used = elements.filter { it !== current }.mapNotNull { (it.key?.expression as? GoReferenceExpression)?.identifier?.text }.toSet()
        for ((field, depth) in GoMemberCandidates(context).fields(type)) {
            if (field.name in used) continue
            out += GoCandidate(
                field.name, GoCandidateKind.STRUCT_KEY, GoScopeLevel.LOCAL + depth, field.declaration,
                valueType = field.type, tailSupplier = { " " + GoLookupElementFactory.typeText(field.type) },
            )
        }
        return true
    }

    /** Package-level types of the current package (a receiver's base type must be declared there). */
    private fun receiverTypes(context: GoCompletionContext, out: MutableList<GoCandidate>) {
        val seen = HashSet<String>()
        // One pass over the package scope: no intermediate filtered list, no rendering here.
        val types = context.file.types.asSequence() + context.semantics.packageScope.allDeclarations()
            .filterIsInstance<GoTypeSpec>().filter { it.containingFile !== context.originalFile }
        for (spec in types) {
            val name = spec.name ?: continue
            if (name.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) || !seen.add(name)) continue
            out += GoScopeCandidates.declarationCandidate(spec, name, GoScopeLevel.PACKAGE, context)
        }
    }

    /**
     * Packages not imported yet whose name matches the prefix (standard library first, then the
     * modules of the build list); inserting one adds the import.
     */
    private fun unimportedPackages(context: GoCompletionContext, matcher: PrefixMatcher, scope: List<GoCandidate>, out: MutableList<GoCandidate>) {
        val prefix = matcher.prefix
        if (prefix.isEmpty()) return
        val visible = scope.mapTo(HashSet()) { it.name }
        val imported = context.semantics.imports.mapTo(HashSet()) { it.path }
        val seen = HashSet<String>()
        for (entry in GoImportPaths.all(context.file.project, context.originalFile.virtualFile)) {
            // Strict start match: middle matches over ~300 package names are noise.
            if (entry.name in visible || entry.path in imported || !entry.name.startsWith(prefix)) continue
            if (!seen.add(entry.path)) continue
            out += GoCandidate(
                entry.name, GoCandidateKind.PACKAGE, GoScopeLevel.UNIMPORTED,
                tailText = " (${entry.path})", typeText = "import", importPath = entry.path,
            )
        }
    }
}

/** Labels of the enclosing function after `goto`; for `break`/`continue` only labels of enclosing statements they can target. */
private class GoLabelProvider : CompletionProvider<CompletionParameters>(), DumbAware {
    override fun addCompletions(parameters: CompletionParameters, processing: ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        if (context.kind != Kind.LABEL) return
        val owner = context.functionOwner ?: return
        val body = when (owner) {
            is GoFunctionOrMethodDeclaration -> owner.block
            is GoFunctionLit -> GoPsiUtil.run { owner.block }
            else -> null
        } ?: return
        val jump = context.jump
        val out = ArrayList<GoCandidate>()
        for (label in PsiTreeUtil.findChildrenOfType(body, GoLabelDefinition::class.java)) {
            if (GoPsiUtil.functionOwner(label) !== owner) continue
            val name = label.name ?: continue
            val labeled = label.parent as? GoLabeledStatement
            val target = labeled?.statement
            val allowed = when (jump) {
                is GoBreakStatement -> target != null && PsiTreeUtil.isAncestor(target, jump, true) &&
                    (target is GoForStatement || target is GoSwitchStatement || target is GoSelectStatement || target is GoExprSwitchStatement || target is GoTypeSwitchStatement)
                is GoContinueStatement -> target is GoForStatement && PsiTreeUtil.isAncestor(target, jump, true)
                else -> true
            }
            if (!allowed) continue
            out += GoCandidate(name, GoCandidateKind.LABEL, GoScopeLevel.LOCAL, label, tailText = " label")
        }
        GoCompletionContributor.emit(out, context, result)
    }
}

/** The package name: names used by the other files of the directory, the directory name, `main`; `<name>_test` in test files. */
private class GoPackageClauseProvider : CompletionProvider<CompletionParameters>(), DumbAware {
    override fun addCompletions(parameters: CompletionParameters, processing: ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        if (context.kind != Kind.PACKAGE_CLAUSE) return
        val names = LinkedHashSet<String>()
        val directory = context.originalFile.originalFile.containingDirectory
        directory?.files?.forEach { f ->
            if (f is GoFile && f.virtualFile != context.originalFile.virtualFile) f.packageName?.takeIf { !it.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) }?.let(names::add)
        }
        directory?.name?.let { dirName ->
            val sanitized = dirName.substringBefore('.').replace('-', '_').lowercase().filter { it.isLetterOrDigit() || it == '_' }
            if (sanitized.isNotEmpty() && !sanitized[0].isDigit()) names += sanitized
        }
        names += "main"
        if (context.originalFile.isTestFile) names.toList().filter { !it.endsWith("_test") }.forEach { names += it + "_test" }
        val out = names.map { GoCandidate(it, GoCandidateKind.PACKAGE_NAME, GoScopeLevel.LOCAL, tailText = " package") }
        GoCompletionContributor.emit(out, context, result)
    }
}

/** Import paths inside the string of an import spec: standard library, then packages of the main and required modules. */
private class GoImportPathProvider : CompletionProvider<CompletionParameters>(), DumbAware {
    override fun addCompletions(parameters: CompletionParameters, processing: ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        if (context.kind != Kind.IMPORT_PATH) return
        val leaf = context.leaf
        val inLeaf = (parameters.offset - leaf.textRange.startOffset).coerceIn(0, leaf.textLength)
        val prefix = leaf.text.substring(0, inLeaf).drop(1)
        val currentSpec = PsiTreeUtil.getParentOfType(leaf, GoImportSpec::class.java)
        val imported = context.file.imports.filter { it !== currentSpec }.mapTo(HashSet()) { it.path }
        val out = ArrayList<GoCandidate>()
        val seen = HashSet<String>()
        for (entry in GoImportPaths.all(context.file.project, context.originalFile.virtualFile)) {
            if (entry.path in imported || !seen.add(entry.path)) continue
            val level = if (entry.isStd) GoScopeLevel.PACKAGE else GoScopeLevel.IMPORTED
            out += GoCandidate(entry.path, GoCandidateKind.IMPORT_PATH, level, tailText = entry.module?.let { "  $it" })
        }
        GoCompletionContributor.emit(out, context, result.withPrefixMatcher(GoImportPathMatcher(prefix)))
        result.stopHere()
    }
}

/** Matches an import path by its start or by the start of any path element (`ht` finds `net/http`). */
class GoImportPathMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean =
        name.startsWith(prefix) || (prefix.isNotEmpty() && !prefix.contains('/') && name.split('/').any { it.startsWith(prefix) })

    override fun cloneWithPrefix(prefix: String): PrefixMatcher = GoImportPathMatcher(prefix)
}
