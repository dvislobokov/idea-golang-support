package io.github.golangsupport.ml

import io.github.completionml.core.rank.FeatureSchema
import io.github.completionml.core.rank.FileState
import io.github.golangsupport.ide.completion.GoCompletionAssistSettings
import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext

/**
 * The `completionRanker` of go-psi-ide backed by the shared ML engine: the file before the caret feeds the per-file cache
 * of the language model, every candidate gets the 13 common features ([io.github.completionml.core.rank.FeatureExtractor])
 * and the 17 PSI features ([GoMlFeatures.languageBlock]) — the very code of the offline export — and the linear ranker
 * scores them. Abstains (null) when the feature is off, the models are not loaded yet, or the list is trivial.
 *
 * Cost: tokenising the text before the caret (a few hundred µs for a 2000-line file) plus ~2 µs per candidate.
 */
class GoMlCompletionRanker : GoCompletionRanker {
    /** "ML" after the rows the model ordered, while the setting asks for it (on by default: a tester must see what is the model's). */
    override val marker: String? get() = if (GoMlSettings.getInstance().showMarker) "ML" else null

    /** The scores are logits: the acceptance memory adds `weight × ln(1 + count)` ([GoCompletionAssistSettings.acceptanceWeight], 0.3). */
    override val acceptanceWeight: Double get() = GoCompletionAssistSettings.getInstance().acceptanceWeight

    override fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double>? {
        if (candidates.size < 2) return null
        val settings = GoMlSettings.getInstance()
        if (!settings.enabled) return null
        val models = GoMlModels.getInstance().get(settings.modelDirectory) ?: return null

        // the tokens before the caret, without the prefix being typed: what the export fed to the file state
        val text = context.file.text
        val end = (context.offset - context.prefix.length).coerceIn(0, text.length)
        val tokens = GoMlLanguage.tokenizer.tokens(text.substring(0, end))
        val state = FileState(models.lm.vocab, withCache = GoMlModels.CACHE_LAMBDA > 0)
        state.addAll(tokens)

        val names = Array(candidates.size) { candidates[it].lookupString }
        val language = GoMlFeatures.languageBlock(context, candidates)
        val base = models.extractor.features(state, context.prefix, names, language)
        val kind = GoMlFeatures.contextKind(tokens, tokens.size)
        val full = FloatArray(models.ranker.schema.size)
        return List(candidates.size) { c ->
            FeatureSchema.expand(base[c], kind, full)
            models.ranker.score(full).toDouble()
        }
    }
}
