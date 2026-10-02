package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.CodeInsightTestUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec

/** Go to Declaration / Type Declaration / Implementation / Super and the implementation gutter markers. */
class GoNavigationTest : GoSemanticIdeTestBase() {

    override val testDataSubdir: String = "navigation"

    // --- Go to Declaration ---

    fun testGotoDeclarationIntoGoroot() {
        myFixture.configureByText("main.go", "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Print<caret>ln(\"x\")\n}\n")
        val target = myFixture.elementAtCaret
        assertInstanceOf(target, GoFunctionDeclaration::class.java)
        assertEquals("Println", (target as GoNamedElement).name)
        assertTrue(target.containingFile.virtualFile.path, target.containingFile.virtualFile.path.endsWith("/src/fmt/print.go"))
    }

    fun testGotoDeclarationOfPackageQualifierAndImportPath() {
        myFixture.configureByText("main.go", "package main\n\nimport \"strings\"\n\nvar _ = str<caret>ings.ToUpper(\"x\")\n")
        val spec = myFixture.elementAtCaret
        assertEquals("strings", (spec as GoNamedElement).name)
        myFixture.configureByText("main2.go", "package main\n\nimport \"str<caret>ings\"\n")
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset)
        val dir = ref?.resolve()
        assertInstanceOf(dir, PsiDirectory::class.java)
        assertTrue((dir as PsiDirectory).virtualFile.path.endsWith("/src/strings"))
    }

    fun testGotoDeclarationAcrossFilesOfPackage() {
        myFixture.addFileToProject("p/decl.go", "package p\n\ntype Point struct{ X int }\n\nfunc New() *Point { return nil }\n")
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject("p/use.go", "package p\n\nfunc use() int { return Ne<caret>w().X }\n").virtualFile,
        )
        val target = myFixture.elementAtCaret as GoNamedElement
        assertEquals("New", target.name)
        assertEquals("decl.go", target.containingFile.name)
    }

    // --- Go to Type Declaration ---

    private fun typeDeclarationAtCaret(text: String): PsiElement? {
        myFixture.configureByText("t.go", text)
        val symbol = myFixture.elementAtCaret
        val provider = TypeDeclarationProvider.EP_NAME.extensionList.filterIsInstance<GoTypeDeclarationProvider>().single()
        return provider.getSymbolTypeDeclarations(symbol)?.singleOrNull()
    }

    fun testTypeDeclarationThroughPointerAndSlice() {
        val target = typeDeclarationAtCaret("package t\n\ntype Point struct{}\n\nfunc f(ps []*Point) {\n\t_ = p<caret>s\n}\n")
        assertEquals("Point", (target as GoTypeSpec).name)
    }

    fun testTypeDeclarationOfMapValueAndChannel() {
        val target = typeDeclarationAtCaret("package t\n\ntype V int\n\nvar m map[string]chan V\n\nfunc f() { _ = <caret>m }\n")
        assertEquals("V", (target as GoTypeSpec).name)
    }

    fun testTypeDeclarationOfFieldAndFunctionResult() {
        val field = typeDeclarationAtCaret("package t\n\ntype A struct{ B *B }\ntype B struct{}\n\nfunc f(a A) { _ = a.<caret>B }\n")
        assertEquals("B", (field as GoTypeSpec).name)
        val result = typeDeclarationAtCaret("package t\n\ntype R struct{}\n\nfunc mk() *R { return nil }\n\nvar _ = m<caret>k\n")
        assertEquals("R", (result as GoTypeSpec).name)
    }

    fun testTypeDeclarationOfGenericInstantiationAndTypeParameter() {
        val generic = typeDeclarationAtCaret("package t\n\ntype List[T any] struct{ items []T }\n\nvar l List[int]\n\nfunc f() { _ = <caret>l }\n")
        assertEquals("List", (generic as GoTypeSpec).name)
        val param = typeDeclarationAtCaret("package t\n\nfunc f[T any](x T) { _ = <caret>x }\n")
        assertInstanceOf(param, GoTypeParamDefinition::class.java)
    }

    fun testTypeDeclarationIntoGoroot() {
        val target = typeDeclarationAtCaret("package t\n\nimport \"strings\"\n\nfunc f(b *strings.Builder) { _ = <caret>b }\n")
        assertEquals("Builder", (target as GoTypeSpec).name)
        assertTrue(target.containingFile.virtualFile.path.endsWith("/src/strings/builder.go"))
    }

    // --- implementations ---

    private fun openShapes() {
        // A Stringer in another package of the project: "implements" markers search all scopes.
        myFixture.addFileToProject("fmtlike/fmt.go", "package fmtlike\n\ntype Stringer interface {\n\tString() string\n}\n")
        myFixture.configureFromExistingVirtualFile(myFixture.copyFileToProject("shapes/shapes.go", "shapes/shapes.go"))
    }

    private fun moveCaretTo(pattern: String, delta: Int = 0) {
        val offset = myFixture.file.text.indexOf(pattern)
        assertTrue("'$pattern' not found", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + delta)
    }

    private fun names(elements: Collection<PsiElement>): List<String> = elements.map { e ->
        when (e) {
            is GoMethodDeclaration -> "${e.receiverTypeName}.${e.name}"
            is GoMethodSpec -> "${GoImplementations.interfaceSpecOf(e)?.name}.${e.name}"
            is GoNamedElement -> e.name ?: "?"
            else -> e.toString()
        }
    }.sorted()

    fun testGotoImplementationOfInterface() {
        openShapes()
        moveCaretTo("Shape interface", 1)
        val data = CodeInsightTestUtil.gotoImplementation(myFixture.editor, myFixture.file)
        assertEquals(listOf("Circle", "Square"), names(data.targets.toList()))
    }

    fun testGotoImplementationOfInterfaceMethodAndCallSite() {
        openShapes()
        moveCaretTo("Area() float64\n\tPerimeter", 1)
        assertEquals(listOf("Circle.Area", "Square.Area"), names(CodeInsightTestUtil.gotoImplementation(myFixture.editor, myFixture.file).targets.toList()))
        moveCaretTo("s.Area()", 3)
        assertEquals(listOf("Circle.Area", "Square.Area"), names(CodeInsightTestUtil.gotoImplementation(myFixture.editor, myFixture.file).targets.toList()))
    }

    fun testSingleMethodInterfaceImplementations() {
        openShapes()
        val named = myFixture.findElementByText("Named interface", GoTypeSpec::class.java)
        val scope = GlobalSearchScope.projectScope(project)
        assertEquals(listOf("Circle", "Partial", "Square"), names(GoImplementations.implementingTypes(named, scope)))
    }

    fun testGotoSuperFromMethodAndType() {
        openShapes()
        moveCaretTo("func (s *Square) Area", 18)
        assertEquals(listOf("Shape.Area"), names(GoGotoSuperHandler.findTargets(myFixture.file.findElementAt(myFixture.caretOffset)!!)))
        moveCaretTo("func (c Circle) String", 17)
        // Circle.String implements fmtlike.Stringer from another package.
        val stringer = GoGotoSuperHandler.findTargets(myFixture.file.findElementAt(myFixture.caretOffset)!!)
        assertTrue(names(stringer).toString(), "Stringer.String" in names(stringer))
        moveCaretTo("type Circle", 6)
        val interfaces = names(GoGotoSuperHandler.findTargets(myFixture.file.findElementAt(myFixture.caretOffset)!!))
        assertTrue(interfaces.toString(), interfaces.containsAll(listOf("Named", "Shape", "Stringer")))
        moveCaretTo("type Partial", 6)
        val partial = names(GoGotoSuperHandler.findTargets(myFixture.file.findElementAt(myFixture.caretOffset)!!))
        assertTrue(partial.toString(), "Named" in partial && "Shape" !in partial)
        moveCaretTo("func (Wrong) Perimeter", 14)
        assertEmpty(GoGotoSuperHandler.findTargets(myFixture.file.findElementAt(myFixture.caretOffset)!!))
    }

    fun testImplementationLineMarkers() {
        openShapes()
        val gutters = myFixture.findAllGutters().mapNotNull { g ->
            val tooltip = g.tooltipText ?: return@mapNotNull null
            val line = myFixture.editor.document.getLineNumber(
                (g as? com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo?.startOffset ?: return@mapNotNull null,
            )
            val text = myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(
                myFixture.editor.document.getLineStartOffset(line), myFixture.editor.document.getLineEndOffset(line))).trim()
            "$tooltip: $text"
        }.sorted()
        assertGolden("shapes/gutters.txt", gutters.joinToString("\n"))
    }
}
