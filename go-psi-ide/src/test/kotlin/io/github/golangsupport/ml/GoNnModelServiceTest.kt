package io.github.golangsupport.ml

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The application service over the real network of `ml-models/go` (skipped without it): the load runs in the background, a prefill never
 * blocks the caller and the editor's session is reused by the completion.
 */
class GoNnModelServiceTest : BasePlatformTestCase() {
    private val dir = listOf(File("../ml-models/go"), File("ml-models/go")).firstOrNull { File(it, GoMlModels.NN_MODEL).isFile }
    private var saved: List<Any> = emptyList()

    override fun setUp() {
        super.setUp()
        val s = GoMlSettings.getInstance()
        saved = listOf(s.modelDirectory, s.inlineEnabled, s.enabled)
    }

    override fun tearDown() {
        try {
            val s = GoMlSettings.getInstance()
            s.modelDirectory = saved[0] as String; s.inlineEnabled = saved[1] as Boolean; s.enabled = saved[2] as Boolean
            GoMlModels.getInstance().reset()
        } finally { super.tearDown() }
    }

    private fun waitForNetwork(name: String) {
        val models = GoMlModels.getInstance()
        PlatformTestUtil.waitWithEventsDispatching("the network $name did not load: ${models.nnStatus(GoMlSettings.getInstance().modelDirectory)}", { models.nnName == name }, 120)
    }

    fun testTheNetworkLoadsInTheBackgroundAndPrefillDoesNotBlock() {
        if (dir == null) { println("GoNnModelServiceTest: no ml-models/go/${GoMlModels.NN_MODEL}, skipped"); return }
        val s = GoMlSettings.getInstance()
        s.modelDirectory = dir.absolutePath; s.inlineEnabled = true; s.enabled = false
        val models = GoMlModels.getInstance()
        models.reset()
        assertNull(models.nn(s.modelDirectory))   // loading in the background
        waitForNetwork(GoMlModels.NN_MODEL.removeSuffix(".cml"))

        // a file opened with the caret after `fmt.`: the prefill returns at once, the completion then finds its prompt in the cache
        val editor = Any()
        val head = "package store\n\nimport \"fmt\"\n\nfunc (o *Order) Validate() error {\n\tif len(o.items) == 0 {\n\t\treturn fmt."
        val started = System.nanoTime()
        models.prefill(editor) { GoNnInline.context(head + "\n\t}\n\treturn nil\n}\n", head.length, "store/order.go") }
        assertTrue("prefill blocked the caller", (System.nanoTime() - started) / 1_000_000 < 50)
        val answer = runBlocking { models.complete(editor, GoNnInline.context(head + "Er\n\t}\n\treturn nil\n}\n", head.length + 2, "store/order.go")) }
        assertNotNull(answer)
        println("GoNnModelServiceTest: after prefill '${answer!!.text}' ${answer.confProd} show ${answer.show}")
        models.release(editor)
    }
}
