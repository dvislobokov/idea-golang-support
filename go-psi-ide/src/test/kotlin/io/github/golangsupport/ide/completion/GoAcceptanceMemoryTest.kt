package io.github.golangsupport.ide.completion

import com.intellij.testFramework.ExtensionTestUtil
import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext
import kotlin.math.ln

/** The memory of accepted items: persistence and decay, the weigher of the plain build, the bonus of a ranker with a model, the setting. */
class GoAcceptanceMemoryTest : GoCompletionTestBase() {

    private val memory get() = GoAcceptanceMemory.getInstance(project)
    private val settings get() = GoCompletionAssistSettings.getInstance()

    override fun setUp() {
        super.setUp()
        memory.clear()
        GoCompletionRecency.getInstance(project).clear()
        settings.acceptanceEnabled = true
    }

    override fun tearDown() {
        try {
            memory.clear()
            memory.now = System::currentTimeMillis
            GoCompletionRecency.getInstance(project).clear()
            settings.acceptanceEnabled = true
            settings.acceptanceWeight = GoCompletionAssistSettings.DEFAULT_ACCEPTANCE_WEIGHT.toDouble()
        } finally {
            super.tearDown()
        }
    }

    private val twoFunctions = """
        package main

        func alphaRun() {}
        func alphaStop() {}

    """
    private val statement = twoFunctions + "func main() {\n    alpha<caret>\n}\n"

    // --- the store ---

    fun testCountsPersistThroughTheState() {
        memory.record("stmt", "alphaStop")
        memory.record("stmt", "alphaStop")
        memory.record("dot", "Println")
        val reloaded = GoAcceptanceMemory()
        reloaded.loadState(memory.state)
        assertEquals(2, reloaded.count("stmt", "alphaStop"))
        assertEquals(1, reloaded.count("dot", "Println"))
        assertEquals(0, reloaded.count("arg", "alphaStop"))
        assertEquals(mapOf("stmt|alphaStop" to 2, "dot|Println" to 1), reloaded.snapshot())
    }

    fun testCountsAreHalvedOnceAMonth() {
        val start = 1_700_000_000_000L
        memory.now = { start }
        repeat(4) { memory.record("stmt", "alphaStop") }
        memory.record("stmt", "alphaRun")
        memory.now = { start + GoAcceptanceMemory.DECAY_MILLIS - 1 }
        memory.decayIfDue()
        assertEquals(4, memory.count("stmt", "alphaStop"))
        memory.now = { start + GoAcceptanceMemory.DECAY_MILLIS + 1 }
        memory.decayIfDue()
        assertEquals(2, memory.count("stmt", "alphaStop"))
        // a count that reaches 0 is dropped
        assertEquals(0, memory.count("stmt", "alphaRun"))
        assertEquals(setOf("stmt|alphaStop"), memory.snapshot().keys)
        // three months without a record: halved three times
        memory.now = { start + GoAcceptanceMemory.DECAY_MILLIS + 1 + 3 * GoAcceptanceMemory.DECAY_MILLIS }
        memory.decayIfDue()
        assertEquals(0, memory.count("stmt", "alphaStop"))
    }

    fun testTheCountIsCapped() {
        repeat(GoAcceptanceMemory.MAX_COUNT + 5) { memory.record("stmt", "x") }
        assertEquals(GoAcceptanceMemory.MAX_COUNT, memory.count("stmt", "x"))
    }

    fun testResetForgetsEverything() {
        memory.record("stmt", "alphaStop")
        memory.clear()
        assertEquals(0, memory.count("stmt", "alphaStop"))
        assertTrue(memory.snapshot().isEmpty())
    }

    // --- the weigher (the heuristic ranker of the plain build) ---

    fun testAnAcceptedItemGoesUpInTheSameKindOfPosition() {
        // alphabetical order would put alphaRun first
        memory.record("stmt", "alphaStop")
        val items = lookups(statement)
        assertTrue(items.toString(), items.indexOf("alphaStop") < items.indexOf("alphaRun"))
    }

    fun testAnAcceptanceInAnotherKindOfPositionDoesNotCount() {
        memory.record("dot", "alphaStop")
        memory.record("arg", "alphaStop")
        val items = lookups(statement)
        assertTrue(items.toString(), items.indexOf("alphaRun") < items.indexOf("alphaStop"))
    }

    fun testTheCountDoesNotBeatTheExpectedTypeOrTheScope() {
        memory.record("stmt", "alphaStop")
        // a local is closer than a package function whatever the count
        val items = lookups(twoFunctions + "func main() {\n    alphaLocal := 1\n    _ = alphaLocal\n    alpha<caret>\n}\n")
        assertTrue(items.toString(), items.indexOf("alphaLocal") < items.indexOf("alphaStop"))
    }

    fun testOffTheCountsHaveNoEffect() {
        memory.record("stmt", "alphaStop")
        settings.acceptanceEnabled = false
        val items = lookups(statement)
        assertTrue(items.toString(), items.indexOf("alphaRun") < items.indexOf("alphaStop"))
    }

    // --- the bonus of a ranker with a model ---

    /** Scores every candidate the same and asks for the additive bonus, as the ML ranker does. */
    private class FlatRanker(private val weight: Double) : GoCompletionRanker {
        override fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double> = candidates.map { 1.0 }
        override val acceptanceWeight: Double get() = weight
    }

    fun testTheBonusIsAddedToTheScoreOfARankerWithAModel() {
        ExtensionTestUtil.maskExtensions(GoCompletionRanker.EP_NAME, listOf(FlatRanker(0.3)), testRootDisposable)
        repeat(3) { memory.record("stmt", "alphaStop") }
        val elements = complete(statement) ?: error("a single candidate was inserted")
        val infos = elements.associate { it.lookupString to GoCompletionWeigher.infoOf(it)!! }
        assertEquals(1.0 + 0.3 * ln(4.0), infos.getValue("alphaStop").rankerScore!!, 1e-9)
        assertEquals(1.0, infos.getValue("alphaRun").rankerScore!!, 1e-9)
        assertTrue(infos.values.all { it.acceptanceInScore && it.acceptedCount == 0 })
        val names = elements.map { it.lookupString }
        assertTrue(names.toString(), names.indexOf("alphaStop") < names.indexOf("alphaRun"))
    }

    fun testOffTheRankerScoreStaysUntouched() {
        ExtensionTestUtil.maskExtensions(GoCompletionRanker.EP_NAME, listOf(FlatRanker(0.3)), testRootDisposable)
        memory.record("stmt", "alphaStop")
        settings.acceptanceEnabled = false
        val elements = complete(statement) ?: error("a single candidate was inserted")
        assertTrue(elements.mapNotNull { GoCompletionWeigher.infoOf(it) }.all { it.rankerScore == 1.0 && !it.acceptanceInScore })
    }

    // --- the listener ---

    fun testAChosenItemIsCounted() {
        GoCompletionRecencyListener.recordInTests = true
        try {
            checkInsert(statement, "alphaStop", twoFunctions + "func main() {\n    alphaStop()<caret>\n}\n")
        } finally {
            GoCompletionRecencyListener.recordInTests = false
        }
        assertEquals(1, memory.count(GoAcceptanceMemory.KIND_STATEMENT, "alphaStop"))
        assertEquals(0, memory.count(GoAcceptanceMemory.KIND_STATEMENT, "alphaRun"))
    }

    fun testTheKindOfAPositionIsOnTheLookupInfo() {
        val afterDot = complete("package main\n\nimport \"strings\"\n\nfunc main() {\n    strings.To<caret>\n}\n") ?: error("a single candidate was inserted")
        assertEquals(GoAcceptanceMemory.KIND_DOT, GoCompletionWeigher.infoOf(afterDot.first { it.lookupString == "ToUpper" })!!.contextKind)
        val argument = complete(twoFunctions + "func use(f func()) {}\n\nfunc main() {\n    use(alpha<caret>)\n}\n") ?: error("a single candidate was inserted")
        assertEquals(GoAcceptanceMemory.KIND_ARGUMENT, GoCompletionWeigher.infoOf(argument.first { it.lookupString == "alphaRun" })!!.contextKind)
    }
}
