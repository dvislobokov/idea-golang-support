package io.github.golangsupport

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoTemplateContexts
import io.github.golangsupport.lang.GoTemplateContexts.Place
import io.github.golangsupport.lang.psi.GoFile

/** The live templates of `liveTemplates/Go.xml`: the places of Go code they apply in, the new ones, the macros' text fallbacks. */
class GoLiveTemplatesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    /** Accepts every stop of the running template with its default. */
    private fun finishTemplate() = WriteCommandAction.runWriteCommandAction(project) { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }

    private fun place(source: String): Place? {
        myFixture.configureByText("a.go", source)
        return GoTemplateContexts.placeAt(myFixture.file as GoFile, myFixture.caretOffset)
    }

    /** The keys of the Go templates that apply at `<caret>`. */
    private fun applicable(source: String): Set<String> {
        myFixture.configureByText("a.go", source)
        return TemplateManagerImpl.listApplicableTemplates(TemplateActionContext.expanding(myFixture.file, myFixture.editor))
            .filter { it.groupName == "Go" }.map { it.key }.toSet()
    }

    private fun expand(source: String): String {
        myFixture.configureByText("a.go", source)
        myFixture.type("\t")
        finishTemplate()
        return myFixture.editor.document.text
    }

    fun testThePlacesByThePsi() {
        assertEquals(Place.STATEMENT, place("package a\n\nfunc f() {\n\tfori<caret>\n}\n"))
        assertEquals(Place.STATEMENT, place("package a\n\nvar g = func() {\n\terr<caret>\n}\n"))
        assertEquals(Place.TOP_LEVEL, place("package a\n\nfunc f() {}\n\nmain<caret>\n"))
        assertEquals(Place.TOP_LEVEL, place("package a\n\nmeth<caret>\n"))
        assertEquals(Place.STRUCT_FIELD, place("package a\n\ntype S struct {\n\tName string json<caret>\n}\n"))
        assertEquals(Place.EXPRESSION, place("package a\n\nfunc f() error {\n\treturn errf<caret>\n}\n"))
        assertEquals(Place.EXPRESSION, place("package a\n\nfunc f() {\n\tg(\n\t\terrf<caret>\n\t)\n}\n"))
        assertNull("a string", place("package a\n\nfunc f() {\n\t_ = \"fori<caret>\"\n}\n"))
        assertNull("an interface", place("package a\n\ntype I interface {\n\tfori<caret>\n}\n"))
        assertNull("parameters", place("package a\n\nfunc f(fori<caret>) {}\n"))
    }

    fun testThePlacesByTokensForTextThePsiHasNotSeen() {
        fun at(source: String): Place? {
            val offset = source.indexOf('|')
            val text = source.removeRange(offset, offset + 1)
            var start = offset
            while (start > 0 && text[start - 1].isLetter()) start--
            return GoTemplateContexts.tokenPlace(text, start, text.substring(text.lastIndexOf('\n', start - 1) + 1, start).isBlank())
        }
        assertEquals(Place.STATEMENT, at("package a\n\nfunc f() {\n\tfori|\n}\n"))
        assertEquals(Place.TOP_LEVEL, at("package a\n\nfunc f() {}\n\nmain|\n"))
        assertEquals(Place.STRUCT_FIELD, at("package a\n\ntype S struct {\n\tName string json|\n}\n"))
        assertEquals(Place.EXPRESSION, at("package a\n\nfunc f() {\n\tg(errf|\n}\n"))
        assertNull(at("package a\n\ntype I interface {\n\tfori|\n}\n"))
    }

    fun testStatementTemplatesOnlyInBodiesDeclarationsOnlyAtTheTop() {
        val body = applicable("package a\n\nfunc f() {\n\tfori<caret>\n}\n")
        assertTrue(body.toString(), "fori" in body && "err" in body && "forr" in body && "ctx" in body)
        assertFalse(body.toString(), "main" in body || "meth" in body || "func" in body || "json" in body)
        val top = applicable("package a\n\nfunc f() {}\n\nfunc<caret>\n")
        assertTrue(top.toString(), "func" in top && "main" in top && "meth" in top && "test" in top && "bench" in top && "fuzz" in top)
        assertFalse(top.toString(), "fori" in top || "err" in top || "json" in top)
        val field = applicable("package a\n\ntype S struct {\n\tName string json<caret>\n}\n")
        assertTrue(field.toString(), "json" in field)
        assertFalse(field.toString(), "fori" in field || "main" in field)
        val expression = applicable("package a\n\nfunc f() error {\n\treturn errf<caret>\n}\n")
        assertTrue(expression.toString(), "errf" in expression)
        assertFalse(expression.toString(), "fori" in expression || "main" in expression)
    }

    fun testTheListOfTheRoadmapIsThere() {
        val keys = TemplateSettings.getInstance().templates.filter { it.groupName == "Go" }.map { it.key }
        for (key in listOf("fori", "forr", "meth", "func", "test", "bench", "fuzz", "main", "err", "json", "errf")) assertTrue(key, key in keys)
        assertEquals("no abbreviation twice", keys.size, keys.toSet().size)
    }

    fun testTheTemplatesOfGoLandAreThereInTheirPlaces() {
        val keys = TemplateSettings.getInstance().templates.filter { it.groupName == "Go" }.associateBy { it.key }
        for (key in listOf("map", "p", "imports", "consts", "vars", "types", "iota", ":", "xml")) assertTrue(key, key in keys)
        assertEquals("map[\$KEY_TYPE$]\$VALUE_TYPE$", keys.getValue("map").string)
        assertEquals("const \$NAME$ \$TYPE$ = iota", keys.getValue("iota").string)
        assertEquals("\$NAME$ := \$VALUE$", keys.getValue(":").string)
        assertEquals("`xml:\"\$FIELD_NAME$\"\$END$`", keys.getValue("xml").string)
        val top = applicable("package a\n\nfunc f() {}\n\nimports<caret>\n")
        assertTrue(top.toString(), listOf("p", "imports", "consts", "vars", "types", "iota").all { it in top })
        assertFalse(top.toString(), ":" in top || "map" in top || "xml" in top)
        val body = applicable("package a\n\nfunc f() {\n\tconsts<caret>\n}\n")
        assertTrue(body.toString(), listOf("consts", "vars", "types", "iota").all { it in body })
        assertFalse(body.toString(), "p" in body || "imports" in body || "map" in body)
        assertTrue(":" in applicable("package a\n\nfunc f() {\n\t:<caret>\n}\n"))
        assertTrue("xml" in applicable("package a\n\ntype S struct {\n\tName string xml<caret>\n}\n"))
        for (source in listOf("package a\n\nvar m map<caret>\n", "package a\n\nfunc f() {\n\tx := make(map<caret>\n}\n", "package a\n\ntype S struct {\n\tM map<caret>\n}\n",
            "package a\n\nfunc f(m map<caret>) {}\n", "package a\n\nvar m []map<caret>\n")) assertTrue(source, "map" in applicable(source))
        assertFalse("a statement", "map" in applicable("package a\n\nfunc f() {\n\tmap<caret>\n}\n"))
        assertFalse("after a selector", "map" in applicable("package a\n\nfunc f() {\n\t_ = x.map<caret>\n}\n"))
    }

    fun testTheTemplatesOfGoLandExpand() {
        assertTrue(expand("package a\n\nfunc f() {\n\t:<caret>\n}\n").contains("\t := \n"))
        assertEquals("package a\n\nconst (\n\t = \n)\n\n", expand("package a\n\nconsts<caret>\n"))
        assertEquals("package a\n\nimport (\n\t\"\"\n)\n\n", expand("package a\n\nimports<caret>\n"))
        assertTrue(expand("package a\n\nvar m map<caret>\n").contains("var m map[]\n"))
        assertTrue(expand("package a\n\ntype S struct {\n\tName string xml<caret>\n}\n").contains("\tName string `xml:\"\"`\n"))
    }

    fun testFuncExpandsAtTheTop() {
        val text = expand("package a\n\nfunc<caret>\n")
        // an empty result leaves two spaces, as `fn` does; gofmt takes one away
        assertEquals("package a\n\nfunc ()  {\n\t\n}\n", text)
    }

    fun testErrfExpandsInAnExpression() {
        val text = expand("package a\n\nfunc f(err error) error {\n\treturn errf<caret>\n}\n")
        assertTrue(text, text.contains("\treturn fmt.Errorf(\": %w\", err)\n"))
    }

    fun testJsonExpandsInAStructField() {
        val text = expand("package a\n\ntype S struct {\n\tName string json<caret>\n}\n")
        assertTrue(text, text.contains("\tName string `json:\"\"`\n"))
    }

    fun testErrUsesTheResultsOfTheFunction() {
        val text = expand("package a\n\nfunc f() (int, error) {\n\terr<caret>\n}\n")
        assertTrue(text, text.contains("\tif err != nil {\n\t\treturn 0, err\n\t}"))
    }

    fun testTheMacrosReadTheTextOfAnUncommittedDocument() {
        val source = "package main\n\ntype Server struct{}\n\nfunc (s *Server) Start() {}\n\ntype Order struct{}\n\nfunc main() {\n\tx := 1\n"
        assertEquals("*Order", GoTemplateContexts.receiverByText(source, source.length))
        assertEquals("*Server", GoTemplateContexts.receiverByText(source, source.indexOf("type Order")))
        val load = "package a\n\nfunc load(path string) (int, error) {\n\tx := 1\n"
        val function = GoTemplateContexts.functionByText(load, load.length, isMainPackage = false)!!
        assertEquals("load", function.name)
        assertEquals(listOf("int", "error"), function.results.map { it.type })
        val main = GoTemplateContexts.functionByText(source, source.length, isMainPackage = true)!!
        assertTrue(main.isMain)
    }
}
