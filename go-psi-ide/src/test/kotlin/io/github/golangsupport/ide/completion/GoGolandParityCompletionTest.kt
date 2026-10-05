package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.completion.GoFillStructCompletion.FILL_ALL
import io.github.golangsupport.ide.completion.GoFillStructCompletion.FILL_SELECTED

/** Items GoLand shows and the plugin copies (PLAN.md, G3; `docs/goland-analysis/dumps/completion.txt`): Fill items, constant values, top-level declarations. */
class GoGolandParityCompletionTest : GoCompletionTestBase() {

    override fun tearDown() {
        try {
            GoFillStructCompletion.chooserForTests = null
            GoTopLevelTemplates.actionRunnerForTests = null
        } finally {
            super.tearDown()
        }
    }

    private val holder = """
        package main

        type Holder struct {
            Name    string
            Count   int
            Enabled bool
        }
    """.trimIndent()

    // --- Fill all fields… / Fill selected fields… ---

    fun testFillItemsComeFirstInAnEmptyLiteral() {
        val items = lookups("$holder\nfunc main() {\n    h := Holder{<caret>}\n    _ = h\n}")
        assertEquals(listOf(FILL_ALL, FILL_SELECTED), items.take(2))
        assertContainsAll(items, "Name", "Count", "Enabled")
    }

    fun testFillItemsInAPartlyFilledLiteralSkipWrittenFields() {
        val items = lookups("$holder\nfunc main() {\n    h := Holder{Name: \"x\", <caret>}\n    _ = h\n}")
        assertEquals(listOf(FILL_ALL, FILL_SELECTED), items.take(2))
        assertContainsAll(items, "Count", "Enabled")
        assertContainsNone(items, "Name")
    }

    fun testNoFillItemsInAPositionalLiteralOrAtAValue() {
        assertContainsNone(lookups("$holder\nfunc main() {\n    h := Holder{\"x\", <caret>}\n    _ = h\n}"), FILL_ALL, FILL_SELECTED)
        assertContainsNone(lookups("$holder\nfunc main() {\n    n := \"\"\n    h := Holder{Name: n<caret>}\n    _ = h\n}"), FILL_ALL, FILL_SELECTED)
        assertContainsNone(lookups("package main\n\nfunc main() {\n    xs := []int{<caret>}\n    _ = xs\n}"), FILL_ALL, FILL_SELECTED)
    }

    fun testFillAllFieldsAlignsTheValuesAndPutsTheCaretAfterTheFirst() = checkInsert(
        "$holder\nfunc main() {\n    h := Holder{<caret>}\n    _ = h\n}", FILL_ALL,
        "$holder\nfunc main() {\n    h := Holder{\n        Name:    \"\"<caret>,\n        Count:   0,\n        Enabled: false,\n    }\n    _ = h\n}",
    )

    fun testFillAllFieldsOfAPointerLiteralOnOneLineKeepsTheWrittenOne() = checkInsert(
        "$holder\nfunc main() {\n    h := &Holder{Count: 1, <caret>}\n    _ = h\n}", FILL_ALL,
        "$holder\nfunc main() {\n    h := &Holder{\n        Count:   1,\n        Name:    \"\"<caret>,\n        Enabled: false,\n    }\n    _ = h\n}",
    )

    fun testFillAllFieldsOfANestedLiteralRealignsTheLinesAbove() = checkInsert(
        "$holder\ntype Box struct {\n    Items []Holder\n}\n\nvar b = Box{Items: []Holder{{\n    Count: 1,\n    <caret>\n}}}\n", FILL_ALL,
        "$holder\ntype Box struct {\n    Items []Holder\n}\n\nvar b = Box{Items: []Holder{{\n    Count:   1,\n    Name:    \"\"<caret>,\n    Enabled: false,\n}}}\n",
    )

    fun testFillSelectedFieldsAsksWithAllFieldsAndWritesTheChosenOnes() {
        var offered: List<String>? = null
        GoFillStructCompletion.chooserForTests = { names -> offered = names; listOf("Enabled") }
        checkInsert(
            "$holder\nfunc main() {\n    h := Holder{<caret>}\n    _ = h\n}", FILL_SELECTED,
            "$holder\nfunc main() {\n    h := Holder{\n        Enabled: false<caret>,\n    }\n    _ = h\n}",
        )
        assertEquals(listOf("Name", "Count", "Enabled"), offered)
    }

    fun testFillSelectedFieldsCancelledLeavesTheLiteral() {
        GoFillStructCompletion.chooserForTests = { null }
        checkInsert("$holder\nfunc main() {\n    h := Holder{<caret>}\n    _ = h\n}", FILL_SELECTED, "$holder\nfunc main() {\n    h := Holder{<caret>}\n    _ = h\n}")
    }

    // --- constant values ---

    fun testConstantRowsShowTheirValueAndType() {
        lookups("""
            package main

            type Level int

            const (
                Debug Level = iota
                Info
            )

            const MaxItems = 10

            func main() {
                var x Level
                x = <caret>
                _ = x
            }
        """)
        assertEquals(" = 10", presentation("MaxItems").tailText)
        assertEquals("untyped int", presentation("MaxItems").typeText)
        assertEquals(" = iota", presentation("Debug").tailText)
        assertEquals("Level", presentation("Debug").typeText)
        // a repeated row shows the expression it repeats
        assertEquals(" = iota", presentation("Info").tailText)
        assertEquals("Level", presentation("Info").typeText)
    }

    fun testConstantValueOfAnotherFileComesFromTheStubs() {
        myFixture.addFileToProject("limits.go", "package main\n\nconst (\n\tA, B = 1 << iota, \"b\"\n\tC, D\n)\n")
        lookups("package main\n\nfunc main() {\n    _ = <caret>\n}")
        assertEquals(" = 1 << iota", presentation("A").tailText)
        assertEquals(" = \"b\"", presentation("D").tailText)
    }

    // --- top level: func (*T), func Implement Interface... ---

    fun testTopLevelOffersMethodAndImplementInterfaceAfterTheKeyword() {
        GoTopLevelTemplates.actionRunnerForTests = {}
        val items = lookups("package main\n\ntype Holder struct{}\n\nfun<caret>\n")
        assertEquals(listOf("func", GoTopLevelTemplates.METHOD, GoTopLevelTemplates.IMPLEMENT), items.filter { it.startsWith("func") })
        assertEquals("Method", presentation(GoTopLevelTemplates.METHOD).typeText)
        val implement = presentation(GoTopLevelTemplates.IMPLEMENT)
        assertEquals("func" to "Implement Interface...", implement.itemText to implement.typeText)
    }

    fun testMethodItemUsesTheReceiverOfTheTypeMethods() = checkInsert(
        "package main\n\ntype Holder struct{}\n\nfunc (hd Holder) Name() string { return \"\" }\n\nfun<caret>\n", GoTopLevelTemplates.METHOD,
        "package main\n\ntype Holder struct{}\n\nfunc (hd Holder) Name() string { return \"\" }\n\nfunc (hd Holder) <caret>() {\n}\n",
    )

    fun testMethodItemForTheTypeAboveWithAPointerReceiver() = checkInsert(
        "package main\n\ntype Base struct{}\n\ntype Circle struct{}\n\nfun<caret>\n", GoTopLevelTemplates.METHOD,
        "package main\n\ntype Base struct{}\n\ntype Circle struct{}\n\nfunc (c *Circle) <caret>() {\n}\n",
    )

    fun testImplementInterfaceRunsTheHostActionOnTheType() {
        var ran: String? = null
        GoTopLevelTemplates.actionRunnerForTests = { ran = it }
        checkInsert("package main\n\ntype Holder struct{}\n\nfun<caret>\n", GoTopLevelTemplates.IMPLEMENT, "package main\n\ntype <caret>Holder struct{}\n\n\n")
        assertEquals(GoTopLevelTemplates.IMPLEMENT_ACTION, ran)
    }

    fun testNoTopLevelDeclarationItemsInsideAFunctionOrWithoutTypes() {
        GoTopLevelTemplates.actionRunnerForTests = {}
        // the keyword alone is left, and inserted at once
        for (text in listOf("package main\n\ntype Holder struct{}\n\nfunc main() {\n    fun<caret>\n}\n", "package main\n\nfun<caret>\n")) {
            val items = complete(text)?.map { it.lookupString }
            assertTrue("$items", items == null || items.none { it == GoTopLevelTemplates.METHOD || it == GoTopLevelTemplates.IMPLEMENT })
        }
    }
}
