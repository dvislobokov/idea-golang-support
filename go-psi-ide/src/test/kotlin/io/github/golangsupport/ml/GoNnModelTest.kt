package io.github.golangsupport.ml

import io.github.completionml.core.nn.native.NativeLib
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The real network of `ml-models/go` through the loader of [GoMlModels]; skipped when the repository has no models next to the module. */
class GoNnModelTest {
    private val dir = listOf(File("../ml-models/go"), File("ml-models/go")).firstOrNull { File(it, GoMlModels.NN_MODEL).isFile }

    @Test fun completesAGoLine() {
        assumeTrue("no ml-models/go/${GoMlModels.NN_MODEL}", dir != null)
        val nn = checkNotNull(GoMlModels.loadNn(dir)) { "no network in $dir" }
        try {
            val before = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Prin"
            val c = GoNnInline.context(before + "\n}\n", before.length, "main.go")
            val started = System.currentTimeMillis()
            val r = nn.model.newSession(2048).use { nn.completion.complete(c.path, c.before, c.after, it) }
            println("GoNnModelTest: '${r.textString}' confProd ${r.confProd} show ${r.show} in ${System.currentTimeMillis() - started} ms; kernels: ${NativeLib.status}")
            assertTrue("empty completion", r.text.isNotEmpty())
            assertTrue(NativeLib.status.isNotBlank())
        } finally { nn.model.close() }
    }

    @Test fun guessesTheStringOfErrorf() {
        // the live case of 2026-10-07: `return fmt.` → `Errorf("store: no items")` at confProd 0.016 — the message is free text, the code around it is certain
        assumeTrue("no ml-models/go/${GoMlModels.NN_MODEL}", dir != null)
        val nn = checkNotNull(GoMlModels.loadNn(dir)) { "no network in $dir" }
        try {
            val before = "package store\n\nimport \"fmt\"\n\ntype Order struct {\n\titems []string\n}\n\nfunc (o *Order) Validate() error {\n\tif len(o.items) == 0 {\n\t\treturn fmt."
            val c = GoNnInline.context(before + "\n\t}\n\treturn nil\n}\n", before.length, "store/order.go")
            val r = nn.model.newSession(2048).use { nn.completion.complete(c.path, c.before, c.after, it) }
            val code = GoNnInline.codeConfidence(GoNnInline.lineBefore(c.before, r.typed.size), r.tokens.map { nn.completion.tok.tokenBytes(it) }, r.logProbs, r.stopLogProb)
            println("GoNnModelTest: 'fmt.' -> '${r.textString}' confProd ${r.confProd} code $code")
            assertTrue("a call with a string: '${r.textString}'", r.textString.startsWith("Errorf(\"") && r.textString.endsWith("\")"))
            assertTrue("the code is certain, the string is not: $code vs ${r.confProd}", code > r.confProd && code >= 0.6)   // 0.69 on 2026-10-07 (0.89 with an `errors` import and `ErrEmpty` above: `Errorf` certain, the end of the line 0.89)
        } finally { nn.model.close() }
    }

    @Test fun prefillOfTheCaretIsReusedByTheFirstCompletion() {
        assumeTrue("no ml-models/go/${GoMlModels.NN_MODEL}", dir != null)
        val nn = checkNotNull(GoMlModels.loadNn(dir)) { "no network in $dir" }
        try {
            val head = "package store\n\nimport \"fmt\"\n\ntype Order struct {\n\titems []string\n}\n\nfunc (o *Order) Validate() error {\n\tif len(o.items) == 0 {\n\t\treturn fmt."
            val tail = "\n\t}\n\treturn nil\n}\n"
            nn.model.newSession(2048).use { s ->
                // the file is opened with the caret after `fmt.`: the prompt of that caret goes into the cache in the background
                val cached = GoNnInline.prefill(nn.completion, s, GoNnInline.context(head + tail, head.length, "store/order.go"))
                assertTrue("nothing cached", cached > 10)
                val before = s.tokens()
                // the user types `Er`: the healed boundary is the same, the prompt is the cached one — nothing is prefilled again
                val c = GoNnInline.context(head + "Er" + tail, head.length + 2, "store/order.go")
                val started = System.nanoTime()
                val r = nn.completion.complete(c.path, c.before, c.after, s)
                val millis = (System.nanoTime() - started) / 1_000_000
                var lcp = 0
                while (lcp < before.size && lcp < r.prompt.size && before[lcp] == r.prompt[lcp]) lcp++
                println("GoNnModelTest: prefill $cached tokens, prompt ${r.prompt.size}, common $lcp, completion '${r.textString}' in $millis ms")
                assertArrayEquals("the prefilled prompt is the request's prompt", r.prompt, before)
                assertTrue("typed `Er` continued: '${r.textString}'", r.textString.startsWith("rorf"))
            }
        } finally { nn.model.close() }
    }

    @Test fun theBigModelLoadsByItsName() {
        assumeTrue("no ml-models/go/${GoMlModels.NN_MODEL_BIG}", dir != null && File(dir, GoMlModels.NN_MODEL_BIG).isFile)
        val nn = checkNotNull(GoMlModels.loadNn(dir, model = GoMlModels.NN_MODEL_BIG)) { "no network in $dir" }
        try {
            assertEquals(GoMlModels.NN_MODEL_BIG.removeSuffix(".cml"), nn.name)
            val before = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Prin"
            val c = GoNnInline.context(before + "\n}\n", before.length, "main.go")
            val r = nn.model.newSession(2048).use { nn.completion.complete(c.path, c.before, c.after, it) }
            println("GoNnModelTest: ${nn.name} 'fmt.Prin' -> '${r.textString}' confProd ${r.confProd}")
            assertTrue("empty completion", r.text.isNotEmpty())
        } finally { nn.model.close() }
        // a directory without the big model falls back to the small one, the bundled choice too
        assertEquals(GoMlModels.NN_MODEL, GoMlModels.nnModelName(big = true, modelDirectory = ""))
        assertEquals(GoMlModels.NN_MODEL_BIG, GoMlModels.nnModelName(big = true, modelDirectory = dir!!.path))
        assertEquals(GoMlModels.NN_MODEL, GoMlModels.nnModelName(big = false, modelDirectory = dir.path))
    }

    @Test fun healsAWordBeingTypedAndTrimsThePairedCloser() {
        // the live case of 2026-10-07: `return le` continued ` le` + `(`, and `return len(` + `)` doubled the `)` — both fixed in the engine (ac9b3fd)
        assumeTrue("no ml-models/go/${GoMlModels.NN_MODEL}", dir != null)
        val nn = checkNotNull(GoMlModels.loadNn(dir)) { "no network in $dir" }
        try {
            val head = "package store\n\ntype Order struct {\n\titems []string\n}\n\nfunc (o *Order) Count() int {\n\treturn "
            nn.model.newSession(2048).use { s ->
                val c1 = GoNnInline.context(head + "le\n}\n", head.length + 2, "store/order.go")
                val r1 = nn.completion.complete(c1.path, c1.before, c1.after, s)
                println("GoNnModelTest: 'le' -> '${r1.textString}' confProd ${r1.confProd}")
                assertTrue("healed from the word start: '${r1.textString}'", r1.textString.startsWith("n("))
                s.truncate(0)
                val c2 = GoNnInline.context(head + "len()\n}\n", head.length + 4, "store/order.go")
                val r2 = nn.completion.complete(c2.path, c2.before, c2.after, s)
                println("GoNnModelTest: 'len(' -> '${r2.textString}' confProd ${r2.confProd}")
                assertTrue("paired closer trimmed: '${r2.textString}'", r2.textString.isNotEmpty() && !r2.textString.endsWith(")"))
            }
        } finally { nn.model.close() }
    }
}
