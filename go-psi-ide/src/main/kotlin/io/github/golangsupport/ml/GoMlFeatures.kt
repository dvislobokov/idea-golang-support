package io.github.golangsupport.ml

import com.intellij.psi.PsiElement
import io.github.completionml.core.lex.GoLanguage
import io.github.completionml.core.rank.FeatureSchema
import io.github.completionml.core.rank.ProxyExampleGenerator
import io.github.completionml.core.spi.ContextKind
import io.github.completionml.core.spi.MlLanguage
import io.github.completionml.core.spi.MlToken
import io.github.golangsupport.ide.completion.GoCandidateKind
import io.github.golangsupport.ide.completion.GoScopeLevel
import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext
import kotlin.math.abs
import kotlin.math.ln

/**
 * The Go adapter of the shared ML completion engine (`ml/docs/ADAPTER.md`): the lexer-only language description plus
 * the language block of ranker features. The `ml` package is internal to go-psi-ide: the offline dataset export
 * (test source set) and the IDE ranker compute every feature through this object, which is what keeps training and
 * serving identical.
 */
internal object GoMlLanguage : MlLanguage by GoLanguage {
    override val rankFeatures: List<String> get() = GoMlFeatures.NAMES
}

/**
 * Per-candidate features derived from the plugin's own completion machinery. Inputs are exactly what the
 * `GoCompletionRanker` extension point receives ([GoCompletionRankingContext], [GoCompletionCandidate]); the offline
 * export builds the same objects from the lookup elements of a headless completion, so both paths share this code.
 */
internal object GoMlFeatures {
    /** The language block, in order. Values are small floats: flags, levels, `ln(1 + x)` for distances and ranks. */
    val NAMES: List<String> = listOf(
        "kind_local",           // GoCandidateKind.LOCAL
        "kind_param",           // PARAMETER (incl. receiver, type parameters at level 1)
        "kind_var_const",       // VARIABLE, CONSTANT (package level)
        "kind_func",            // FUNCTION, BUILTIN_FUNCTION
        "kind_method",          // METHOD
        "kind_field",           // FIELD, STRUCT_KEY
        "kind_type",            // TYPE, TYPE_PARAMETER, BUILTIN_TYPE
        "kind_package",         // PACKAGE, PACKAGE_NAME, IMPORT_PATH
        "kind_keyword",         // KEYWORD, SNIPPET, LITERAL, LABEL
        "kind_builtin_const",   // BUILTIN_CONSTANT (nil, true, false, iota)
        "scope_level",          // GoScopeLevel 0..6 (members: embedding depth)
        "needs_import",         // candidate inserts an import (level >= UNIMPORTED)
        "expected_type_match",  // 0 none, 1 assignable, 2 identical (GoLookupElementFactory.expectedMatch)
        "list_has_expected",    // some candidate of the list matches an expected type (= an expected type exists)
        "declared_in_file",     // the declaration is in the completed file
        "decl_distance_log",    // ln(1 + |caret - declaration offset|) / 10 when declared_in_file, else 0
        "rule_rank_log",        // ln(1 + rank under the plugin's deterministic order: expected match desc, scope level asc, name)
    )

    val schema: FeatureSchema = FeatureSchema.common(languageFeatures = NAMES)

    /** The language block for every candidate of one list, in list order. */
    fun languageBlock(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): Array<FloatArray> {
        val hasExpected = if (candidates.any { it.expectedTypeMatch > 0 }) 1f else 0f
        val ruleOrder = candidates.indices.sortedWith(compareBy({ -candidates[it].expectedTypeMatch }, { candidates[it].scopeLevel }, { candidates[it].lookupString }))
        val ruleRank = IntArray(candidates.size).also { r -> ruleOrder.forEachIndexed { rank, c -> r[c] = rank } }
        return Array(candidates.size) { c ->
            val cand = candidates[c]
            val kind = runCatching { GoCandidateKind.valueOf(cand.kind) }.getOrNull()
            val f = FloatArray(NAMES.size)
            when (kind) {
                GoCandidateKind.LOCAL -> f[0] = 1f
                GoCandidateKind.PARAMETER -> f[1] = 1f
                GoCandidateKind.VARIABLE, GoCandidateKind.CONSTANT -> f[2] = 1f
                GoCandidateKind.FUNCTION, GoCandidateKind.BUILTIN_FUNCTION -> f[3] = 1f
                GoCandidateKind.METHOD -> f[4] = 1f
                GoCandidateKind.FIELD, GoCandidateKind.STRUCT_KEY -> f[5] = 1f
                GoCandidateKind.TYPE, GoCandidateKind.TYPE_PARAMETER, GoCandidateKind.BUILTIN_TYPE -> f[6] = 1f
                GoCandidateKind.PACKAGE, GoCandidateKind.PACKAGE_NAME, GoCandidateKind.IMPORT_PATH -> f[7] = 1f
                GoCandidateKind.KEYWORD, GoCandidateKind.SNIPPET, GoCandidateKind.LITERAL, GoCandidateKind.LABEL -> f[8] = 1f
                GoCandidateKind.BUILTIN_CONSTANT -> f[9] = 1f
                null -> {}
            }
            f[10] = cand.scopeLevel.toFloat()
            f[11] = if (cand.scopeLevel >= GoScopeLevel.UNIMPORTED) 1f else 0f
            f[12] = cand.expectedTypeMatch.toFloat()
            f[13] = hasExpected
            val declOffset = declarationOffsetInFile(context, cand.element)
            if (declOffset != null) { f[14] = 1f; f[15] = (ln(1.0 + abs(context.offset - declOffset)) / 10).toFloat() }
            f[16] = ln(1.0 + ruleRank[c]).toFloat()
            f
        }
    }

    /**
     * Offset of the declaration when it is in the completed file (the original or completion's light copy of it), else null.
     * Declarations in other files are stub-backed and are never touched: reading their offset would load the AST.
     */
    private fun declarationOffsetInFile(context: GoCompletionRankingContext, element: PsiElement?): Int? {
        if (element == null || !element.isValid) return null
        val file = element.containingFile ?: return null
        // completion runs on a light copy of the file: same name, no virtual file behind it
        val same = file === context.file || (file.virtualFile == null && file.name == context.file.name)
        if (!same) return null
        return runCatching { element.textOffset }.getOrNull()
    }

    /** Context kind of the schema's one-hot block, from the lexer tokens before the caret (shared with the proxy generator). */
    fun contextKind(tokens: List<MlToken>, caretTokenIndex: Int): ContextKind =
        if (caretTokenIndex <= 0) ContextKind.OTHER else ProxyExampleGenerator.contextKind(tokens, caretTokenIndex)
}
