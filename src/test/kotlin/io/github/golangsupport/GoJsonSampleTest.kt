package io.github.golangsupport

import io.github.golangsupport.lang.GoJsonSample
import io.github.golangsupport.lang.GoJsonSample.Declared
import io.github.golangsupport.lang.GoJsonSample.Field
import org.junit.Assert.assertEquals
import org.junit.Test

class GoJsonSampleTest {
    private fun f(name: String, type: String, tag: String? = null, embedded: Boolean = false) = Field(name, type, tag, embedded)
    private fun norm(s: String) = s.replace(Regex("\\s+"), "")

    @Test fun primitivesAndTags() {
        val json = GoJsonSample.sample(listOf(
            f("ID", "int64", "`json:\"id\"`"), f("Name", "string"), f("Ok", "bool", "`json:\"ok,omitempty\"`"),
            f("Skip", "string", "`json:\"-\"`"), f("hidden", "string"), f("Price", "float64"), f("Dash", "int", "`json:\"-,\"`"),
        ))
        assertEquals("""{"id":0,"Name":"","ok":false,"Price":0,"-":0}""", norm(json))
    }

    @Test fun prettyPrinted() {
        assertEquals("{\n  \"A\": \"\"\n}", GoJsonSample.sample(listOf(f("A", "string"))))
    }

    @Test fun containersAndTime() {
        val json = GoJsonSample.sample(listOf(
            f("Tags", "[]string"), f("M", "map[string]int"), f("At", "time.Time"), f("P", "*int"), f("Raw", "[]byte"),
            f("Any", "any"), f("Ext", "other.Thing"), f("Arr", "[3]bool"),
        ))
        assertEquals("""{"Tags":[""],"M":{},"At":"2006-01-02T15:04:05Z","P":0,"Raw":"","Any":null,"Ext":null,"Arr":[false]}""", norm(json))
    }

    @Test fun nestedAndEmbedded() {
        val types = mapOf(
            "Base" to Declared.Struct(listOf(f("ID", "int"), f("Name", "string"))),
            "Addr" to Declared.Struct(listOf(f("City", "string"))),
            "Status" to Declared.Alias("string"),
        )
        val json = GoJsonSample.sample(listOf(f("Base", "Base", embedded = true), f("Name", "string", "`json:\"n\"`"), f("Home", "*Addr"), f("S", "Status")), types::get)
        assertEquals("""{"ID":0,"Name":"","n":"","Home":{"City":""},"S":""}""", norm(json))
    }

    @Test fun cycleGuard() {
        val types = mapOf("Node" to Declared.Struct(listOf(f("Next", "*Node"), f("Kids", "[]Node"), f("V", "int"))))
        val json = GoJsonSample.sample(types.getValue("Node").let { (it as Declared.Struct).fields }, types::get)
        assertEquals("""{"Next":{"Next":null,"Kids":[null],"V":0},"Kids":[{"Next":null,"Kids":[null],"V":0}],"V":0}""", norm(json))
    }

    @Test fun tagNames() {
        assertEquals("a", GoJsonSample.tagName("`xml:\"b\" json:\"a,omitempty\"`"))
        assertEquals("", GoJsonSample.tagName("`json:\",omitempty\"`"))
        assertEquals(null, GoJsonSample.tagName("`yaml:\"a\"`"))
        assertEquals("-", GoJsonSample.tagName("`json:\"-\"`"))
    }
}
