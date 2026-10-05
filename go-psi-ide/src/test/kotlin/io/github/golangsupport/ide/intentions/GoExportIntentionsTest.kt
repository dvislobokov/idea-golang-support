package io.github.golangsupport.ide.intentions

import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G4, declarations: Export, Migrate function parameter to method receiver (both rewrite usages in other files). */
class GoExportIntentionsTest : GoSemanticIdeTestBase() {

    private fun launch(intention: String) {
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == intention } ?: error("$intention not in ${offered.map { it.text }}"))
    }

    private fun assertNotOffered(text: String, intention: String) {
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertFalse("$intention in $offered", intention in offered)
    }

    // --- export ---

    fun testExportFunctionRenamesUsagesInThePackage() {
        val other = myFixture.addFileToProject("b.go", "package p\n\nfunc use() { private() }\n")
        myFixture.configureByText("a.go", "package p\n\nfunc <caret>private() {}\n")
        launch("Export")
        myFixture.checkResult("package p\n\nfunc Private() {}\n")
        assertEquals("package p\n\nfunc use() { Private() }\n", other.text)
    }

    fun testExportField() {
        myFixture.configureByText("a.go", "package p\n\ntype T struct{ <caret>name string }\n\nfunc (t T) get() string { return t.name }\n")
        launch("Export")
        myFixture.checkResult("package p\n\ntype T struct{ Name string }\n\nfunc (t T) get() string { return t.Name }\n")
    }

    fun testNoExportWhenTheNameIsTaken() {
        myFixture.addFileToProject("b.go", "package p\n\nfunc Parse() {}\n")
        assertNotOffered("package p\n\nfunc <caret>parse() {}\n", "Export")
    }

    fun testNoExportOfLocalsMainOrExported() {
        assertNotOffered("package p\n\nfunc f() {\n\t<caret>x := 1\n\t_ = x\n}\n", "Export")
        assertNotOffered("package main\n\nfunc <caret>main() {}\n", "Export")
        assertNotOffered("package p\n\nfunc <caret>Done() {}\n", "Export")
        assertNotOffered("package p\n\ntype T struct{ name string }\n\nfunc (t T) Name() string { return t.<caret>name }\n", "Export")
    }

    fun testNoExportOfAFieldWhenAMethodHasTheName() =
        assertNotOffered("package p\n\ntype T struct{ <caret>name string }\n\nfunc (t T) Name() string { return t.name }\n", "Export")

    // --- migrate parameter to receiver ---

    fun testMigrateParameterToReceiverRewritesCallsInOtherFiles() {
        val other = myFixture.addFileToProject(
            "b.go",
            "package p\n\nfunc use(v T, p *T) {\n\tscale(&v, 2)\n\tscale(p, 3)\n\tg := scale\n\t_ = g\n}\n",
        )
        myFixture.configureByText("a.go", "package p\n\ntype T struct{ n int }\n\nfunc <caret>scale(t *T, k int) { t.n *= k }\n")
        launch("Migrate function parameter to method receiver")
        myFixture.checkResult("package p\n\ntype T struct{ n int }\n\nfunc (t *T) scale(k int) { t.n *= k }\n")
        assertEquals("package p\n\nfunc use(v T, p *T) {\n\tv.scale(2)\n\tp.scale(3)\n\tg := (*T).scale\n\t_ = g\n}\n", other.text)
    }

    fun testMigrateTheOnlyParameterOfAGroup() {
        myFixture.configureByText("a.go", "package p\n\ntype P struct{}\n\nfunc <caret>same(a, b P) bool { return a == b }\n\nvar _ = same(P{}, P{})\n")
        launch("Migrate function parameter to method receiver")
        myFixture.checkResult("package p\n\ntype P struct{}\n\nfunc (a P) same(b P) bool { return a == b }\n\nvar _ = (P{}).same(P{})\n")
    }

    fun testMigrateRefusesANilReceiver() {
        myFixture.configureByText("a.go", "package p\n\ntype T struct{}\n\nfunc <caret>f(t *T) {}\n\nfunc g() { f(nil) }\n")
        try {
            launch("Migrate function parameter to method receiver")
            fail("expected a refusal")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message!!.contains("nil"))
        }
    }

    fun testNoMigrate() {
        assertNotOffered("package p\n\nimport \"time\"\n\nfunc <caret>f(d time.Duration) {}\n", "Migrate function parameter to method receiver")
        assertNotOffered("package p\n\ntype T struct{}\n\nfunc (T) f() {}\n\nfunc <caret>f(t T) {}\n", "Migrate function parameter to method receiver")
        assertNotOffered("package p\n\ntype I interface{ M() }\n\nfunc <caret>f(i I) {}\n", "Migrate function parameter to method receiver")
        assertNotOffered("package p\n\ntype T struct{}\n\nfunc <caret>f[X any](t T, x X) {}\n", "Migrate function parameter to method receiver")
        assertNotOffered("package p\n\nfunc <caret>f(n int) {}\n", "Migrate function parameter to method receiver")
    }
}
