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
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.StandardPatterns
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
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

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
        extend(
            CompletionType.BASIC,
            psiElement().withElementType(GoTokenSets.STRING_LITERALS).withSuperParent(2, GoArgumentList::class.java),
            GoFormatVerbProvider(),
        )
        extend(
            CompletionType.BASIC,
            psiElement().withElementType(GoTokenSets.STRING_LITERALS).withSuperParent(2, GoArgumentList::class.java),
            GoTimeLayoutProvider(),
        )
        extend(CompletionType.BASIC, psiElement(GoTypes.IDENTIFIER).withParent(GoLabelRef::class.java), GoLabelProvider())
        extend(CompletionType.BASIC, psiElement(GoTypes.IDENTIFIER).withParent(GoPackageClause::class.java), GoPackageClauseProvider())
        extend(CompletionType.BASIC, psiElement(GoTypes.IDENTIFIER).withLanguage(GoLanguage), GoIdentifierProvider())
        extend(CompletionType.SMART, psiElement(GoTypes.IDENTIFIER).withLanguage(GoLanguage), GoSmartProvider())
    }

    /** A trimmed dummy identifier keeps `x.<caret>(` and `T{<caret>}` parsing like the final code. */
    override fun beforeCompletion(context: CompletionInitializationContext) {
        if (context.file is GoFile) context.dummyIdentifier = CompletionUtil.DUMMY_IDENTIFIER_TRIMMED
    }

    /**
     * Nothing when the host serves completion from another source ([GoIdeFeature.COMPLETION] off) or in a code fragment of the host;
     * a fragment of a refactoring dialog ([GoCodeFragments]) is completed in a copy of its context file.
     */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, parameters.position.project)) return
        if (isCodeFragment(parameters.originalFile)) {
            val moved = GoCodeFragments.completionParameters(parameters) ?: return
            GoCompletionContext.of(moved)?.let { GoBasicCompletion.fill(it, result) }
            return
        }
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
            val prefix = result.prefixMatcher.prefix
            val elements: List<LookupElement> = candidates.map { GoLookupElementFactory.create(it, context) }
            result.addAllElements(applyRankers(elements, context, prefix).map { GoLookupPriority.wrap(it, prefix) })
        }

        /** The elements, scored by the first ranker that answers; wrapped with its [GoCompletionRanker.marker] when it has one. */
        private fun applyRankers(elements: List<LookupElement>, context: GoCompletionContext, prefix: String): List<LookupElement> {
            val rankers = GoCompletionRanker.EP_NAME.extensionList
            if (rankers.isEmpty()) return elements
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
                val marker = ranker.marker ?: return elements
                return elements.map { MarkedElement(it, marker) }
            }
            return elements
        }
    }

    /** A scored element with the ranker's marker as grey tail text; everything else (insert, lookup strings, user data) is the delegate's. */
    private class MarkedElement(delegate: LookupElement, private val marker: String) : LookupElementDecorator<LookupElement>(delegate) {
        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            presentation.appendTailText(" $marker", true)
        }
    }
}

/** Identifiers in expressions, types, selectors, struct literal keys, top-level and statement keywords. */
private class GoIdentifierProvider : CompletionProvider<CompletionParameters>(), DumbAware {
    override fun addCompletions(parameters: CompletionParameters, processing: ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        if (context.kind == Kind.NONE && GoNameCompletion.variableNames(context, result)) return
        GoBasicCompletion.fill(context, result)
    }
}

/**
 * Smart completion (Ctrl+Shift+Space): only what fits the expected type at the caret (identical first): values of the scope or
 * members of the qualifier, functions and methods by their first result, literals written for the type ([GoSmartLiterals]),
 * chains `x.F.M` ([GoChainCandidates]) and members of unimported project packages ([GoProjectMemberCandidates]) that fit.
 * Without an expected type (or outside an expression) it is the basic set: an empty list helps nobody.
 */
private class GoSmartProvider : CompletionProvider<CompletionParameters>(), DumbAware {
    override fun addCompletions(parameters: CompletionParameters, processing: ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        val smartKind = context.isExpression || context.kind == Kind.SELECTOR
        val expected = if (smartKind) context.semantics.expectedType else null
        if (expected == null) {
            GoBasicCompletion.fill(context, result)
            return
        }
        val fits = { c: GoCandidate ->
            GoLookupElementFactory.smartMatch(c, expected) > 0 || c.kind == GoCandidateKind.BUILTIN_FUNCTION && builtinFits(c.name, expected)
        }
        val candidates = ArrayList<GoCandidate>()
        val out = ArrayList<GoCandidate>()
        if (context.kind == Kind.SELECTOR) {
            GoMemberCandidates(context).collect(candidates, typesOnly = false)
            candidates.filterTo(out, fits)
            GoCompletionContributor.emit(out, context, result)
            return
        }
        GoScopeCandidates(context).collect(context.reference ?: context.typeReference ?: context.leaf, GoScopeCandidates.Filter.ALL, candidates)
        candidates.filterTo(out, fits)
        GoSmartLiterals(context).let {
            it.collect(expected, out)
            it.collectImplementations(expected, candidates, out)
        }
        GoCompletionContributor.emit(out, context, result)
        GoBasicCompletion.later(context, result, candidates, typesOnly = false, accept = fits)
    }

    /** Builtins whose result type is known without their arguments: `len`/`cap` for an int, `append` for a slice, `new` for a pointer. */
    private fun builtinFits(name: String, expected: GoType): Boolean = when (name) {
        "len", "cap" -> GoTypePredicates.assignable(GoBasicType.INT, expected)
        "append" -> expected.underlying() is GoSliceType
        "new" -> expected.underlying() is GoPointerType
        else -> false
    }
}

/** The basic set of an identifier position; shared by basic completion and by smart completion without an expected type. */
private object GoBasicCompletion {
    /** Chains and project members need this many typed characters in basic completion (fewer would list half the project). */
    const val MIN_PREFIX = 2

    fun fill(context: GoCompletionContext, result: CompletionResultSet) {
        val out = ArrayList<GoCandidate>()
        var scope: List<GoCandidate>? = null
        var typesOnly = false
        when (context.kind) {
            Kind.STATEMENT, Kind.EXPRESSION -> {
                structKeys(context, out, result)
                scope = expression(context, result, out)
            }
            Kind.STRUCT_KEY -> {
                // A key of a map or slice literal is an ordinary expression.
                if (!structKeys(context, out, result)) scope = expression(context, result, out)
            }
            Kind.TYPE -> {
                val types = ArrayList<GoCandidate>()
                val place = context.typeReference ?: context.leaf
                GoScopeCandidates(context).collect(place, GoScopeCandidates.Filter.TYPES, types)
                // `func g(<caret>`: names with their types, as GoLand (`err error`, `base Base`), instead of bare types.
                if (GoNameCompletion.isParameterNamePosition(context)) {
                    GoNameCompletion.parameterItems(context, types, result.prefixMatcher.prefix, out)
                    unimportedPackages(context, result.prefixMatcher, types, out)
                    GoCompletionContributor.emit(out, context, result)
                    return
                }
                out += types
                GoKeywordCandidates.collect(context, out)
                unimportedPackages(context, result.prefixMatcher, types, out)
                scope = types
                typesOnly = true
            }
            Kind.RECEIVER_TYPE -> receiverTypes(context, out)
            Kind.SELECTOR -> GoMemberCandidates(context).collect(out, typesOnly = false)
            Kind.TYPE_SELECTOR -> GoMemberCandidates(context).collect(out, typesOnly = true)
            Kind.TOP_LEVEL -> {
                GoKeywordCandidates.collect(context, out)
                GoTopLevelTemplates.collect(context, out)
            }
            Kind.SWITCH_BODY -> GoKeywordCandidates.collect(context, out)
            else -> return
        }
        GoCompletionContributor.emit(out, context, result)
        if (scope != null) later(context, result, scope, typesOnly, accept = null)
    }

    /**
     * The costly sources, after the cheap set is in the list: chains ([GoChainCandidates], not in type positions) and exported
     * names of unimported project packages ([GoProjectMemberCandidates]). Basic completion asks for [MIN_PREFIX] typed characters
     * (and restarts when the prefix reaches it); smart completion ([accept] given) builds chains whatever the prefix.
     */
    fun later(context: GoCompletionContext, result: CompletionResultSet, scope: List<GoCandidate>, typesOnly: Boolean, accept: ((GoCandidate) -> Boolean)?) {
        val matcher = result.prefixMatcher
        val longEnough = matcher.prefix.length >= MIN_PREFIX
        if (!longEnough) result.restartCompletionOnPrefixChange(StandardPatterns.string().withLength(MIN_PREFIX))
        if (result.isStopped || (!longEnough && accept == null)) return
        val extra = ArrayList<GoCandidate>()
        if (!typesOnly) {
            val matches = { c: GoCandidate -> matcher.prefixMatches(c.name) || matcher.prefixMatches(c.lookupString) }
            GoChainCandidates(context).collect(scope, { c -> matches(c) && (accept == null || accept(c)) }, extra)
            GoCompletionContributor.emit(extra, context, result)
        }
        if (result.isStopped || !longEnough) return
        extra.clear()
        GoProjectMemberCandidates(context).collect(matcher, typesOnly, scope.mapTo(HashSet()) { it.name }, extra)
        GoCompletionContributor.emit(if (accept == null) extra else extra.filter(accept), context, result)
    }

    private fun expression(context: GoCompletionContext, result: CompletionResultSet, out: MutableList<GoCandidate>): List<GoCandidate> {
        val scope = ArrayList<GoCandidate>()
        val place: GoCompositeElement? = context.reference ?: context.typeReference
        GoScopeCandidates(context).collect(place ?: context.leaf, GoScopeCandidates.Filter.ALL, scope)
        out += scope
        GoKeywordCandidates.collect(context, out)
        GoSnippets.collect(context, scope, out)
        functionLiterals(context, out)
        unimportedPackages(context, result.prefixMatcher, scope, out)
        return scope
    }

    /** The function literal written for an expected function type, and `func() {}()` right after `go` / `defer`. */
    private fun functionLiterals(context: GoCompletionContext, out: MutableList<GoCandidate>) {
        if (!context.isExpression) return
        val statement = context.reference?.parent
        if (statement is GoDeferStatement || statement is GoGoStatement) {
            out += GoSmartLiterals(context).deferredCall()
            return
        }
        context.semantics.expectedType?.let { GoSmartLiterals(context).collectFunctionLiteral(it, out) }
    }

    /**
     * Field names of a struct literal (promoted and embedded ones included) not used yet, after the Fill items ([GoFillStructCompletion]);
     * false when the literal is not a struct.
     */
    private fun structKeys(context: GoCompletionContext, out: MutableList<GoCandidate>, result: CompletionResultSet): Boolean {
        val literal = context.literalValue ?: return false
        val literalType = context.semantics.literalType(literal) ?: return false
        if (GoCompletionSemantics.derefUnderlying(literalType) !is GoStructType) return false
        val elements = GoPsiUtil.children(literal, GoElement::class.java)
        val current = PsiTreeUtil.getParentOfType(context.leaf, GoElement::class.java)
        // Positional literals (`T{1, 2}`) take no keys.
        if (!context.keyOnly && elements.any { it !== current && it.key == null }) return true
        GoFillStructCompletion.collect(context, literal, current, result)
        val used = elements.filter { it !== current }.mapNotNull { (it.key?.expression as? GoReferenceExpression)?.identifier?.text }.toSet()
        GoMemberCandidates(context).structKeys(literalType, used, out)
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
