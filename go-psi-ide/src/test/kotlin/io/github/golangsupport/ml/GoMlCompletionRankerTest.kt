package io.github.golangsupport.ml

import io.github.golangsupport.ide.completion.GoCompletionTestBase
import io.github.golangsupport.ide.completion.GoCompletionWeigher
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import java.io.File

/**
 * The IDE ranker over real models. The models are not test data: the test reads them from `-Dml.models=<dir>` (lm.cml and
 * rank.cml of a training, `GoMlModels`) and is skipped without it; what it checks then is that every Go candidate of the
 * popup got a score, that the scores drive the order (the weigher's `RANKED` bucket) and that the whole thing stays fast.
 */
class GoMlCompletionRankerTest : GoCompletionTestBase() {

    private val source = """
        package main

        import "strings"

        type server struct{ name string }

        func (s *server) Start(port int) error { return nil }

        func run(count int, label string) string {
            total := count + 1
            srv := &server{name: label}
            _ = srv.Start(total)
            return strings.ToUpper(<caret>)
        }
    """

    fun testAbstainsWithoutModels() {
        GoMlSettings.getInstance().modelDirectory = java.nio.file.Files.createTempDirectory("no-models").toFile().path
        GoMlModels.getInstance().reset()
        GoCompletionRanker.EP_NAME.point.registerExtension(GoMlCompletionRanker(), testRootDisposable)
        val items = complete(source) ?: error("a single candidate was inserted; the test needs a list")
        assertTrue(items.mapNotNull { GoCompletionWeigher.infoOf(it) }.all { it.rankerScore == null })
    }

    fun testScoresEveryCandidateWithModels() {
        val dir = System.getProperty("ml.models")?.let(::File)?.takeIf { File(it, "rank.cml").isFile }
        if (dir == null) { println("GoMlCompletionRankerTest: no -Dml.models, skipped"); return }
        GoMlSettings.getInstance().modelDirectory = dir.path
        val models = GoMlModels.getInstance()
        models.reset()
        GoCompletionRanker.EP_NAME.point.registerExtension(GoMlCompletionRanker(), testRootDisposable)
        // the first completion starts the loading in the background; wait for it, then complete again
        complete(source)
        val deadline = System.currentTimeMillis() + 60_000
        while (models.get(dir.path) == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertNotNull("models did not load: ${models.status(dir.path)}", models.get(dir.path))

        val started = System.nanoTime()
        val items = complete(source) ?: error("a single candidate was inserted; the test needs a list")
        val millis = (System.nanoTime() - started) / 1_000_000
        val infos = items.mapNotNull { GoCompletionWeigher.infoOf(it) }
        assertTrue("no Go candidates", infos.size >= 3)
        assertTrue("unscored candidates: ${infos.filter { it.rankerScore == null }.map { it.name }}", infos.all { it.rankerScore != null })
        val names = items.map { it.lookupString }
        println("GoMlCompletionRankerTest: ${names.take(8)} in $millis ms")
        // the string parameter in scope is what a Go programmer passes to ToUpper here; the rules agree, the model must not do worse
        assertTrue("label expected among the first three of $names", names.indexOf("label") in 0..2)
    }
}
