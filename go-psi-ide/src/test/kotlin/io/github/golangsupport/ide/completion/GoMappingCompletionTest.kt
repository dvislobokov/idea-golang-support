package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.completion.GoFillStructCompletion.FILL_ALL
import io.github.golangsupport.ide.completion.GoFillStructCompletion.FILL_SELECTED

/** Field-to-field "mapping" items: a struct literal, a block of assignments, nested sources, exclusions, Map all, the setting. */
class GoMappingCompletionTest : GoCompletionTestBase() {

    override fun tearDown() {
        try {
            GoCompletionAssistSettings.getInstance().mappingEnabled = true
        } finally {
            super.tearDown()
        }
    }

    private val types = """
        package main

        type Source struct {
            ID    int
            Name  string
            Email string
            Years int
        }

        type Dto struct {
            ID    int
            Name  string
            Email string
            Age   int
            Tags  []string
        }

    """

    private val mapAll = GoMappingCompletion.mapAllText("src")

    // --- the literal ---

    fun testAnEmptyLiteralOffersOneItemPerMatchingFieldBelowTheFillItems() {
        val items = lookups(types + "func conv(src Source) Dto {\n    return Dto{<caret>}\n}\n")
        assertEquals(listOf(FILL_ALL, FILL_SELECTED, mapAll), items.take(3))
        assertContainsAll(items, "ID: src.ID", "Name: src.Name", "Email: src.Email")
        // `Years` is an int like `Age`, but the names share nothing and no neighbour copies from src yet
        assertContainsNone(items, "Age: src.Years", "Tags: src.Name")
        assertEquals(GoMappingCompletion.TAIL, presentation("Name: src.Name").tailFragments.single().text)
    }

    fun testFieldsAlreadyWrittenAreNotOfferedAndThePartnerAddsTheRest() {
        val items = lookups(types + "func conv(src Source) Dto {\n    return Dto{Name: src.Name, <caret>}\n}\n")
        assertContainsNone(items, "Name: src.Name")
        assertContainsAll(items, "ID: src.ID", "Email: src.Email", "Age: src.Years")
        // the neighbour copies from src: a clear mapping, the items sit right below the Fill ones
        assertEquals(mapAll, items[2])
    }

    fun testATypeMismatchIsExcluded() {
        val items = lookups(types + "type Other struct {\n    Name int\n    Email string\n}\n\nfunc conv(o Other) Dto {\n    return Dto{<caret>}\n}\n")
        assertContainsNone(items, "Name: o.Name")
        assertContainsAll(items, "Email: o.Email")
    }

    fun testTheReceiverGivesItsFieldsAndTheirFields() {
        val items = lookups(types + "type Profile struct {\n    Email string\n}\n\ntype User struct {\n    Name    string\n    Profile Profile\n}\n\nfunc (u *User) dto() Dto {\n    return Dto{<caret>}\n}\n")
        assertContainsAll(items, "Name: u.Name", "Email: u.Profile.Email")
    }

    fun testWithoutAMatchingVariableNothingIsOffered() {
        val items = lookups(types + "func conv(n int) Dto {\n    return Dto{<caret>}\n}\n")
        assertTrue(items.toString(), items.none { it.startsWith("Map all") || it.contains(": n") })
    }

    fun testOneFieldInAOneLineLiteralIsInsertedWithoutAComma() = checkInsert(
        types + "func conv(src Source) Dto {\n    return Dto{Name: src.Name, <caret>}\n}\n", "ID: src.ID",
        types + "func conv(src Source) Dto {\n    return Dto{Name: src.Name, ID: src.ID<caret>}\n}\n",
    )

    fun testOneFieldInAMultiLineLiteralGetsTheTrailingComma() = checkInsert(
        types + "func conv(src Source) Dto {\n    return Dto{\n        <caret>\n    }\n}\n", "Name: src.Name",
        types + "func conv(src Source) Dto {\n    return Dto{\n        Name: src.Name,<caret>\n    }\n}\n",
    )

    fun testOneFieldBeforeAnotherOnTheSameLineGetsNoComma() = checkInsert(
        types + "func conv(src Source) Dto {\n    return Dto{\n        <caret> Email: src.Email,\n    }\n}\n", "Name: src.Name",
        types + "func conv(src Source) Dto {\n    return Dto{\n        Name: src.Name<caret> Email: src.Email,\n    }\n}\n",
    )

    fun testMapAllWritesTheConfidentFieldsAlignedInDeclarationOrder() = checkInsert(
        types + "func conv(src Source) Dto {\n    d := Dto{\n        <caret>\n    }\n    return d\n}\n", mapAll,
        types + "func conv(src Source) Dto {\n    d := Dto{\n        ID:    src.ID,\n        Name:  src.Name,\n        Email: src.Email<caret>,\n    }\n    return d\n}\n",
    )

    fun testMapAllNeedsTwoConfidentFields() {
        val items = lookups(types + "type Pair struct {\n    Name string\n    N    int\n}\n\nfunc conv(src Source) Pair {\n    return Pair{<caret>}\n}\n")
        assertContainsAll(items, "Name: src.Name")
        assertContainsNone(items, mapAll)
    }

    // --- the assignment block ---

    private val block = types + "func conv(src Source) Dto {\n    var dst Dto\n    dst.ID = src.ID\n    <caret>\n    return dst\n}\n"

    fun testAfterAnAssignmentTheRemainingFieldsOfTheTargetComeFirst() {
        val items = lookups(block)
        assertEquals(mapAll, items[0])
        assertContainsAll(items, "dst.Name = src.Name", "dst.Email = src.Email", "dst.Age = src.Years")
        assertContainsNone(items, "dst.ID = src.ID")
        assertTrue(items.toString(), items.indexOf("dst.Name = src.Name") < items.indexOf("dst.Age = src.Years"))
    }

    fun testMapAllInABlockWritesOneAssignmentPerLineWithTheIndentation() = checkInsert(
        block, mapAll,
        types + "func conv(src Source) Dto {\n    var dst Dto\n    dst.ID = src.ID\n    dst.Name = src.Name\n    dst.Email = src.Email<caret>\n    return dst\n}\n",
    )

    fun testOneAssignmentIsInsertedAsTyped() = checkInsert(
        block, "dst.Email = src.Email",
        types + "func conv(src Source) Dto {\n    var dst Dto\n    dst.ID = src.ID\n    dst.Email = src.Email<caret>\n    return dst\n}\n",
    )

    fun testTheTargetIsNotASourceOfItsOwnFields() {
        val items = lookups(types + "func conv(src Source) Dto {\n    var dst Dto\n    dst.ID = src.ID\n    <caret>\n    return dst\n}\n")
        assertTrue(items.toString(), items.none { it.contains("= dst.") })
    }

    fun testNoAssignmentAboveMeansNoStatementItems() {
        val items = lookups(types + "func conv(src Source) Dto {\n    var dst Dto\n    <caret>\n    return dst\n}\n")
        assertTrue(items.toString(), items.none { it.startsWith("dst.") || it.startsWith("Map all") })
    }

    // --- the setting ---

    fun testOffNothingIsOffered() {
        GoCompletionAssistSettings.getInstance().mappingEnabled = false
        val items = lookups(block)
        assertTrue(items.toString(), items.none { it.startsWith("dst.") || it.startsWith("Map all") })
        val literal = lookups(types + "func conv(src Source) Dto {\n    return Dto{<caret>}\n}\n")
        assertContainsNone(literal, "Name: src.Name", mapAll)
    }

    // --- the scoring ---

    fun testNameSimilarity() {
        assertEquals(1.0, GoMappingCompletion.similarity("Name", "Name"))
        assertEquals(0.9, GoMappingCompletion.similarity("Name", "name"))
        assertEquals(0.7, GoMappingCompletion.similarity("UserID", "ID"))
        assertEquals(0.7, GoMappingCompletion.similarity("Email", "EmailAddress"))
        assertEquals(0.6, GoMappingCompletion.similarity("FirstName", "first_name"), 1e-9)
        assertEquals(0.2, GoMappingCompletion.similarity("FirstName", "LastName"), 1e-9)
        assertEquals(0.0, GoMappingCompletion.similarity("Age", "Years"))
        assertEquals(setOf("http", "server", "id"), GoMappingCompletion.tokens("HTTPServerID"))
    }
}
