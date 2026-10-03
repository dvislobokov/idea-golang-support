package io.github.golangsupport.ide.asm

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.lang.psi.GoFunctionDeclaration

/** `TEXT ·add(SB)` ↔ `func add(a, b int) int`: the file type, the reference of a symbol and the gutter markers on both sides. */
class GoAsmNavigationTest : GoIdeTestBase() {

    private lateinit var amd64: PsiFile
    private lateinit var arm64: PsiFile

    private fun fixture() {
        myFixture.addFileToProject("asm9/add.go", "package asm9\n\nfunc add(a, b int) int\n\nfunc asm9body() int { return 1 }\n\nfunc asm9missing()\n")
        amd64 = myFixture.addFileToProject(
            "asm9/add_amd64.s",
            "#include \"textflag.h\"\n\n// func add(a, b int) int\nTEXT ·add(SB), NOSPLIT, \$0-24\n\tMOVQ a+0(FP), AX\n\tADDQ b+8(FP), AX\n" +
                "\tMOVQ AX, ret+16(FP)\n\tRET\n\nTEXT ·asm9body(SB), NOSPLIT, \$0\n\tCALL runtime·morestack(SB)\n\tRET\n",
        )
        arm64 = myFixture.addFileToProject("asm9/add_arm64.s", "TEXT asm9·add<ABIInternal>(SB), NOSPLIT, \$0-24\n\tRET\n")
    }

    private fun symbol(file: PsiFile, text: String): GoAsmSymbol =
        PsiTreeUtil.findChildrenOfType(file, GoAsmSymbol::class.java).first { it.text == text }

    private fun describe(e: PsiElement?): String = when (e) {
        null -> "null"
        is GoFunctionDeclaration -> "func ${e.name} in ${e.containingFile.name}"
        is GoAsmSymbol -> "${e.text} in ${e.containingFile.name}"
        else -> e.toString()
    }

    private fun gutterTargets(tooltip: String): List<List<String>> = myFixture.findAllGutters().filter { it.tooltipText == tooltip }.map { g ->
        val info = (g as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo as RelatedItemLineMarkerInfo<*>
        info.createGotoRelatedItems().map { describe(it.element) }.sorted()
    }

    fun testFileTypeOnlyNextToGoFiles() {
        fixture()
        val lone = myFixture.addFileToProject("asm9lone/start.s", "TEXT _start(SB), 0, \$0\n")
        assertSame(GoAsmFileType, amd64.virtualFile.fileType)
        assertInstanceOf(amd64, GoAsmFile::class.java)
        assertNotSame(GoAsmFileType, lone.virtualFile.fileType)
        assertNotSame(GoAsmFileType, FileTypeManager.getInstance().getFileTypeByFileName("x.s"))
    }

    fun testTextSymbolResolvesToBodylessDeclaration() {
        fixture()
        val ref = symbol(amd64, "·add").reference!!
        assertEquals("add", ref.canonicalText)
        assertEquals("func add in add.go", describe(ref.resolve()))
        assertEquals("func add in add.go", describe(symbol(arm64, "asm9·add").reference!!.resolve()))
    }

    fun testSymbolOfAnotherPackageDoesNotResolve() {
        fixture()
        assertNull(symbol(amd64, "runtime·morestack").reference!!.resolve())
    }

    fun testCtrlClickInAssemblyGoesToGo() {
        fixture()
        myFixture.configureFromExistingVirtualFile(arm64.virtualFile)
        myFixture.editor.caretModel.moveToOffset(arm64.text.indexOf("add<"))
        assertEquals("func add in add.go", describe(myFixture.elementAtCaret))
    }

    fun testGutterOnGoDeclarationListsEveryArchitecture() {
        fixture()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("asm9/add.go"))
        // asm9body has a body (its TEXT does not make it assembly-backed), asm9missing has no TEXT: one marker only
        assertEquals(listOf(listOf("asm9·add in add_arm64.s", "·add in add_amd64.s")), gutterTargets("Implemented in assembly"))
    }

    fun testGutterOnTextSymbolGoesToGo() {
        fixture()
        myFixture.configureFromExistingVirtualFile(amd64.virtualFile)
        assertEquals(listOf(listOf("func add in add.go"), listOf("func asm9body in add.go")), gutterTargets("Go declaration").sortedBy { it.toString() })
    }

    fun testCommenter() {
        fixture()
        myFixture.configureFromExistingVirtualFile(arm64.virtualFile)
        myFixture.performEditorAction("CommentByLineComment")
        val text = myFixture.editor.document.text
        assertTrue(text, text.startsWith("//") && "TEXT asm9·add" in text.lineSequence().first())
    }
}
