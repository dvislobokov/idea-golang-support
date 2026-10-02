package io.github.golangsupport.ide.rename

import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoVarDefinition

/** Rename over `testData/rename/<name>.go` -> `<name>_after.go`, plus the name validators. */
class GoRenameTest : GoSemanticIdeTestBase() {

    override val testDataSubdir: String = "rename"

    private fun doTest(name: String, newName: String, after: String = "${name}_after.go") {
        myFixture.configureByFile("$name.go")
        myFixture.renameElementAtCaret(newName)
        myFixture.checkResultByFile(after)
    }

    fun testLocal() = doTest("local", "sum")

    fun testParameter() = doTest("param", "x")

    fun testFieldWithLiteralKeysAndPromotion() = doTest("field", "Title")

    fun testType() = doTest("type", "Vec")

    fun testLabel() = doTest("label", "rows")

    fun testInterfaceMethodRenamesImplementations() = doTest("iface", "Fetch")

    fun testImplementingMethodRenamesInterfaceOnYes() {
        TestDialogManager.setTestDialog(TestDialog.OK)
        doTest("impl", "Fetch", "iface_after.go")
    }

    fun testImplementingMethodOnlyOnNo() {
        TestDialogManager.setTestDialog(TestDialog.NO)
        doTest("impl", "Fetch", "impl_only_after.go")
    }

    fun testMethodAcrossFiles() {
        myFixture.addFileToProject("p/a.go", "package p\n\ntype T struct{}\n\nfunc (t T) Run() {}\n")
        val b = myFixture.addFileToProject("p/b.go", "package p\n\nfunc use(t T) {\n\tt.R<caret>un()\n\tf := t.Run\n\tf()\n}\n")
        myFixture.configureFromExistingVirtualFile(b.virtualFile)
        myFixture.renameElementAtCaret("Start")
        myFixture.checkResult("package p\n\nfunc use(t T) {\n\tt.Start()\n\tf := t.Start\n\tf()\n}\n")
        assertEquals("package p\n\ntype T struct{}\n\nfunc (t T) Start() {}\n", myFixture.findFileInTempDir("p/a.go").let { psiManager.findFile(it)!!.text })
    }

    fun testImportAlias() {
        myFixture.configureByText("i.go", "package i\n\nimport st<caret>r \"strings\"\n\nvar _ = str.ToUpper(\"x\") + str.ToLower(\"y\")\n")
        myFixture.renameElementAtCaret("s")
        myFixture.checkResult("package i\n\nimport s \"strings\"\n\nvar _ = s.ToUpper(\"x\") + s.ToLower(\"y\")\n")
    }

    fun testNamesValidator() {
        val validator = LanguageNamesValidation.INSTANCE.forLanguage(GoLanguage)
        assertTrue(validator.isIdentifier("abc", project))
        assertTrue(validator.isIdentifier("_x1", project))
        assertTrue(validator.isIdentifier("переменная", project))
        assertFalse(validator.isIdentifier("1abc", project))
        assertFalse(validator.isIdentifier("a-b", project))
        assertFalse(validator.isIdentifier("func", project))
        assertTrue(validator.isKeyword("range", project))
        assertFalse(validator.isKeyword("int", project))
    }

    fun testRenameInputValidator() {
        val file = myFixture.configureByText("v.go", "package v\n\nfunc f() {}\n")
        val fn = PsiTreeUtil.findChildOfType(file, GoFunctionDeclaration::class.java)!!
        val validator = GoRenameInputValidator()
        assertTrue(validator.pattern.accepts(fn))
        assertTrue(validator.isInputValid("g", fn, ProcessingContext()))
        assertFalse(validator.isInputValid("type", fn, ProcessingContext()))
        assertEquals("'type' is a Go keyword", validator.getErrorMessage("type", project))
        assertEquals("'1x' is not a valid Go identifier", validator.getErrorMessage("1x", project))
        assertNull(validator.getErrorMessage("ok", project))
    }

    fun testInplaceRenameOnlyForLocalDeclarations() {
        val file = myFixture.configureByText("v.go", "package v\n\nvar global int\n\nfunc f(p int) {\nl:\n\tx := p\n\t_ = x\n\tgoto l\n}\n")
        val support = com.intellij.lang.LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)
        val vars = PsiTreeUtil.findChildrenOfType(file, GoVarDefinition::class.java).associateBy { it.name }
        assertTrue(support.isInplaceRenameAvailable(vars.getValue("x"), null))
        assertTrue(support.isInplaceRenameAvailable(PsiTreeUtil.findChildOfType(file, GoParamDefinition::class.java)!!, null))
        assertTrue(support.isInplaceRenameAvailable(PsiTreeUtil.findChildOfType(file, GoLabelDefinition::class.java)!!, null))
        assertFalse(support.isInplaceRenameAvailable(vars.getValue("global"), null))
        assertFalse(support.isMemberInplaceRenameAvailable(vars.getValue("global"), null))
    }
}
