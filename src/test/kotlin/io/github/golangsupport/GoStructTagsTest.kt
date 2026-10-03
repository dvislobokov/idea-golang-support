package io.github.golangsupport

import io.github.golangsupport.lang.GoStructTags
import io.github.golangsupport.lang.GoTemplateContexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoStructTagsTest {
    @Test fun keyContext() {
        val empty = GoStructTags.contextOf("") as GoStructTags.Context.AtKey
        assertEquals("", empty.prefix)
        val typing = GoStructTags.contextOf("js") as GoStructTags.Context.AtKey
        assertEquals("js", typing.prefix)
        val second = GoStructTags.contextOf("json:\"name\" va") as GoStructTags.Context.AtKey
        assertEquals("va", second.prefix)
        assertEquals(setOf("json"), second.present)
    }

    @Test fun valueContext() {
        val name = GoStructTags.contextOf("json:\"") as GoStructTags.Context.AtValue
        assertEquals("json", name.key)
        assertTrue(name.first)
        val option = GoStructTags.contextOf("json:\"user_id,omit") as GoStructTags.Context.AtValue
        assertEquals("omit", option.prefix)
        assertFalse(option.first)
        val rules = GoStructTags.contextOf("json:\"id\" validate:\"required,min=1|") as GoStructTags.Context.AtValue
        assertEquals("validate", rules.key)
        assertEquals("", rules.prefix)
        assertEquals(setOf("required", "min=1"), rules.chosen)
        // seen live: the first rule of validate (`validate:"mi`) offers the rules, not field names
        val first = GoStructTags.contextOf("json:\"name\" validate:\"mi") as GoStructTags.Context.AtValue
        assertEquals("validate" to "mi", first.key to first.prefix)
        assertTrue(GoStructTags.key("validate")!!.options.any { it.text == "min=" })
        val gorm = GoStructTags.contextOf("gorm:\"primaryKey;col") as GoStructTags.Context.AtValue
        assertEquals("col", gorm.prefix)
        // an argument of a rule is typed by hand
        assertNull(GoStructTags.contextOf("validate:\"oneof=red gre"))
        assertNull(GoStructTags.contextOf("gorm:\"column:na"))
    }

    @Test fun names() {
        val json = GoStructTags.key("json")!!
        assertEquals(listOf("user_id", "userID", "userid", "UserID", "user-id"), GoStructTags.names(json, "UserID"))
        assertEquals(listOf("DATABASE_URL"), GoStructTags.names(GoStructTags.key("env")!!, "DatabaseURL"))
        assertTrue(GoStructTags.names(GoStructTags.key("validate")!!, "Name").isEmpty())
    }

    @Test fun templatesStayOutOfStringsAndComments() {
        val text = "package p\n\ntype T struct {\n\tName string `json`\n}\n\n// err\nfunc f() {\n\terr\n\tx := \"err\"\n}\n"
        assertTrue(GoTemplateContexts.isInLiteralOrComment(text, text.indexOf("`json") + 1))
        assertTrue(GoTemplateContexts.isInLiteralOrComment(text, text.indexOf("// err") + 3))
        assertTrue(GoTemplateContexts.isInLiteralOrComment(text, text.indexOf("\"err\"") + 1))
        assertFalse(GoTemplateContexts.isInLiteralOrComment(text, text.indexOf("\terr\n") + 1))
        // the caret right after a closed string is outside it
        assertFalse(GoTemplateContexts.isInLiteralOrComment(text, text.indexOf("\"err\"") + 5))
        // a backquote just typed, closed by the IDE: `json| + `
        val typing = "type T struct {\n\tName string `json`\n}\n"
        assertTrue(GoTemplateContexts.isInLiteralOrComment(typing, typing.indexOf("json") + 4))
    }
}
