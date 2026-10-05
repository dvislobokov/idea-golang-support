package io.github.golangsupport.ide.inspections.lint

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** errcheck: "Do not report this method/function anymore" fills the inspection's excludes, and listed callees are not reported. */
class GoUncheckedErrorExcludeTest : GoSemanticIdeTestBase() {

    private val source = """
        package ue9x

        import "os"

        type ue9xStore struct{}

        func (*ue9xStore) Save() error { return nil }

        func ue9xCalls(s *ue9xStore) {
        	os.Re<caret>move("tmp")
        	s.Save()
        	os.Chdir("/")
        }
        """.trimIndent() + "\n"

    private fun warnings(): List<String> = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Error return value") }

    fun testDoNotReportAddsTheFunctionToTheExcludes() {
        val inspection = GoUncheckedErrorInspection()
        myFixture.enableInspections(inspection)
        myFixture.configureByText("ue9x.go", source)
        assertEquals(3, warnings().size)
        myFixture.launchAction(myFixture.findSingleIntention(GoUncheckedErrorInspection.DO_NOT_REPORT))
        val configured = GoUncheckedErrorInspection.profileInstance(myFixture.file)!!
        assertEquals(listOf("os.Remove"), configured.excludedFunctions)
        assertEquals(listOf("Error return value of `s.Save` is not checked", "Error return value of `os.Chdir` is not checked"), warnings())
    }

    fun testMethodsAreExcludedByReceiverType() {
        val inspection = GoUncheckedErrorInspection()
        inspection.excludedFunctions.add("example.com/none.T.Save")
        myFixture.enableInspections(inspection)
        myFixture.configureByText("ue9x.go", source.replace("os.Re<caret>move", "os.Remove").replace("s.Save()", "s.Sa<caret>ve()"))
        myFixture.launchAction(myFixture.findSingleIntention(GoUncheckedErrorInspection.DO_NOT_REPORT))
        val added = GoUncheckedErrorInspection.profileInstance(myFixture.file)!!.excludedFunctions.last()
        assertTrue(added, added.endsWith(".ue9xStore.Save"))
        assertEquals(listOf("Error return value of `os.Remove` is not checked", "Error return value of `os.Chdir` is not checked"), warnings())
    }

    fun testNoFixWithoutAFinding() {
        myFixture.enableInspections(GoUncheckedErrorInspection())
        myFixture.configureByText("ue9x.go", source.replace("os.Re<caret>move(\"tmp\")", "_ = os.Re<caret>move(\"tmp\")"))
        assertEmpty(myFixture.filterAvailableIntentions(GoUncheckedErrorInspection.DO_NOT_REPORT))
    }
}
