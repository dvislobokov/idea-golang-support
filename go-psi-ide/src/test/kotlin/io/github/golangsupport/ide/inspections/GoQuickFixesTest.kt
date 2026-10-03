package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Quick fixes of the diagnostics inspections and Optimize Imports over `testData/inspections/fixes`. */
class GoQuickFixesTest : GoSemanticIdeTestBase() {
    override val testDataSubdir: String = "inspections/fixes"

    private fun doTest(name: String, fixText: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByFile("$name.go")
        myFixture.launchAction(myFixture.findSingleIntention(fixText))
        myFixture.checkResultByFile("${name}_after.go")
    }

    fun testAddImport() = doTest("addImport", "Import \"strings\"", GoUnresolvedReferenceInspection())

    fun testAddImportInTypePosition() = doTest("addImportType", "Import \"bytes\"", GoUnresolvedReferenceInspection())

    fun testAddImportOffersEveryPackageWithTheName() {
        myFixture.enableInspections(GoUnresolvedReferenceInspection())
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t_ = <caret>rand.Int()\n}\n")
        val names = myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Import ") }
        assertTrue(names.toString(), names.containsAll(listOf("Import \"crypto/rand\"", "Import \"math/rand\"")))
    }

    fun testNoAddImportWhenThePackageLacksTheMember() {
        myFixture.enableInspections(GoUnresolvedReferenceInspection())
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t_ = <caret>strings.NoSuchFunction()\n}\n")
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Import ") })
    }

    fun testRemoveImport() = doTest("removeImport", "Remove unused import", GoUnusedImportInspection())

    fun testRemoveSingleImportDeclaration() = doTest("removeImportSingle", "Remove unused import", GoUnusedImportInspection())

    fun testOptimizeImportsFix() {
        myFixture.enableInspections(GoUnusedImportInspection())
        myFixture.configureByFile("optimizeImports.go")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\"os\"") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Optimize imports"))
        myFixture.checkResultByFile("optimizeImports_after.go")
    }

    fun testOptimizeImportsAction() {
        myFixture.configureByFile("optimizeImports.go")
        OptimizeImportsProcessor(project, myFixture.file).run()
        myFixture.checkResultByFile("optimizeImports_after.go")
    }

    fun testRemoveUnusedVariable() = doTest("removeVariable", "Remove variable 'x'", GoUnusedVariableInspection())

    fun testReplaceUnusedVariableWithBlankAssignment() = doTest("blankAssign", "Replace 'x' with '_ ='", GoUnusedVariableInspection())

    fun testRenameUnusedVariableToBlank() = doTest("renameBlank", "Rename 'b' to '_'", GoUnusedVariableInspection())

    fun testWrapInConversion() = doTest("wrapConversion", "Convert to 'Celsius'", GoTypeMismatchInspection())

    fun testWrapInConversionContexts() {
        myFixture.enableInspections(GoTypeMismatchInspection())
        myFixture.configureByFile("wrapConversion.go")
        val text = myFixture.file.text
        // Argument of another package's type: qualified with the import name.
        myFixture.editor.caretModel.moveToOffset(text.indexOf("takes(c, i)") + "takes(c, ".length)
        assertNotNull(myFixture.getAvailableIntention("Convert to 'time.Duration'"))
        // Function result.
        myFixture.editor.caretModel.moveToOffset(text.indexOf("return i") + "return ".length)
        assertNotNull(myFixture.getAvailableIntention("Convert to 'float64'"))
    }

    fun testNoConversionForUntypedConstantsOrIntToString() {
        myFixture.enableInspections(GoTypeMismatchInspection())
        myFixture.configureByText("a.go", "package a\n\nfunc f(i int) {\n\tvar s string = <caret>i\n\tvar t string = 1\n\t_, _ = s, t\n}\n")
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Convert to") })
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("= 1") + 2)
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Convert to") })
    }

    // --- implement missing methods (docs/FEATURES.md §11, wave 3 B2) ---

    private fun implement(before: String, fix: String, after: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools.ifEmpty { arrayOf(GoTypeMismatchInspection()) })
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    fun testImplementMissingMethodsWithPointerReceiverAndImports() = implement(
        """
        package a

        import "net/http"

        type Server struct{}

        func (s *Server) Start() {}

        func f() {
        	var h http.Handler = <caret>&Server{}
        	_ = h
        }
        """,
        "Implement 'http.Handler' for Server: add missing methods",
        """
        package a

        import "net/http"

        type Server struct{}

        func (s *Server) Start() {}

        func (s *Server) ServeHTTP(responseWriter http.ResponseWriter, request *http.Request) {
        	panic("not implemented")
        }

        func f() {
        	var h http.Handler = &Server{}
        	_ = h
        }
        """,
    )

    fun testImplementMissingMethodsOfAValueAddsTheImportOfAResultType() = implement(
        """
        package a

        import "fmt"

        type Shape interface {
        	Area() float64
        	Name() string
        	Origin() fmt.Stringer
        }

        type Circle struct{ R float64 }

        func (c Circle) Area() float64 { return c.R }

        func all() []Shape {
        	return []Shape{<caret>Circle{1}}
        }
        """,
        "Implement 'Shape' for Circle: add missing methods",
        """
        package a

        import "fmt"

        type Shape interface {
        	Area() float64
        	Name() string
        	Origin() fmt.Stringer
        }

        type Circle struct{ R float64 }

        func (c Circle) Area() float64 { return c.R }

        func (c Circle) Name() string {
        	panic("not implemented")
        }

        func (c Circle) Origin() fmt.Stringer {
        	panic("not implemented")
        }

        func all() []Shape {
        	return []Shape{Circle{1}}
        }
        """,
    )

    fun testImplementErrorForAnArgument() = implement(
        """
        package a

        type NotFound struct{ Key string }

        func report(err error) {}

        func f() {
        	report(<caret>NotFound{"k"})
        }
        """,
        "Implement 'error' for NotFound: add missing methods",
        """
        package a

        type NotFound struct{ Key string }

        func (n NotFound) Error() string {
        	panic("not implemented")
        }

        func report(err error) {}

        func f() {
        	report(NotFound{"k"})
        }
        """,
    )

    fun testImplementForAnImpossibleTypeAssertion() = implement(
        """
        package a

        import "io"

        type File struct{}

        func f(r io.Reader) {
        	_ = r.(*<caret>File)
        }
        """,
        "Implement 'io.Reader' for File: add missing methods",
        """
        package a

        import "io"

        type File struct{}

        func (f *File) Read(p []byte) (n int, err error) {
        	panic("not implemented")
        }

        func f(r io.Reader) {
        	_ = r.(*File)
        }
        """,
        GoCheckerInspection(),
    )

    fun testNoImplementFixWhenOnlyThePointerReceiverIsMissing() {
        myFixture.enableInspections(GoTypeMismatchInspection())
        myFixture.configureByText(
            "a.go",
            "package a\n\nimport \"fmt\"\n\ntype T struct{}\n\nfunc (t *T) String() string { return \"\" }\n\nvar s fmt.Stringer = <caret>T{}\n",
        )
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Implement ") })
    }

    fun testImplementStubsForATypeSpec() {
        myFixture.configureByText("a.go", "package a\n\nimport \"io\"\n\ntype W struct{}\n\nvar _ io.Writer\n")
        val spec = (myFixture.file as io.github.golangsupport.lang.psi.GoFile).types.single { it.name == "W" }
        val service = io.github.golangsupport.semantic.api.GoSemanticService.getInstance(project)
        val writerRef = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, io.github.golangsupport.lang.psi.GoVarSpec::class.java).single()
        val iface = service.declarationType(writerRef.varDefinitionList.single()).underlying() as io.github.golangsupport.semantic.types.GoInterfaceType
        assertEquals(
            listOf("func (w *W) Write(p []byte) (n int, err error) {\n\tpanic(\"not implemented\")\n}"),
            io.github.golangsupport.ide.intentions.GoImplementStubs.missing(spec, iface),
        )
    }
}
