package io.github.golangsupport.ide.completion

/**
 * Rows as GoLand renders them (dump probes 1, 2, 3, 6, 9, 10, C1c): members with their owner after an arrow in the tail (`created → Base`),
 * parameters as the tail and results as the type, the import path in the tail for members of other packages, `byte` / `rune` kept.
 */
class GoPresentationCompletionTest : GoCompletionTestBase() {

    private val shapes = """
        package main

        type Base struct{ ID int }

        func (b Base) Describe() string { return "" }

        type Circle struct {
            Base
            Radius float64
        }

        func (c Circle) Area() float64 { return 0 }

        type Square struct{ Side float64 }

        func (s *Square) Area() float64 { return 0 }

        type Shape interface{ Area() float64 }

        func classify(x any) string { return "" }

        func bytes2(b []byte, r rune) (byte, rune) { return 0, 0 }
    """.trimIndent()

    private fun tailAndType(lookup: String): Pair<String?, String?> = presentation(lookup).let { it.tailText to it.typeText }

    fun testFieldsAndMethodsShowTheirOwner() {
        lookups("$shapes\n\nfunc main() {\n    var c Circle\n    c.<caret>\n}")
        assertEquals(" → Base" to "int", tailAndType("ID"))
        assertEquals(" → Circle" to "float64", tailAndType("Radius"))
        assertEquals("an embedded field has no owner", null to "Base", tailAndType("Base"))
        assertEquals("() → Base" to "string", tailAndType("Describe"))
        assertEquals("() → Circle" to "float64", tailAndType("Area"))
    }

    fun testPointerReceiverAndInterfaceMethods() {
        lookups("$shapes\n\nfunc main() {\n    sq := &Square{}\n    sq.<caret>\n}")
        assertEquals("() → *Square" to "float64", tailAndType("Area"))
        assertEquals(" → Square" to "float64", tailAndType("Side"))
        lookups("$shapes\n\nfunc f(s Shape) {\n    s.<caret>\n}")
        assertEquals("() → interface {...}" to "float64", tailAndType("Area"))
    }

    fun testStructLiteralKeysShowTheirOwner() {
        lookups("$shapes\n\nfunc main() {\n    c := Circle{<caret>}\n    _ = c\n}")
        assertEquals(" → Circle" to "float64", tailAndType("Radius"))
        assertEquals(" → Base" to "int", tailAndType("ID"))
    }

    fun testScopeRows() {
        lookups("$shapes\n\nfunc main() {\n    c := Circle{}\n    _ = c\n    <caret>\n}")
        assertEquals(null to "Circle", tailAndType("c"))
        assertEquals("(x any)" to "string", tailAndType("classify"))
        assertEquals("(b []byte, r rune)" to "(byte, rune)", tailAndType("bytes2"))
        assertEquals("(v Type)" to "int", tailAndType("len"))
        assertEquals("(c chan<- Type)" to null, tailAndType("close"))
    }

    fun testImportedPackageMembers() {
        lookups("package main\n\nimport \"strings\"\n\nfunc main() {\n    strings.<caret>\n}")
        assertEquals("(s string)" to "string", tailAndType("ToUpper"))
        assertEquals("(s string, sep string)" to "(before string, after string, found bool)", tailAndType("Cut"))
        assertEquals("(s string, c byte)" to "int", tailAndType("IndexByte"))
        assertEquals("(s string, r rune)" to "int", tailAndType("IndexRune"))
    }

    fun testMembersOfAPackageNotImportedShowItsPath() {
        lookups("package main\n\nfunc main() {\n    strings.<caret>\n}")
        assertEquals("(s string) strings" to "string", tailAndType("ToUpper"))
        assertEquals(" strings" to null, tailAndType("Builder"))
    }
}
