package io.github.golangsupport

import io.github.golangsupport.lang.GoJsonTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Go types for a JSON document. */
class GoJsonTypesTest {
    @Test
    fun anObjectBecomesAStructWithTagsAndAlignedColumns() {
        val result = GoJsonTypes.generate("User", """{"id": 7, "user_name": "ann", "active": true, "score": 1.5, "tags": ["a"], "note": null}""")
        assertEquals(
            """
            type User struct {
            	ID       int      `json:"id"`
            	UserName string   `json:"user_name"`
            	Active   bool     `json:"active"`
            	Score    float64  `json:"score"`
            	Tags     []string `json:"tags"`
            	Note     any      `json:"note"`
            }
            """.trimIndent() + "\n", result.code,
        )
        assertEquals(emptyList<String>(), result.imports)
    }

    @Test
    fun nestedObjectsGetTheirOwnTypesNamedAfterTheKeys() {
        val result = GoJsonTypes.generate("Order", """{"id": 1, "customer": {"name": "x", "address": {"city": "y"}}, "items": [{"sku": "a", "qty": 2}, {"sku": "b", "qty": 3, "gift": true}]}""")
        val code = result.code
        assertTrue(code, code.startsWith("type Order struct {\n"))
        assertTrue(code, code.contains("\tCustomer Customer `json:\"customer\"`") && code.contains("\tItems    []Item   `json:\"items\"`"))
        assertTrue(code, code.contains("type Customer struct {\n\tName    string  `json:\"name\"`\n\tAddress Address `json:\"address\"`\n}"))
        assertTrue(code, code.contains("type Address struct {\n\tCity string `json:\"city\"`\n}"))
        // the elements of the array are one struct: every field of every element, in the order they are first seen
        assertTrue(code, code.contains("type Item struct {\n\tSku  string `json:\"sku\"`\n\tQty  int    `json:\"qty\"`\n\tGift bool   `json:\"gift\"`\n}"))
        assertEquals(listOf("Order", "Customer", "Address", "Item"), Regex("type (\\w+) struct").findAll(code).map { it.groupValues[1] }.toList())
    }

    @Test
    fun theValuesOfAnArrayAreMerged() {
        val code = GoJsonTypes.generate("Data", """{"mixed": [1, 2.5], "wide": [1, 12345678901], "opt": [{"a": 1}, null], "some": [null, 3], "none": [], "any": [1, "x"]}""").code
        assertTrue(code, code.contains("Mixed []float64"))
        assertTrue(code, code.contains("Wide  []int64"))
        assertTrue(code, code.contains("Opt   []*Opt"))
        assertTrue(code, code.contains("Some  []*int"))
        assertTrue(code, code.contains("None  []any"))
        assertTrue(code, code.contains("Any   []any"))
        val plain = GoJsonTypes.generate("Data", """{"some": [null, 3]}""", GoJsonTypes.Options(pointerForNull = false)).code
        assertTrue(plain, plain.contains("Some []any"))
    }

    @Test
    fun aDateIsTimeAndTimeIsImported() {
        val result = GoJsonTypes.generate("Event", """{"at": "2026-09-30T10:00:00Z", "day": "2026-09-30", "until": "2026-09-30T10:00:00.5+03:00"}""")
        assertTrue(result.code, result.code.contains("At    time.Time `json:\"at\"`") && result.code.contains("Day   string    `json:\"day\"`") && result.code.contains("Until time.Time"))
        assertEquals(listOf("time"), result.imports)
    }

    @Test
    fun aRootArrayAndOmitEmpty() {
        val result = GoJsonTypes.generate("users", """[{"id": 1}, {"id": 2}]""", GoJsonTypes.Options(omitEmpty = true))
        assertEquals("type Users []User\n\ntype User struct {\n\tID int `json:\"id,omitempty\"`\n}\n", result.code)
        assertEquals("type Names []string\n", GoJsonTypes.generate("Names", """["a", "b"]""").code)
    }

    @Test
    fun namesOfFieldsAndTypes() {
        assertEquals("UserID", GoJsonTypes.fieldName("user_id"))
        assertEquals("FirstName", GoJsonTypes.fieldName("first-name"))
        assertEquals("HTTPURL", GoJsonTypes.fieldName("http url"))
        assertEquals("N2Fa", GoJsonTypes.fieldName("2fa"))
        assertEquals("Field", GoJsonTypes.fieldName("@#"))
        assertEquals("Entry", GoJsonTypes.typeName("entries", singular = true))
        assertEquals("Address", GoJsonTypes.typeName("addresses", singular = true))
        assertEquals("", GoJsonTypes.typeName(""))
        // two keys that give one name stay two fields
        val code = GoJsonTypes.generate("T", """{"a_b": 1, "a-b": 2, "ab": 3}""").code
        assertTrue(code, code.contains("AB  int") && code.contains("AB2 int") && code.contains("Ab  int"))
    }

    @Test
    fun whatLooksLikeJson() {
        assertTrue(GoJsonTypes.looksLikeJson(""" {"a": 1} """))
        assertTrue(GoJsonTypes.looksLikeJson("[1, 2]"))
        assertFalse(GoJsonTypes.looksLikeJson("package main"))
        assertFalse(GoJsonTypes.looksLikeJson("{a: 1"))
        assertFalse(GoJsonTypes.looksLikeJson(null))
        assertFalse(GoJsonTypes.looksLikeJson("42"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun notJsonIsRefused() {
        GoJsonTypes.generate("T", "{\"a\": }")
    }
}
