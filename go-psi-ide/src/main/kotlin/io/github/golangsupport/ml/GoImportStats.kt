package io.github.golangsupport.ml

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import io.github.completionml.core.imports.ImportsModel
import io.github.golangsupport.ide.completion.GoCompletionAssistSettings
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Corpus import statistics (engine experiment e20, `ImportsModel`): which import path usually supplies a package qualifier (`log` →
 * `log` / `github.com/sirupsen/logrus` / `go.uber.org/zap`) or an exported name (`Client` → `net/http`), given the imports the file
 * already has (co-import PMI: `rand` is `crypto/rand` next to `crypto/sha256` and `math/rand` next to `time`). Only ORDERS candidates
 * the plugin found itself — never adds a path PSI does not know. On the test fold: top-1 0.717 with the context vs 0.685 by frequency.
 *
 * Used by the add-import fix ([io.github.golangsupport.ide.inspections.GoAddImportFix]: the default of the popup / the first intention)
 * and by the completion of unimported packages and their members ([io.github.golangsupport.ide.completion.GoImportStatsWeigher], inside the
 * unimported bucket only). Off with [GoCompletionAssistSettings.importStatsEnabled].
 *
 * The artifact `go-imports-e20.cml` (2.9 MB, ~100 ms to read, 1–3 µs a query) comes from the directory of [GoMlSettings.modelDirectory],
 * else from the plugin resources (`ml/go/`, the ML build), else from `ml-models/go` next to the installed plugin; none of them: the feature is
 * silently off. Loaded once on a pooled thread on the first query — [model] answers null until then, and the callers keep their own order.
 */
@Service(Service.Level.APP)
class GoImportStats {
    private sealed class State {
        object Idle : State()
        object Loading : State()
        class Ready(val model: ImportsModel?, val key: String, val error: String?) : State()
    }

    private val state = AtomicReference<State>(State.Idle)

    /** True when the setting is on (the model may still be absent or loading). */
    val enabled: Boolean get() = GoCompletionAssistSettings.getInstance().importStatsEnabled

    /** The model, or null while it loads, when there is none, or when the setting is off; the first call starts the load (never blocks). */
    fun model(): ImportsModel? {
        if (!enabled) return null
        val key = GoMlSettings.getInstance().modelDirectory.trim()
        when (val s = state.get()) {
            is State.Ready -> if (s.key == key) return s.model
            State.Loading -> return null
            State.Idle -> {}
        }
        if (state.compareAndSet(state.get().takeUnless { it === State.Loading } ?: return null, State.Loading)) {
            ApplicationManager.getApplication().executeOnPooledThread { state.set(load(key)) }
        }
        return null
    }

    /**
     * The score of [path] for the unresolved [name] (a package qualifier or an exported identifier) given the file's [currentImports]:
     * `ln p(path | name) + λ·ΣPMI`, larger is better; null when the model is not there, [name] was never seen or [path] does not supply it.
     */
    fun score(name: String, path: String, currentImports: Collection<String>): Float? {
        val model = model() ?: return null
        return model.rankImports(name, currentImports).firstOrNull { it.path == path }?.score
    }

    /**
     * [candidates] ordered for [name]: the paths the corpus knows for [name] by score (best first), then the rest in their original order.
     * The list itself when the model is not there or [name] is unknown (nothing moves then).
     */
    fun order(name: String, candidates: List<String>, currentImports: Collection<String>): List<String> {
        if (candidates.size < 2) return candidates
        val model = model() ?: return candidates
        val ranked = model.rankImports(name, currentImports)
        if (ranked.isEmpty()) return candidates
        val scores = HashMap<String, Float>(ranked.size * 2)
        for (s in ranked) scores[s.path] = s.score
        if (candidates.none { it in scores }) return candidates
        val known = candidates.withIndex().filter { it.value in scores }.sortedWith(compareByDescending<IndexedValue<String>> { scores[it.value] }.thenBy { it.index })
        return known.map { it.value } + candidates.filter { it !in scores }
    }

    /** Forgets the loaded model so that the directory of the settings is read again ([GoMlModels.reset] calls it). */
    fun reset() { state.set(State.Idle) }

    /** Status for the settings page: what is loaded, or why nothing is. */
    fun status(): String = if (!enabled) "off" else when (val s = state.get()) {
        State.Idle -> "not loaded yet (the first import or completion loads it)"
        State.Loading -> "loading…"
        is State.Ready -> s.model?.let { "${s.key.ifEmpty { "bundled" }}: ${it.paths.size} import paths, ${it.nameCount} names" } ?: (s.error ?: "no import statistics")
    }

    /** Loads on the calling thread and waits for it (tests). */
    @TestOnly
    fun loadSynchronously(): ImportsModel? {
        val key = GoMlSettings.getInstance().modelDirectory.trim()
        val s = state.get()
        if (s is State.Ready && s.key == key) return s.model
        return load(key).also { state.set(it) }.model
    }

    private fun load(key: String): State.Ready {
        val started = System.currentTimeMillis()
        return try {
            val model = if (key.isNotEmpty()) directoryModel(File(key)) else null
            val loaded = model ?: bundledModel() ?: pluginDirectoryModel()
            if (loaded != null) LOG.info("Go import statistics: ${loaded.paths.size} paths, ${loaded.nameCount} names in ${System.currentTimeMillis() - started} ms")
            State.Ready(loaded, key, if (loaded == null) "no import statistics in ${key.ifEmpty { "the plugin" }}" else null)
        } catch (e: Exception) {
            LOG.warn("Go import statistics could not be loaded", e)
            State.Ready(null, key, e.message ?: e.toString())
        }
    }

    private fun directoryModel(dir: File): ImportsModel? {
        val file = File(dir, MODEL).takeIf { it.isFile }
            ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".cml") && "imports" in f.name }?.minByOrNull { it.name }
            ?: return null
        return ImportsModel.read(file)
    }

    private fun bundledModel(): ImportsModel? =
        GoImportStats::class.java.classLoader.getResourceAsStream("$RESOURCE_DIR/$MODEL")?.use { ImportsModel.read(it, "bundled $MODEL") }

    private fun pluginDirectoryModel(): ImportsModel? {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: return null
        return directoryModel(plugin.pluginPath.resolve("ml-models").resolve("go").toFile())
    }

    companion object {
        private val LOG = logger<GoImportStats>()
        /** The artifact of `ml-models/go` (the ML build copies it to `ml/go/`). */
        const val MODEL = "go-imports-e20.cml"
        private const val RESOURCE_DIR = "ml/go"
        private const val PLUGIN_ID = "io.github.golangsupport"

        fun getInstance(): GoImportStats = service()
    }
}
