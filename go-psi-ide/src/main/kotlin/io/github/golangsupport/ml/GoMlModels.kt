package io.github.golangsupport.ml

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import io.github.completionml.core.bpe.BpeTokenizer
import io.github.completionml.core.ngram.NgramModel
import io.github.completionml.core.nn.NnCompletion
import io.github.completionml.core.nn.NnFormat
import io.github.completionml.core.nn.NnModel
import io.github.completionml.core.nn.NnSession
import io.github.completionml.core.nn.native.NativeLib
import io.github.completionml.core.rank.FeatureExtractor
import io.github.completionml.core.rank.Ranker
import io.github.completionml.core.rank.Rankers
import java.io.File
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The trained models of the Go plugin, either bundled under `ml/go/` (a build with `-PmlEnabled=true`) or taken from the directory of
 * [GoMlSettings.modelDirectory]:
 *  - the ranker pair — the n-gram language model (`lm.cml`) and the linear ranker (`rank.cml`) of [GoMlCompletionRanker]; loaded once,
 *    in the background, on the first completion; until then (and in a build without them) the ranker abstains;
 *  - the network pair — the transformer [NN_MODEL] and its vocabulary [NN_VOCAB] of
 *    the grey text ([GoNnInlineCompletionProvider]). One [NnModel] per application (~100 MB, the int8 weights memory-mapped from a copy of the
 *    resource in the system directory), loaded and warmed up when a project with Go files is open ([preload]) or on the first Go editor.
 *    The model is not reentrant, so everything that touches it — loading, `complete`, [prefill], closing sessions — runs on one daemon
 *    thread of this service ([GoNnEngine.complete] switches to it); the KV-cache sessions are kept per editor on that thread.
 *    A directory setting without the network files falls back to the bundled network.
 */
@Service(Service.Level.APP)
class GoMlModels : GoNnEngine, Disposable {
    /** Everything the ranker needs, built once per model set. */
    class Loaded(val lm: NgramModel, val ranker: Ranker, val source: String) {
        val extractor = FeatureExtractor(GoMlFeatures.schema, lm.vocab, lm, CACHE_LAMBDA)
        val description: String get() = "$source: ${lm.vocab.size} words, ${ranker.description}"
    }

    /**
     * The loaded network: [completion] with the options of the load; [completionFor] a variant per gate (`Options.copy` per call — the gate
     * after a `.` differs from the main one, so the variants are kept, not rebuilt on every switch). On the model's thread only.
     */
    class Nn(val completion: NnCompletion, val model: NnModel, val name: String) {
        private val variants = HashMap<Pair<Double, Boolean>, NnCompletion>()
        internal fun completionFor(threshold: Double, suppressPunctOnly: Boolean): NnCompletion =
            if (completion.options.showThreshold == threshold && completion.options.suppressPunctOnly == suppressPunctOnly) completion
            else variants.getOrPut(threshold to suppressPunctOnly) { NnCompletion(model, completion.tok, completion.options.copy(showThreshold = threshold, suppressPunctOnly = suppressPunctOnly)) }
    }

    private sealed class State {
        object Idle : State()
        object Loading : State()
        class Ready(val loaded: Loaded?, val key: String, val error: String?) : State()
    }

    private sealed class NnState {
        object Idle : NnState()
        object Loading : NnState()
        class Ready(val nn: Nn?, val key: String, val error: String?) : NnState()
    }

    private val state = AtomicReference<State>(State.Idle)

    // --- network state: [nnState] and [generation] under the lock; [current] and [sessions] only on [thread]
    private var nnState: NnState = NnState.Idle
    private var generation = 0
    private var current: Nn? = null
    private val sessions = HashMap<Any, NnSession>()
    /** The model's thread: low priority for the background work, normal while a completion waits ([NnThread]). */
    private val thread = NnThread("Go NN completion")
    private val shown = AtomicInteger()
    private val accepted = AtomicInteger()

    /** Suggestions of the network shown since the start of the IDE (its own policy said `show`). */
    val shownCount: Int get() = shown.get()
    /** Suggestions of the network accepted with Tab since the start of the IDE. */
    val acceptedCount: Int get() = accepted.get()

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

    /**
     * The network for [modelDirectory] (empty: bundled), or null while it loads, when there is none or when the grey text is off. The first
     * call starts loading on the network's thread; never blocks, so it may be called on the EDT (the editor listener warms up this way).
     */
    fun nn(modelDirectory: String): Nn? {
        val settings = GoMlSettings.getInstance()
        if (!settings.inlineEnabled) return null
        val key = nnKey(modelDirectory)
        val g = synchronized(this) {
            when (val s = nnState) {
                is NnState.Ready -> if (s.key == key) return s.nn
                NnState.Loading -> return null
                NnState.Idle -> {}
            }
            nnState = NnState.Loading
            generation
        }
        thread.execute {
            closeNn()
            val ready = loadNn(key)
            val stale = synchronized(this) { (generation != g).also { if (!it) { nnState = ready; current = ready.nn } } }
            if (stale) ready.nn?.model?.close()
        }
        return null
    }

    override suspend fun complete(editor: Any, context: GoNnInline.Context): GoNnInline.Answer? {
        val settings = GoMlSettings.getInstance()
        nn(settings.modelDirectory) ?: return null
        return thread.complete {
            // a reset between the check and this task closed the model: [current] is null then
            val nn = current ?: return@complete null
            // the gate of this caret: lower right after a `.` (the model is as right there, less sure) and on a line with nothing typed yet
            // (the first word of a statement is one guess among a few); over the code tokens only, so a guessed string literal does not hide
            // a certain line (see GoNnInline.codeConfidence)
            val gate = GoNnInline.gate(context.before, settings.inlineThreshold, settings.inlineDotThreshold, settings.inlineEmptyLineThreshold)
            val completion = nn.completionFor(gate, !settings.inlineShowClosers)
            val session = sessions.getOrPut(editor) { nn.model.newSession(SESSION_CAPACITY) }
            val started = System.nanoTime()
            val r = completion.complete(context.path, context.before, context.after, session)
            val sound = r.text.isNotEmpty() && !r.repeated && !r.healMiss && !(r.punctOnly && !settings.inlineShowClosers)
            val code = if (settings.inlineGuessStrings && !r.show && sound)
                GoNnInline.codeConfidence(GoNnInline.lineBefore(context.before, r.typed.size), r.tokens.map { completion.tok.tokenBytes(it) }, r.logProbs, r.stopLogProb)
            else r.confProd
            var show = r.show || sound && code >= gate
            var text = r.textString
            // the whole line is not certain: its certain start may be (`len(o.items)` before a ` ==` the model is unsure of)
            if (!show && sound && r.tokens.isNotEmpty()) {
                val prefix = GoNnInline.certainPrefix(r.tokens.map { completion.tok.tokenBytes(it) }, r.logProbs, r.typed.size, gate)
                if (prefix != null) { text = String(prefix, Charsets.UTF_8); show = true }
            }
            if (show) shown.incrementAndGet()
            val line = "NN completion ${(System.nanoTime() - started) / 1_000_000} ms, confProd ${"%.3f".format(r.confProd)}, code ${"%.3f".format(code)}, gate $gate, show $show: $text${if (text != r.textString) " (of: ${r.textString})" else ""}"
            if (settings.inlineDebugLog) debugSink?.invoke(line) ?: LOG.info(line) else LOG.debug(line)
            GoNnInline.Answer(text, show, maxOf(r.confProd, code))
        }
    }

    override fun accepted() { accepted.incrementAndGet() }

    /**
     * Fills the KV cache of [editor]'s session with the prompt of [context] (the caret where the file was opened) on the model's thread, so the
     * first grey text there reuses it instead of a cold prefill (~150 ms for 1 500 tokens). Never blocks; skipped while the network is not
     * loaded (the load queued before it by [nn] runs first) and when a completion is already waiting — it prefills itself.
     */
    fun prefill(editor: Any, context: () -> GoNnInline.Context) {
        if (thread.isShutdown) return
        thread.execute {
            val nn = current ?: return@execute
            if (thread.completionWaiting) return@execute
            val session = sessions.getOrPut(editor) { nn.model.newSession(SESSION_CAPACITY) }
            val started = System.nanoTime()
            val n = GoNnInline.prefill(nn.completion, session, context())
            LOG.debug { "NN prefill of $n tokens in ${(System.nanoTime() - started) / 1_000_000} ms" }
        }
    }

    /**
     * Loads and warms up what the settings ask for and the build carries — the network and the ranker — without a completion asking
     * first: the project activity calls it once the project has Go files, the settings page after the network choice changed.
     */
    fun preload() {
        val settings = GoMlSettings.getInstance()
        if (settings.inlineEnabled && (isNnBundled || settings.modelDirectory.isNotBlank())) nn(settings.modelDirectory)
        if (settings.enabled && (isRankerBundled || settings.modelDirectory.isNotBlank())) get(settings.modelDirectory)
    }

    /** Frees the KV cache of a closed editor (native memory). */
    fun release(editor: Any) { if (!thread.isShutdown) thread.execute { sessions.remove(editor)?.close() } }

    /** Status for the settings page: what is loaded, or why nothing is. */
    fun status(modelDirectory: String): String = when (val s = state.get()) {
        State.Idle -> if (isRankerBundled || modelDirectory.isNotBlank()) "models not loaded yet (the first completion loads them)" else "no bundled models"
        State.Loading -> "loading…"
        is State.Ready -> s.loaded?.description ?: (s.error ?: "no models")
    }

    /** Status of the inline (grey text) network for the settings page: model name and kernels, or why nothing is loaded. */
    fun nnStatus(modelDirectory: String): String = when (val s = synchronized(this) { nnState }) {
        NnState.Idle -> when {
            !GoMlSettings.getInstance().inlineEnabled -> "off"
            isNnBundled || modelDirectory.isNotBlank() -> "not loaded yet (the first Go project or editor loads it)"
            else -> "no bundled network"
        }
        NnState.Loading -> "loading…"
        is NnState.Ready -> s.nn?.let { "model ${it.name}, ${it.model.nThreads} threads, kernels: ${NativeLib.status}; shown ${shown.get()}, accepted ${accepted.get()}" }
            ?: (s.error ?: "no network")
    }

    /** The name of the loaded network (`go-nn-50m-caret-ft5e5`; earlier `go-nn-50m-e3-lr2e3`), or null while none is. */
    val nnName: String? get() = (synchronized(this) { nnState } as? NnState.Ready)?.nn?.name

    /**
     * Forgets the loaded models and drops the editor sessions, so that the models of the settings are read again (after the directory or the
     * network choice changed); what the settings still ask for is loaded again in the background right away.
     */
    fun reset() {
        state.set(State.Idle)
        GoImportStats.getInstance().reset()
        synchronized(this) { generation++; nnState = NnState.Idle }
        thread.execute { closeNn() }
        preload()
    }

    override fun dispose() {
        synchronized(this) { generation++; nnState = NnState.Idle }
        thread.execute { closeNn() }
        thread.shutdown()
    }

    /** On [thread]: closes the sessions and the model. */
    private fun closeNn() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        current?.model?.close()
        current = null
    }

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
        return Loaded(NgramModel.read(lm, "bundled lm.cml"), Rankers.read(rank, "bundled rank.cml"), "bundled")
    }

    private fun loadDirectory(dir: File): Loaded? {
        val lm = File(dir, "lm.cml"); val rank = File(dir, "rank.cml")
        if (!lm.isFile || !rank.isFile) return null
        return Loaded(NgramModel.read(lm), Rankers.read(rank), dir.path)
    }

    /** On [thread]: reads, builds and warms up the network of [key] (see [nnKey]). */
    private fun loadNn(key: String): NnState.Ready = try {
        val dir = key.substringBefore(KEY_SEPARATOR)
        val name = key.substringAfter(KEY_SEPARATOR)
        val nn = loadNn(dir.takeIf { it.isNotEmpty() }?.let(::File), GoMlSettings.getInstance().let { NnCompletion.Options(showThreshold = it.inlineThreshold, suppressPunctOnly = !it.inlineShowClosers) }, name)
        NnState.Ready(nn, key, if (nn == null) "no network $name in ${dir.ifEmpty { "the plugin" }}" else null)
    } catch (e: Throwable) {
        LOG.warn("ML inline completion network could not be loaded", e)
        NnState.Ready(null, key, e.message ?: e.toString())
    }

    companion object {
        private val LOG = logger<GoMlModels>()
        /** Weight of the per-file cache in the mixed language model; the value the models were trained with. */
        const val CACHE_LAMBDA = 0.3
        private const val RESOURCE_DIR = "ml/go"
        /** The bundled transformer and its BPE vocabulary (`ml-models/go`, copied by the ML build). */
        const val NN_MODEL = "go-nn-50m-caret-ft5e5.cml"
        const val NN_VOCAB = "go-16384.bpe"
        private const val KEY_SEPARATOR = "\u0000"
        /** KV cache of an editor's session: the prompt (≤ 2000 tokens) and the generated line; capped by the model's context. */
        private const val SESSION_CAPACITY = 2048
        private const val WARM_UP = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Prin"

        fun getInstance(): GoMlModels = service()

        private fun resource(name: String) = GoMlModels::class.java.classLoader.getResource("$RESOURCE_DIR/$name")
        private val isRankerBundled: Boolean by lazy { resource("rank.cml") != null }
        /** Where the debug lines go with [GoMlSettings.inlineDebugLog] on: the host's plugin log (set by its bridge), else idea.log at INFO. */
        @Volatile var debugSink: ((String) -> Unit)? = null

        /** True when the build carries the grey-text network. */
        val isNnBundled: Boolean by lazy { resource(NN_MODEL) != null && resource(NN_VOCAB) != null }
        /** The key of a network load: the directory and the model file. */
        private fun nnKey(modelDirectory: String): String = modelDirectory.trim().let { "$it$KEY_SEPARATOR$NN_MODEL" }
        /** True in a build that carries any of the models (and therefore shows the Smart Completion settings page). */
        val isBundled: Boolean by lazy { isRankerBundled || isNnBundled }

        /**
         * Builds and warms up the network [model] from [dir] (null: bundled). In a directory: [model] and [NN_VOCAB], or else the only `*.bpe`
         * with the first `*-nn-*.cml`; no such files there — the bundled network. Null when there is none at all. Blocking (a second).
         */
        fun loadNn(dir: File?, options: NnCompletion.Options = NnCompletion.Options(), model: String = NN_MODEL): Nn? {
            val started = System.currentTimeMillis()
            val (modelFile, vocab) = dir?.let { directoryNn(it, model) } ?: bundledNn(model) ?: return null
            val model = NnModel(NnFormat.read(modelFile), nThreads = minOf(8, Runtime.getRuntime().availableProcessors()))
            val nn = try {
                Nn(NnCompletion(model, vocab, options), model, modelFile.name.removeSuffix(".cml"))
            } catch (e: Throwable) { model.close(); throw e }
            val loadedMillis = System.currentTimeMillis() - started
            model.newSession(SESSION_CAPACITY).use { nn.completion.complete("main.go".toByteArray(), WARM_UP.toByteArray(), ByteArray(0), it) }
            LOG.info("ML inline completion: ${nn.name} (${dir ?: "bundled"}) loaded in $loadedMillis ms, warmed up in ${System.currentTimeMillis() - started - loadedMillis} ms, " +
                "${model.nThreads} threads, kernels: ${NativeLib.status}")
            return nn
        }

        private fun directoryNn(dir: File, name: String): Pair<File, BpeTokenizer>? {
            val model = File(dir, name).takeIf { it.isFile } ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".cml") && "-nn-" in f.name }?.minByOrNull { it.name } ?: return null
            val vocab = File(dir, NN_VOCAB).takeIf { it.isFile } ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".bpe") }?.singleOrNull() ?: return null
            return model to BpeTokenizer.load(vocab.toPath())
        }

        private fun bundledNn(name: String): Pair<File, BpeTokenizer>? {
            val cl = GoMlModels::class.java.classLoader
            val vocab = cl.getResourceAsStream("$RESOURCE_DIR/$NN_VOCAB")?.use { BpeTokenizer.load(it) } ?: return null
            return (extract("$RESOURCE_DIR/$name") ?: extract("$RESOURCE_DIR/$NN_MODEL") ?: return null) to vocab
        }

        /**
         * Copies a bundled resource to `<system>/go-plugin/ml/<sha256>/` (like the bundled delve) for the memory mapping of [NnFormat.read].
         * The directories are the user's only (0700 where POSIX permissions exist); an existing copy is reused only when its SHA-256 equals
         * the resource's, otherwise it is written again — to a temp name in the same directory, then an atomic move (another IDE of the
         * same system directory may be extracting it too; on Windows a mapped file is locked, then the copy must already be right).
         */
        private fun extract(resource: String): File? {
            val cl = GoMlModels::class.java.classLoader
            val sha = cl.getResourceAsStream(resource)?.use(::sha256) ?: return null
            val dir = privateDirectories(Path.of(PathManager.getSystemPath(), "go-plugin", "ml", sha))
            val out = dir.resolve(resource.substringAfterLast('/'))
            if (Files.isRegularFile(out) && Files.newInputStream(out).use(::sha256) == sha) return out.toFile()
            val tmp = Files.createTempFile(dir, "model", ".tmp")
            try {
                cl.getResourceAsStream(resource)!!.use { s -> Files.newOutputStream(tmp).use { s.copyTo(it, 1 shl 16) } }
                check(Files.newInputStream(tmp).use(::sha256) == sha) { "$resource changed while it was extracted" }
                try { Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (e: Exception) { if (!(Files.isRegularFile(out) && Files.newInputStream(out).use(::sha256) == sha)) throw IllegalStateException("cannot create $out", e) }
            } finally { Files.deleteIfExists(tmp) }
            return out.toFile()
        }

        private fun sha256(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) { val n = stream.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Creates [dir] and its missing parents with 0700 where the file system has POSIX permissions (not on Windows). */
        private fun privateDirectories(dir: Path): Path {
            val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
            if (!posix) return Files.createDirectories(dir)
            val missing = generateSequence(dir) { it.parent }.takeWhile { !Files.exists(it) }.toList().asReversed()
            for (d in missing) try {
                Files.createDirectory(d, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (_: FileAlreadyExistsException) {}
            return dir
        }
    }
}
