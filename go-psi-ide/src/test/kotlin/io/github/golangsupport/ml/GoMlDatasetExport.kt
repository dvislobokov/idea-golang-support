package io.github.golangsupport.ml

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.github.completionml.core.ngram.NgramModel
import io.github.completionml.core.rank.ExampleShards
import io.github.completionml.core.rank.FeatureExtractor
import io.github.completionml.core.rank.FileState
import io.github.completionml.core.rank.TrainingExample
import io.github.completionml.core.spi.TokenKind
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.completion.GoCompletionWeigher
import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.project.impl.GoLibraryRootsMode
import io.github.golangsupport.project.impl.GoLibraryRootsPolicy
import io.github.golangsupport.project.impl.GoRootsProvider
import com.intellij.testFramework.replaceService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.types.GoUnknownType
import java.io.File
import java.util.Random

/**
 * Offline dataset export for the ML ranker (`https://github.com/dvislobokov/idea-ml-completion/blob/main/docs/ADAPTER.md` §3): runs the plugin's real completion headlessly over
 * Go repositories and writes one example shard per repository. Not a test of behaviour — a `testIde` task
 * (`:go-psi-ide:mlDataset`) runs it, the regular `test` task excludes `*MlDatasetExport`.
 *
 * System properties (set by Gradle from `-Pml.*`):
 *  - `ml.repos`   file with repository directory names, one per line (e.g. `../ml-data/go/sets/rank.txt`)
 *  - `ml.data`    corpus root containing `repos/<name>/` (default `../ml-data/go`)
 *  - `ml.lm`      the n-gram model whose vocabulary and probabilities feed the common features (trained on other repositories!)
 *  - `ml.out`     output directory for `<repo>.cmlx`
 *  - `ml.perFile` sampled completion positions per file (60), `ml.maxFiles` per repository (0 = all), `ml.cache` λ of the file cache (0.3),
 *    `ml.names` write candidate names into the shards (false), `ml.seed` (7)
 *  - `ml.overlay` directory with `<repo>/go.mod` (+ `go.sum`, `go.work`) copied over the repository copy: the corpus snapshots carry no
 *    module files, so this is what makes import paths and the module cache (`-Dgopsi.gomodcache`, pre-filled by `go mod download`) usable
 *  - `ml.libraryRoots` library roots as in the IDE (`GoRootsProvider`, off in tests by default): `none` (default), `stdlib` (`$GOROOT/src`
 *    is indexed), `all` (also the module-cache directories of the build list); worth it only with a reused `idea.system.path`
 *
 * Per position: the identifier token is cut to a 0–2 character prefix, the caret is put there, `completeBasic()` runs,
 * the answer is the identifier that was in the source. Lists without the answer are counted (the plugin's recall) and skipped.
 */
class GoMlDatasetExport : GoSemanticIdeTestBase() {

    private val data = File(System.getProperty("ml.data") ?: "../ml-data/go")
    private val reposFile = System.getProperty("ml.repos")
    private val lmFile = System.getProperty("ml.lm")
    private val out = File(System.getProperty("ml.out") ?: File(data, "shards").path)
    private val perFile = System.getProperty("ml.perFile")?.toInt() ?: 60
    private val maxFiles = System.getProperty("ml.maxFiles")?.toInt() ?: 0
    private val cacheLambda = System.getProperty("ml.cache")?.toDouble() ?: 0.3
    private val withNames = System.getProperty("ml.names")?.toBoolean() ?: false
    private val seed = System.getProperty("ml.seed")?.toLong() ?: 7L
    private val overlay = System.getProperty("ml.overlay")?.let { File(it) }
    private val libraryRoots = when (System.getProperty("ml.libraryRoots") ?: "none") {
        "none", "false" -> GoLibraryRootsMode.NONE; "stdlib" -> GoLibraryRootsMode.STANDARD_LIBRARY; "all", "true" -> GoLibraryRootsMode.STANDARD_LIBRARY_AND_DEPENDENCIES
        else -> error("ml.libraryRoots must be none|stdlib|all")
    }
    private val maxCandidates = 100
    /** library roots the platform was last told about (`ml.libraryRoots`) */
    private var lastRoots: List<VirtualFile> = emptyList()
    private lateinit var vocab: io.github.completionml.core.vocab.Vocabulary

    fun testExport() {
        requireNotNull(reposFile) { "-Pml.repos=<file with repository names> is required" }
        requireNotNull(lmFile) { "-Pml.lm=<lm.cml> is required (train it on repositories disjoint from ml.repos)" }
        val lm = NgramModel.read(File(lmFile))
        vocab = lm.vocab
        val extractor = FeatureExtractor(GoMlFeatures.schema, lm.vocab, lm, cacheLambda)
        out.mkdirs()
        if (libraryRoots != GoLibraryRootsMode.NONE) {
            val fixed = object : GoLibraryRootsPolicy { override fun modeFor(project: com.intellij.openapi.project.Project) = libraryRoots }
            ApplicationManager.getApplication().replaceService(GoLibraryRootsPolicy::class.java, fixed, testRootDisposable)
            GoRootsProvider.enableInTests(true)
        }
        val repos = File(reposFile).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val total = Stats()
        val t0 = System.currentTimeMillis()
        for (repo in repos) {
            val dir = File(data, "repos/$repo")
            if (!dir.isDirectory) { println("ml: skip $repo (no directory)"); continue }
            val shard = File(out, "$repo.cmlx")
            if (shard.exists()) { println("ml: skip $repo (shard exists)"); continue }
            val stats = Stats()
            val tRepo = System.currentTimeMillis()
            try {
                ExampleShards.Writer(shard, GoMlLanguage.id, "idea-golang-support GoMlDatasetExport lm=${File(lmFile).name}", GoMlFeatures.schema, withNames).use { writer ->
                    exportRepository(dir, extractor, writer, stats)
                }
            } catch (e: Throwable) {
                // one repository must not end the whole export (e.g. a logged error turned into an exception by the test logger)
                println("ml: $repo FAILED ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
            }
            stats.wallMillis = System.currentTimeMillis() - tRepo
            println("ml: $repo ${stats.summary()}")
            total.add(stats)
        }
        if (libraryRoots != GoLibraryRootsMode.NONE) GoRootsProvider.enableInTests(false)
        println("ml: TOTAL ${total.summary()} in ${(System.currentTimeMillis() - t0) / 1000} s; shards in $out")
    }

    private class Stats {
        var files = 0; var positions = 0; var lists = 0; var noAnswer = 0; var empty = 0; var single = 0; var millis = 0L
        /** `.`-positions (the identifier follows a dot) and those whose receiver resolved: a known type, or a package / import. */
        var dot = 0; var dotResolved = 0
        /** copy + VFS refresh + indexing of the repository; the whole repository including start-up of its content root. */
        var indexMillis = 0L; var wallMillis = 0L
        fun add(o: Stats) {
            files += o.files; positions += o.positions; lists += o.lists; noAnswer += o.noAnswer; empty += o.empty; single += o.single; millis += o.millis
            dot += o.dot; dotResolved += o.dotResolved; indexMillis += o.indexMillis; wallMillis += o.wallMillis
        }
        /** recall = lists with the answer / positions where the plugin offered a list at all (declaration names etc. get none). */
        fun summary() = "files=$files positions=$positions lists=$lists answer-missing=$noAnswer no-list=$empty single-insert=$single " +
            "recall=%.3f %.0f ms/position".format(lists.toDouble() / (positions - empty - single).coerceAtLeast(1), millis.toDouble() / positions.coerceAtLeast(1)) +
            " dot=$dot dot-resolved=$dotResolved (%.3f) index-ms=$indexMillis wall-ms=$wallMillis".format(dotResolved.toDouble() / dot.coerceAtLeast(1))
    }

    /** Copies the repository's Go files into a temporary content root (the corpus stays untouched) and exports every file. */
    private fun exportRepository(repoDir: File, extractor: FeatureExtractor, writer: ExampleShards.Writer, stats: Stats) {
        // a unique directory per repository: the same path reused after a bulk deletion confuses cached values captured on the old VFS entries
        val tmp = FileUtil.createTempDirectory("gopsi-ml-${repoDir.name}-", null, true)
        val tIndex = System.currentTimeMillis()
        val sources = ArrayList<File>()
        repoDir.walkTopDown().onEnter { d -> !d.name.startsWith(".") && d.name != "vendor" && d.name != "testdata" && d.name != "node_modules" }.forEach { f ->
            if (f.isFile && f.name.endsWith(".go") || f.isFile && (f.name == "go.mod" || f.name == "go.work")) {
                val rel = f.relativeTo(repoDir).path
                val dst = File(tmp, rel); dst.parentFile.mkdirs(); f.copyTo(dst)
                if (f.name.endsWith(".go") && !f.name.endsWith("_test.go") && f.length() in 200..400_000) sources.add(dst)
            }
        }
        // module files from the overlay (the corpus snapshots carry none); the repository's own files win when present
        overlay?.let { File(it, repoDir.name) }?.takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
            if (f.isFile && f.name in setOf("go.mod", "go.sum", "go.work", "go.work.sum")) { val dst = File(tmp, f.name); if (!dst.exists()) f.copyTo(dst) }
        }
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(myFixture.module, root)
        try {
            if (libraryRoots != GoLibraryRootsMode.NONE) {
                // the platform only asks the provider again after a roots-changed event; the asynchronous GoRootsProvider.scheduleRootsUpdate
                // needs a pumped EDT, so the event is fired here, synchronously, with the roots of this repository's build list
                // GOROOT/src and the module cache must exist in the VFS before the provider can see them
                toolchain.gorootSrc?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                toolchain.gomodcache?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                val roots = GoRootsProvider.computeRoots(project).all()
                println("ml: library roots (${libraryRoots.name.lowercase()}): " + roots.joinToString(" ") { it.path }.take(600))
                if (roots != lastRoots) {
                    val old = lastRoots; lastRoots = roots
                    WriteAction.run<RuntimeException> { AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "Go", old, roots, "gopsi") }
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            stats.indexMillis += System.currentTimeMillis() - tIndex
            val files = sources.sortedBy { it.path }.let { if (maxFiles > 0) it.take(maxFiles) else it }
            for (file in files) {
                val text = file.readText().replace("\r\n", "\n")
                if (GoAnalysisScope.isGenerated(text)) continue
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: continue
                exportFile(vf, text, extractor, writer, stats)
            }
        } finally {
            PsiTestUtil.removeContentEntry(myFixture.module, root)
            FileUtil.delete(tmp)
        }
    }

    private fun exportFile(vf: com.intellij.openapi.vfs.VirtualFile, text: String, extractor: FeatureExtractor, writer: ExampleShards.Writer, stats: Stats) {
        val tokens = GoMlLanguage.tokenizer.tokens(text)
        val rnd = Random(seed xor tokens.size.toLong())
        val identifiers = (1 until tokens.size).filter { tokens[it].kind == TokenKind.IDENT }
        val positions = (if (identifiers.size <= perFile) identifiers else identifiers.shuffled(rnd).take(perFile)).sorted()
        if (positions.isEmpty()) return
        stats.files++
        myFixture.configureFromExistingVirtualFile(vf)
        val document = myFixture.editor.document
        val state = FileState(vocab, withCache = cacheLambda > 0)
        var fed = 0
        for (i in positions) {
            while (fed < i) state.add(tokens[fed++])
            val t = tokens[i]
            val prefixLen = when (rnd.nextInt(10)) { in 0..4 -> 0; in 5..7 -> 1; else -> 2 }.coerceAtMost(t.text.length)
            val prefix = t.text.substring(0, prefixLen)
            val caret = t.offset + prefixLen
            val started = System.currentTimeMillis()
            stats.positions++
            try {
                // only the identifier is replaced (not the whole text): an incremental reparse instead of a full one per position
                replace(document, t.offset, t.offset + t.text.length, prefix)
                myFixture.editor.caretModel.moveToOffset(caret)
                if (t.offset > 0 && text[t.offset - 1] == '.') { stats.dot++; if (receiverResolved(myFixture.file, t.offset - 1)) stats.dotResolved++ }
                val items = myFixture.completeBasic()
                if (items == null) { stats.single++; continue }     // one candidate was inserted directly: no list to learn from
                val infos = items.mapNotNull { e -> GoCompletionWeigher.infoOf(e)?.let { e to it } }
                var candidates = infos.map { (e, info) -> GoCompletionCandidate(e.lookupString, info.kind.name, info.level, info.expectedMatch, info.element) }
                if (candidates.isEmpty()) { stats.empty++; continue }
                val chosenAll = candidates.indexOfFirst { it.lookupString == t.text }
                if (chosenAll < 0 || candidates.size < 2) { stats.noAnswer++; continue }
                if (candidates.size > maxCandidates) {
                    val answer = candidates[chosenAll]
                    candidates = (candidates.filterIndexed { idx, _ -> idx != chosenAll }.shuffled(rnd).take(maxCandidates - 1) + answer).shuffled(rnd)
                }
                val chosen = candidates.indexOfFirst { it.lookupString == t.text }
                val context = GoCompletionRankingContext(myFixture.file, caret, "", prefix, null)
                val language = GoMlFeatures.languageBlock(context, candidates)
                val names = Array(candidates.size) { candidates[it].lookupString }
                val base = extractor.features(state, prefix, names, language)
                writer.add(TrainingExample(GoMlFeatures.contextKind(tokens, i), base, chosen, names))
                stats.lists++
            } finally {
                LookupManager.getInstance(project).hideActiveLookup()
                val cur = document.text
                if (cur != text) {
                    val untouched = cur.length == text.length - t.text.length + prefixLen && cur.regionMatches(0, text, 0, caret) && cur.regionMatches(caret, text, t.offset + t.text.length, text.length - t.offset - t.text.length)
                    if (untouched) replace(document, t.offset, caret, t.text) else setText(document, text)   // completion may have inserted something
                }
                stats.millis += System.currentTimeMillis() - started
            }
        }
    }

    /**
     * Whether the receiver of the member reference at `.` ([dotOffset]) is known to the plugin: the qualifier resolves to a package
     * (import), or its type is not [GoUnknownType]. Unresolved receivers (imports outside the module cache, failed inference) give
     * lists without the real members — the recall gap the module cache is meant to close.
     */
    private fun receiverResolved(file: PsiFile, dotOffset: Int): Boolean {
        val leafAtDot = file.findElementAt(dotOffset) ?: return false
        val ref = PsiTreeUtil.getParentOfType(leafAtDot, GoReferenceExpression::class.java, false)
        val typeRef = PsiTreeUtil.getParentOfType(leafAtDot, GoTypeReferenceExpression::class.java, false)
        var qualifier: PsiElement? = ref?.expression ?: typeRef?.referenceExpression
        if (qualifier == null) {
            // incomplete reference (empty prefix): the widest expression ending right before the dot
            var e: PsiElement? = file.findElementAt(dotOffset - 1)?.let { PsiTreeUtil.getParentOfType(it, GoExpression::class.java, false) }
            while (e != null && e.parent is GoExpression && e.parent.textRange.endOffset == dotOffset) e = e.parent
            qualifier = e?.takeIf { it.textRange.endOffset == dotOffset }
        }
        if (qualifier == null) return false
        if (qualifier is GoReferenceExpression && GoResolver.getInstance(project).resolveReferenceExpression(qualifier)
                .any { it is GoResolver.Result.Import || it is GoResolver.Result.Package }) return true
        val expr = qualifier as? GoExpression ?: return false
        return GoSemanticService.getInstance(project).typeOf(expr) !is GoUnknownType
    }

    private fun replace(document: com.intellij.openapi.editor.Document, start: Int, end: Int, with: String) {
        WriteCommandAction.runWriteCommandAction(project) { document.replaceString(start, end, with) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun setText(document: com.intellij.openapi.editor.Document, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
