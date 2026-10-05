package io.github.golangsupport.ml

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.github.completionml.core.ngram.NgramModel
import io.github.completionml.core.rank.FeatureExtractor
import io.github.completionml.core.rank.LinearRanker
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The trained models of the Go ranker: the n-gram language model (`lm.cml`) and the linear ranker (`rank.cml`), either bundled
 * in the plugin under `ml/go/` (a build with `-PmlEnabled=true` copies them from `-Pml.models`) or taken from the directory of
 * [GoMlSettings.modelDirectory]. Loading takes a second for a 20 MB language model, so it happens once, in the background, on the
 * first completion; until then the ranker abstains.
 */
@Service(Service.Level.APP)
class GoMlModels {
    /** Everything the ranker needs, built once per model set. */
    class Loaded(val lm: NgramModel, val ranker: LinearRanker, val source: String) {
        val extractor = FeatureExtractor(GoMlFeatures.schema, lm.vocab, lm, CACHE_LAMBDA)
        val description: String get() = "$source: ${lm.vocab.size} words, ${ranker.schema.size} weights"
    }

    private sealed class State {
        object Idle : State()
        object Loading : State()
        class Ready(val loaded: Loaded?, val key: String, val error: String?) : State()
    }

    private val state = AtomicReference<State>(State.Idle)

    /** The models for [modelDirectory] (empty: bundled), or null while they load or when there are none. */
    fun get(modelDirectory: String): Loaded? {
        val key = modelDirectory.trim()
        when (val s = state.get()) {
            is State.Ready -> if (s.key == key) return s.loaded
            State.Loading -> return null
            State.Idle -> {}
        }
        if (state.compareAndSet(state.get().takeUnless { it === State.Loading } ?: return null, State.Loading)) {
            ApplicationManager.getApplication().executeOnPooledThread { state.set(load(key)) }
        }
        return null
    }

    /** Status for the settings page: what is loaded, or why nothing is. */
    fun status(modelDirectory: String): String = when (val s = state.get()) {
        State.Idle -> if (isBundled || modelDirectory.isNotBlank()) "models not loaded yet (the first completion loads them)" else "no bundled models"
        State.Loading -> "loading…"
        is State.Ready -> s.loaded?.description ?: (s.error ?: "no models")
    }

    /** Forgets the loaded models so that the next completion reads them again (after the directory setting changed). */
    fun reset() = state.set(State.Idle)

    private fun load(key: String): State.Ready {
        val started = System.currentTimeMillis()
        return try {
            val loaded = if (key.isEmpty()) loadBundled() else loadDirectory(File(key))
            if (loaded != null) {
                check(loaded.ranker.schema.names == GoMlFeatures.schema.names) { "rank.cml was trained for another feature set; retrain with this plugin's export" }
                LOG.info("ML completion models: ${loaded.description} in ${System.currentTimeMillis() - started} ms")
            }
            State.Ready(loaded, key, if (loaded == null) "no models in ${key.ifEmpty { "the plugin" }}" else null)
        } catch (e: Exception) {
            LOG.warn("ML completion models could not be loaded", e)
            State.Ready(null, key, e.message ?: e.toString())
        }
    }

    private fun loadBundled(): Loaded? {
        val cl = GoMlModels::class.java.classLoader
        val lm = cl.getResourceAsStream("$RESOURCE_DIR/lm.cml") ?: return null
        val rank = cl.getResourceAsStream("$RESOURCE_DIR/rank.cml") ?: return null
        return Loaded(NgramModel.read(lm, "bundled lm.cml"), LinearRanker.read(rank, "bundled rank.cml"), "bundled")
    }

    private fun loadDirectory(dir: File): Loaded? {
        val lm = File(dir, "lm.cml"); val rank = File(dir, "rank.cml")
        if (!lm.isFile || !rank.isFile) return null
        return Loaded(NgramModel.read(lm), LinearRanker.read(rank), dir.path)
    }

    companion object {
        private val LOG = logger<GoMlModels>()
        /** Weight of the per-file cache in the mixed language model; the value the models were trained with. */
        const val CACHE_LAMBDA = 0.3
        private const val RESOURCE_DIR = "ml/go"

        fun getInstance(): GoMlModels = service()

        /** True in a build that carries the models (and therefore shows the Smart Completion settings page). */
        val isBundled: Boolean by lazy { GoMlModels::class.java.classLoader.getResource("$RESOURCE_DIR/rank.cml") != null }
    }
}
