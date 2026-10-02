package io.github.golangsupport.semantic

import io.github.golangsupport.semantic.types.*
import junit.framework.TestCase

/** Pure tests of the type model: identity, assignability, method sets, lookup, rendering. */
class GoTypePredicatesTest : TestCase() {
    private val int = GoBasicType.INT
    private val str = GoBasicType.STRING

    fun testIdentityAndRendering() {
        assertTrue(GoTypePredicates.identical(GoSliceType(int), GoSliceType(int)))
        assertFalse(GoTypePredicates.identical(GoSliceType(int), GoArrayType(int, 3)))
        assertTrue(GoTypePredicates.identical(GoMapType(str, GoPointerType(int)), GoMapType(str, GoPointerType(int))))
        assertEquals("map[string]*int", GoTypeRenderer.render(GoMapType(str, GoPointerType(int))))
        assertEquals("[3]int", GoTypeRenderer.render(GoArrayType(int, 3)))
        assertEquals("chan<- int", GoTypeRenderer.render(GoChanType(int, GoChanDir.SEND)))
        assertEquals("<-chan int", GoTypeRenderer.render(GoChanType(int, GoChanDir.RECV)))
        assertEquals("chan (<-chan int)", GoTypeRenderer.render(GoChanType(GoChanType(int, GoChanDir.RECV), GoChanDir.BOTH)))
        val sig = GoSignatureType(listOf(GoParam("a", int), GoParam("rest", GoSliceType(str))), listOf(GoParam(null, GoBasicType.BOOL)), true)
        assertEquals("func(a int, rest ...string) bool", GoTypeRenderer.render(sig))
        assertEquals("(int, string)", GoTypeRenderer.render(GoTupleType(listOf(int, str))))
        assertEquals("struct{X int; Y string}", GoTypeRenderer.render(GoStructType(listOf(GoField("X", int, false, null, null, null), GoField("Y", str, false, null, null, null)))))
        assertEquals("interface{}", GoTypeRenderer.render(GoInterfaceType(emptyList(), emptyList())))
        assertEquals("int | ~string", GoTypeRenderer.render(GoUnionType(listOf(GoTerm(false, int), GoTerm(true, str)))))
    }

    fun testByteRuneAliasesAreIdentical() {
        assertEquals("byte", GoTypeRenderer.render(GoBasicType.BYTE))
        assertEquals("uint8", GoTypeRenderer.render(GoBasicType.UINT8))
        assertEquals("[]rune", GoTypeRenderer.render(GoSliceType(GoBasicType.RUNE)))
        assertTrue(GoTypePredicates.identical(GoBasicType.BYTE, GoBasicType.UINT8))
        assertTrue(GoTypePredicates.identical(GoSliceType(GoBasicType.RUNE), GoSliceType(GoBasicType.INT32)))
        assertTrue(GoTypePredicates.assignable(GoBasicType.BYTE, GoBasicType.UINT8))
        assertTrue(GoTypePredicates.assignable(GoBasicType.INT32, GoBasicType.RUNE))
        assertEquals(GoBasicType.UINT8, GoBasicType.BYTE)
        assertEquals(GoBasicType.UINT8.hashCode(), GoBasicType.BYTE.hashCode())
        assertEquals(GoMapType(GoBasicType.BYTE, str), GoMapType(GoBasicType.UINT8, str))
        assertFalse(GoTypePredicates.identical(GoBasicType.BYTE, GoBasicType.INT32))
    }

    fun testAssignability() {
        assertTrue(GoTypePredicates.assignable(GoBasicType.UNTYPED_INT, int))
        assertTrue(GoTypePredicates.assignable(GoBasicType.UNTYPED_INT, GoBasicType.FLOAT64))
        assertFalse(GoTypePredicates.assignable(GoBasicType.UNTYPED_STRING, int))
        assertTrue(GoTypePredicates.assignable(GoBasicType.UNTYPED_NIL, GoSliceType(int)))
        assertFalse(GoTypePredicates.assignable(GoBasicType.UNTYPED_NIL, int))
        assertTrue(GoTypePredicates.assignable(GoChanType(int, GoChanDir.BOTH), GoChanType(int, GoChanDir.SEND)))
        assertFalse(GoTypePredicates.assignable(GoChanType(int, GoChanDir.SEND), GoChanType(int, GoChanDir.BOTH)))
        val empty = GoInterfaceType(emptyList(), emptyList())
        assertTrue(GoTypePredicates.assignable(int, empty))
        assertTrue(GoTypePredicates.assignable(GoBasicType.UNTYPED_INT, empty))
        val stringer = GoInterfaceType(listOf(GoMethod("String", GoSignatureType(emptyList(), listOf(GoParam(null, str)), false), null)), emptyList())
        assertFalse(GoTypePredicates.assignable(int, stringer))
        assertEquals(GoBasicType.FLOAT64, GoTypePredicates.defaultType(GoBasicType.UNTYPED_FLOAT))
        assertEquals(GoBasicType.INT32, GoTypePredicates.defaultType(GoBasicType.UNTYPED_RUNE))
    }

    fun testComparable() {
        assertTrue(GoTypePredicates.comparable(int))
        assertFalse(GoTypePredicates.comparable(GoSliceType(int)))
        assertFalse(GoTypePredicates.comparable(GoStructType(listOf(GoField("f", GoMapType(int, int), false, null, null, null)))))
        assertTrue(GoTypePredicates.comparable(GoArrayType(str, 2)))
        assertTrue(GoTypePredicates.comparable(GoPointerType(GoSliceType(int))))
    }

    fun testLookupThroughEmbeddedStructs() {
        val inner = GoStructType(listOf(GoField("Deep", int, false, null, null, null), GoField("Dup", int, false, null, null, null)))
        val other = GoStructType(listOf(GoField("Dup", str, false, null, null, null)))
        val outer = GoStructType(listOf(
            GoField("Own", str, false, null, null, null),
            GoField("Inner", inner, true, null, null, null),
            GoField("Other", GoPointerType(other), true, null, null, null),
        ))
        val deep = GoLookup.lookupFieldOrMethod(outer, "Deep") as GoLookup.Selection.Field
        assertEquals(int, deep.type)
        assertEquals(listOf("Inner"), deep.path.map { it.name })
        assertFalse(deep.indirect)
        val viaPointer = GoLookup.lookupFieldOrMethod(GoPointerType(outer), "Own") as GoLookup.Selection.Field
        assertTrue(viaPointer.indirect)
        assertTrue(GoLookup.lookupFieldOrMethod(outer, "Dup") is GoLookup.Selection.Ambiguous)
        assertNull(GoLookup.lookupFieldOrMethod(outer, "Missing"))
        assertNull(GoLookup.lookupFieldOrMethod(GoPointerType(GoPointerType(outer)), "Own"))
    }

    fun testIntersectionOfConstraintTerms() {
        val a = GoInterfaceType(emptyList(), listOf(GoUnionType(listOf(GoTerm(true, int), GoTerm(true, str)))))
        val b = GoInterfaceType(emptyList(), listOf(GoUnionType(listOf(GoTerm(false, int), GoTerm(false, GoBasicType.FLOAT64)))))
        val both = GoInterfaceType(emptyList(), listOf(a, b))
        assertEquals(listOf(GoTerm(false, int)), both.typeTerms)
        assertTrue(GoTypePredicates.implements(int, a))
        assertFalse(GoTypePredicates.implements(GoBasicType.FLOAT64, both))
    }
}
