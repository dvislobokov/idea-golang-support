package io.github.golangsupport.ml

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
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
    private val maxCandidates = 100
    private lateinit var vocab: io.github.completionml.core.vocab.Vocabulary

    fun testExport() {
        requireNotNull(reposFile) { "-Pml.repos=<file with repository names> is required" }
        requireNotNull(lmFile) { "-Pml.lm=<lm.cml> is required (train it on repositories disjoint from ml.repos)" }
        val lm = NgramModel.read(File(lmFile))
        vocab = lm.vocab
        val extractor = FeatureExtractor(GoMlFeatures.schema, lm.vocab, lm, cacheLambda)
        out.mkdirs()
        val repos = File(reposFile).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val total = Stats()
        val t0 = System.currentTimeMillis()
        for (repo in repos) {
            val dir = File(data, "repos/$repo")
            if (!dir.isDirectory) { println("ml: skip $repo (no directory)"); continue }
            val shard = File(out, "$repo.cmlx")
            if (shard.exists()) { println("ml: skip $repo (shard exists)"); continue }
            val stats = Stats()
            ExampleShards.Writer(shard, GoMlLanguage.id, "idea-golang-support GoMlDatasetExport lm=${File(lmFile).name}", GoMlFeatures.schema, withNames).use { writer ->
                exportRepository(dir, extractor, writer, stats)
            }
            println("ml: $repo ${stats.summary()}")
            total.add(stats)
        }
        println("ml: TOTAL ${total.summary()} in ${(System.currentTimeMillis() - t0) / 1000} s; shards in $out")
    }

    private class Stats {
        var files = 0; var positions = 0; var lists = 0; var noAnswer = 0; var empty = 0; var single = 0; var millis = 0L
        fun add(o: Stats) { files += o.files; positions += o.positions; lists += o.lists; noAnswer += o.noAnswer; empty += o.empty; single += o.single; millis += o.millis }
        /** recall = lists with the answer / positions where the plugin offered a list at all (declaration names etc. get none). */
        fun summary() = "files=$files positions=$positions lists=$lists answer-missing=$noAnswer no-list=$empty single-insert=$single " +
            "recall=%.3f %.0f ms/position".format(lists.toDouble() / (positions - empty - single).coerceAtLeast(1), millis.toDouble() / positions.coerceAtLeast(1))
    }

    /** Copies the repository's Go files into a temporary content root (the corpus stays untouched) and exports every file. */
    private fun exportRepository(repoDir: File, extractor: FeatureExtractor, writer: ExampleShards.Writer, stats: Stats) {
        val tmp = FileUtil.createTempDirectory("gopsi-ml-", null, true)
        val sources = ArrayList<File>()
        repoDir.walkTopDown().onEnter { d -> !d.name.startsWith(".") && d.name != "vendor" && d.name != "testdata" && d.name != "node_modules" }.forEach { f ->
            if (f.isFile && f.name.endsWith(".go") || f.isFile && (f.name == "go.mod" || f.name == "go.work")) {
                val rel = f.relativeTo(repoDir).path
                val dst = File(tmp, rel); dst.parentFile.mkdirs(); f.copyTo(dst)
                if (f.name.endsWith(".go") && !f.name.endsWith("_test.go") && f.length() in 200..400_000) sources.add(dst)
            }
        }
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(myFixture.module, root)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
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
            val modified = text.substring(0, caret) + text.substring(t.offset + t.text.length)
            val started = System.currentTimeMillis()
            stats.positions++
            try {
                setText(document, modified)
                myFixture.editor.caretModel.moveToOffset(caret)
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
                setText(document, text)
                stats.millis += System.currentTimeMillis() - started
            }
        }
    }

    private fun setText(document: com.intellij.openapi.editor.Document, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
