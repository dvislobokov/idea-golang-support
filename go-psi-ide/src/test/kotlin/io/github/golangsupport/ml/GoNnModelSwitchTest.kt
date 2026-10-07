package io.github.golangsupport.ml

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The application service over the real models of `ml-models/go` (skipped without them): the choice 31 M / 50 M reloads the network in the
 * background, a prefill never blocks the caller and the editor's session is reused by the completion.
 */
class GoNnModelSwitchTest : BasePlatformTestCase() {
    private val dir = listOf(File("../ml-models/go"), File("ml-models/go")).firstOrNull { File(it, GoMlModels.NN_MODEL_BIG).isFile }
    private var saved: List<Any> = emptyList()

    override fun setUp() {
        super.setUp()
        val s = GoMlSettings.getInstance()
        saved = listOf(s.modelDirectory, s.inlineEnabled, s.inlineBigModel, s.enabled)
    }

    override fun tearDown() {
        try {
            val s = GoMlSettings.getInstance()
            s.modelDirectory = saved[0] as String; s.inlineEnabled = saved[1] as Boolean; s.inlineBigModel = saved[2] as Boolean; s.enabled = saved[3] as Boolean
            GoMlModels.getInstance().reset()
        } finally { super.tearDown() }
    }

    private fun waitForNetwork(name: String) {
        val models = GoMlModels.getInstance()
        PlatformTestUtil.waitWithEventsDispatching("the network $name did not load: ${models.nnStatus(GoMlSettings.getInstance().modelDirectory)}", { models.nnName == name }, 120)
    }

    fun testTheBigModelSwitchReloadsTheNetworkAndPrefillDoesNotBlock() {
        if (dir == null) { println("GoNnModelSwitchTest: no ml-models/go/${GoMlModels.NN_MODEL_BIG}, skipped"); return }
        val s = GoMlSettings.getInstance()
        s.modelDirectory = dir.absolutePath; s.inlineEnabled = true; s.inlineBigModel = false; s.enabled = false
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
        println("GoNnModelSwitchTest: after prefill '${answer!!.text}' ${answer.confProd} show ${answer.show}")
        models.release(editor)

        // the switch: the settings page applies and resets; the big network replaces the small one without any completion asking
        s.inlineBigModel = true
        models.reset()
        waitForNetwork(GoMlModels.NN_MODEL_BIG.removeSuffix(".cml"))
        assertTrue(models.nnStatus(s.modelDirectory).contains("go-nn-50m"))
        val big = runBlocking { models.complete(Any(), GoNnInline.context(head + "Er\n\t}\n\treturn nil\n}\n", head.length + 2, "store/order.go")) }
        assertNotNull(big)
        println("GoNnModelSwitchTest: big '${big!!.text}' ${big.confProd} show ${big.show}")

        s.inlineBigModel = false
        models.reset()
        waitForNetwork(GoMlModels.NN_MODEL.removeSuffix(".cml"))
    }
}
