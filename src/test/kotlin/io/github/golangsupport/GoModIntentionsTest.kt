package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.mod.GoModUpdate
import io.github.golangsupport.mod.GoModUpdateDependenciesIntention

/** The go.mod intentions (GoLand parity G4) in a `go.mod` editor: where each is offered, the merge applied to the document, Update dependencies… through its hooks. */
class GoModIntentionsTest : BasePlatformTestCase() {
    private val merges = listOf("Merge a group of directives", "Merge all directives", "Merge directive up")

    private val text = """
        module example.com/m

        go 1.22

        require (
        	github.com/google/uuid v1.5.0
        	golang.org/x/text v0.14.0 // indirect
        )

        require golang.org/x/sync v0.6.0
        require golang.org/x/mod v0.15.0
    """.trimIndent() + "\n"

    private fun offeredAt(needle: String): List<String> {
        val at = text.indexOf(needle).also { assertTrue(needle, it >= 0) }
        myFixture.configureByText("go.mod", text.substring(0, at) + "<caret>" + text.substring(at))
        return myFixture.availableIntentions.map { it.text }.filter { it in merges || it == GoModUpdateDependenciesIntention.TITLE }.sorted()
    }

    private var savedSource = GoModUpdateDependenciesIntention.updatesSource
    private var savedChooser = GoModUpdateDependenciesIntention.chooser
    private var savedRunner = GoModUpdateDependenciesIntention.runner

    override fun setUp() {
        super.setUp()
        savedSource = GoModUpdateDependenciesIntention.updatesSource
        savedChooser = GoModUpdateDependenciesIntention.chooser
        savedRunner = GoModUpdateDependenciesIntention.runner
        // never the background `go list` of GoModUpdates
        GoModUpdateDependenciesIntention.updatesSource = { _, _, _ -> null }
    }

    override fun tearDown() {
        try {
            GoModUpdateDependenciesIntention.updatesSource = savedSource
            GoModUpdateDependenciesIntention.chooser = savedChooser
            GoModUpdateDependenciesIntention.runner = savedRunner
        } finally {
            super.tearDown()
        }
    }

    fun testWhereTheMergesAreOffered() {
        // the first of two one-line requires right after a block: all three
        assertEquals(merges, offeredAt("require golang.org/x/sync"))
        // the second: a group and all, not up (a line is above it)
        assertEquals(listOf("Merge a group of directives", "Merge all directives"), offeredAt("require golang.org/x/mod"))
        // inside the block: all
        assertEquals(listOf("Merge all directives"), offeredAt("github.com/google/uuid"))
        assertEquals(emptyList<String>(), offeredAt("module"))
        assertEquals(emptyList<String>(), offeredAt("go 1.22"))
    }

    fun testMergeAllAppliesToTheDocument() {
        offeredAt("require golang.org/x/mod")
        myFixture.launchAction(myFixture.findSingleIntention("Merge all directives"))
        myFixture.checkResult(
            "module example.com/m\n\ngo 1.22\n\nrequire (\n\tgithub.com/google/uuid v1.5.0\n\tgolang.org/x/text v0.14.0 // indirect\n" +
                "\tgolang.org/x/sync v0.6.0\n\tgolang.org/x/mod v0.15.0\n)\n",
        )
    }

    fun testMergeUpAppliesToTheDocument() {
        offeredAt("require golang.org/x/sync")
        myFixture.launchAction(myFixture.findSingleIntention("Merge directive up"))
        myFixture.checkResult(
            "module example.com/m\n\ngo 1.22\n\nrequire (\n\tgithub.com/google/uuid v1.5.0\n\tgolang.org/x/text v0.14.0 // indirect\n" +
                "\tgolang.org/x/sync v0.6.0\n)\n\nrequire golang.org/x/mod v0.15.0\n",
        )
    }

    fun testUpdateDependenciesNeedsKnownNewerDirectVersions() {
        // nothing known yet
        assertEquals(emptyList<String>(), offeredAt("github.com/google/uuid").filter { it == GoModUpdateDependenciesIntention.TITLE })
        // newer versions of a direct and of an indirect require: offered on a require, the indirect one is not listed
        GoModUpdateDependenciesIntention.updatesSource = { _, _, _ -> mapOf("github.com/google/uuid" to "v1.6.0", "golang.org/x/text" to "v0.15.0", "golang.org/x/mod" to "v0.15.0") }
        assertTrue(GoModUpdateDependenciesIntention.TITLE in offeredAt("github.com/google/uuid"))
        assertTrue(GoModUpdateDependenciesIntention.TITLE in offeredAt("require golang.org/x/sync"))
        assertFalse(GoModUpdateDependenciesIntention.TITLE in offeredAt("go 1.22"))

        var listed: List<GoModUpdate> = emptyList()
        var ran: List<String> = emptyList()
        GoModUpdateDependenciesIntention.chooser = { _, updates -> listed = updates; updates }
        GoModUpdateDependenciesIntention.runner = { _, _, targets -> ran = targets }
        offeredAt("github.com/google/uuid")
        myFixture.launchAction(myFixture.findSingleIntention(GoModUpdateDependenciesIntention.TITLE))
        assertEquals(listOf("github.com/google/uuid"), listed.map { it.require.path })
        assertEquals(listOf("github.com/google/uuid@v1.6.0"), ran)
    }

    fun testUpdateDependenciesOnlyInGoMod() {
        GoModUpdateDependenciesIntention.updatesSource = { _, _, _ -> mapOf("a.com/x" to "v1.1.0") }
        myFixture.configureByText("go.work", "go 1.22\n\nuse ./a\n<caret>require a.com/x v1.0.0\n")
        assertFalse(myFixture.availableIntentions.any { it.text == GoModUpdateDependenciesIntention.TITLE })
    }
}
