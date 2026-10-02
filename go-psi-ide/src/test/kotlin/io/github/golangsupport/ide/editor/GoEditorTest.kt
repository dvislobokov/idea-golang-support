package io.github.golangsupport.ide.editor

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.LanguageCommenters
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoNamedElement

class GoEditorTest : GoIdeTestBase() {

    fun testExtensionsRegistered() {
        assertInstanceOf(LanguageBraceMatching.INSTANCE.forLanguage(GoLanguage), GoBraceMatcher::class.java)
        assertInstanceOf(LanguageCommenters.INSTANCE.forLanguage(GoLanguage), GoCommenter::class.java)
        assertInstanceOf(LanguageFindUsages.INSTANCE.forLanguage(GoLanguage), GoFindUsagesProvider::class.java)
    }

    fun testBraceMatchingForward() {
        myFixture.configureByText("a.go", "package p\n\nfunc f() <caret>{\n\tg(a[1], b)\n}\n")
        val matched = BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, true, myFixture.file)
        assertEquals(myFixture.file.text.lastIndexOf('}'), matched)
    }

    fun testBraceMatchingParensAndBrackets() {
        val text = "package p\n\nvar x = g(a[1], b)\n"
        myFixture.configureByText("a.go", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf('('))
        assertEquals(text.indexOf(')'), BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, true, myFixture.file))
        myFixture.editor.caretModel.moveToOffset(text.indexOf(']'))
        assertEquals(text.indexOf('['), BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, false, myFixture.file))
    }

    fun testStructuralBrace() {
        val matcher = GoBraceMatcher()
        assertTrue(matcher.pairs.single { it.leftBraceType.toString() == "{" }.isStructural)
        assertFalse(matcher.pairs.single { it.leftBraceType.toString() == "(" }.isStructural)
    }

    fun testCommentLine() {
        myFixture.configureByText("a.go", "package p\n\nfunc f() {\n\tx<caret> := 1\n}\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        // Go code style (Phase 6b): LINE_COMMENT_ADD_SPACE, comment at the code's indentation like gofmt keeps it
        myFixture.checkResult("package p\n\nfunc f() {\n\t// x := 1\n}\n")
    }

    fun testUncommentLine() {
        myFixture.configureByText("a.go", "package p\n\n//var <caret>x = 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("package p\n\nvar x = 1\n")
    }

    fun testCommentBlock() {
        myFixture.configureByText("a.go", "package p\n\nvar x = <selection>1 + 2</selection>\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        myFixture.checkResult("package p\n\nvar x = /*1 + 2*/\n")
    }

    fun testQuoteAutoClosesString() {
        myFixture.configureByText("a.go", "package p\n\nvar s = <caret>\n")
        myFixture.type('"')
        myFixture.checkResult("package p\n\nvar s = \"<caret>\"\n")
        myFixture.type("ab\"")
        myFixture.checkResult("package p\n\nvar s = \"ab\"<caret>\n")
    }

    fun testQuoteAutoClosesRawString() {
        myFixture.configureByText("a.go", "package p\n\nvar s = <caret>\n")
        myFixture.type('`')
        myFixture.checkResult("package p\n\nvar s = `<caret>`\n")
    }

    fun testQuoteAutoClosesRune() {
        myFixture.configureByText("a.go", "package p\n\nvar r = <caret>\n")
        myFixture.type('\'')
        myFixture.checkResult("package p\n\nvar r = '<caret>'\n")
    }

    fun testBraceAutoCloses() {
        myFixture.configureByText("a.go", "package p\n\nvar x = f<caret>\n")
        myFixture.type('(')
        myFixture.checkResult("package p\n\nvar x = f(<caret>)\n")
    }

    fun testFindUsagesTypeNames() {
        myFixture.configureByText(
            "a.go",
            """
            package p

            import alias "fmt"

            type T struct {
            	F int
            	Embedded
            }

            type I interface{ M() }

            const C = 1

            var V int

            func Gen[X any](a int) {
            L:
            	for {
            		break L
            	}
            }

            func (r *T) Method() {}
            """.trimIndent(),
        )
        val provider = GoFindUsagesProvider()
        val kinds = PsiTreeUtil.findChildrenOfType(myFixture.file, GoNamedElement::class.java)
            .associate { it.name to provider.getType(it) }
        assertEquals(
            mapOf(
                "p" to "package", "alias" to "package", "T" to "type", "F" to "field", "Embedded" to "field",
                "I" to "type", "M" to "method", "C" to "constant", "V" to "variable", "Gen" to "function",
                "X" to "type", "a" to "parameter", "L" to "label", "r" to "parameter", "Method" to "method",
            ),
            kinds,
        )
        val function = PsiTreeUtil.findChildrenOfType(myFixture.file, GoNamedElement::class.java).first { it.name == "Gen" }
        assertTrue(provider.canFindUsagesFor(function))
        assertEquals("Gen", provider.getDescriptiveName(function))
        assertEquals("Gen", provider.getNodeText(function, true))
        assertFalse(provider.canFindUsagesFor(myFixture.file))
    }

    fun testWordsScanner() {
        val words = mutableListOf<String>()
        GoWordsScanner().processWords("package p // hello world\nvar x = \"lit text\"") { occurrence ->
            words += occurrence.baseText.subSequence(occurrence.start, occurrence.end).toString()
            true
        }
        assertTrue(words.containsAll(listOf("p", "hello", "world", "x", "lit", "text")))
    }

    /** TODO items come from the parser definition's comment tokens; no extra registration is needed. */
    fun testTodoInComments() {
        myFixture.configureByText(
            "a.go",
            "package p\n\n// TODO first item\nfunc f() {\n\t/* FIXME second */\n\tx := \"TODO not a comment\"\n\t_ = x\n}\n",
        )
        val items = PsiTodoSearchHelper.getInstance(project).findTodoItems(myFixture.file)
        assertEquals(2, items.size)
        val texts = items.map { myFixture.file.text.substring(it.textRange.startOffset, it.textRange.endOffset) }.sorted()
        assertEquals(listOf("FIXME second ", "TODO first item"), texts)
    }
}
