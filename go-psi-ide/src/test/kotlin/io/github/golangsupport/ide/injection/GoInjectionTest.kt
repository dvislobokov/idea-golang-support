package io.github.golangsupport.ide.injection

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.injection.json.GoJsonDetect
import io.github.golangsupport.lang.psi.GoStringLiteral

/** Language injection into Go strings: RegExp (RE2) in the `regexp` calls and JSON in obvious JSON strings. */
class GoInjectionTest : GoSemanticIdeTestBase() {

    /** The injected language ids of the string literal at the caret (`<caret>` inside the literal), empty when there is none. */
    private fun injectedAt(body: String, imports: String = "\"regexp\""): List<String> {
        myFixture.configureByText("a.go", "package p\n\nimport (\n\t$imports\n)\n\n${body.trimIndent()}\n")
        // The fixture moves the caret into the injected fragment when there is one; a plain Go file means "not injected".
        val file = myFixture.file
        return if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) listOf(file.language.id) else emptyList()
    }

    // --- RegExp ---

    fun testRegexpCalls() {
        for (f in listOf("Compile", "MustCompile", "CompilePOSIX", "MustCompilePOSIX")) {
            assertEquals(f, listOf("RegExp"), injectedAt("var x = regexp.$f(\"a<caret>b+\")"))
        }
        assertEquals(listOf("RegExp"), injectedAt("var ok, _ = regexp.MatchString(`^a<caret>\\d+`, \"a1\")"))
        assertEquals(listOf("RegExp"), injectedAt("var ok, _ = regexp.Match(\"a<caret>b\", nil)"))
        assertEquals(listOf("RegExp"), injectedAt("var ok, _ = regexp.MatchReader(\"a<caret>b\", nil)"))
    }

    fun testRegexpNotInjected() {
        // a second argument, a method of *Regexp, a same-named function of another package, a plain variable
        assertEquals(emptyList<String>(), injectedAt("var ok, _ = regexp.MatchString(\"a\", \"a<caret>b\")"))
        assertEquals(emptyList<String>(), injectedAt("var re = regexp.MustCompile(\"a\")\nvar s = re.ReplaceAllString(\"x\", \"a<caret>b\")"))
        assertEquals(emptyList<String>(), injectedAt("var re = regexp.MustCompile(\"a\")\nvar ok = re.MatchString(\"a<caret>b\")"))
        assertEquals(emptyList<String>(), injectedAt("var x = fmt.Sprintf(\"a<caret>b\")", "\"fmt\""))
        assertEquals(emptyList<String>(), injectedAt("func MustCompile(s string) int { return 0 }\nvar x = MustCompile(\"a<caret>b\")"))
        assertEquals(emptyList<String>(), injectedAt("var pattern = \"a<caret>b+\""))
    }

    private fun highlight(body: String) {
        myFixture.configureByText("a.go", "package p\n\nimport \"regexp\"\n\n${body.trimIndent()}\n")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testLookaheadAndBackrefAreErrors() {
        highlight("var a = regexp.MustCompile(`a<error descr=\"RE2 (Go regexp) does not support lookahead\">(?=x)</error>`)")
        highlight("var a = regexp.MustCompile(`a<error descr=\"RE2 (Go regexp) does not support lookahead\">(?!x)</error>`)")
        highlight("var a = regexp.MustCompile(`(a)<error descr=\"RE2 (Go regexp) does not support backreferences\">\\1</error>`)")
    }

    fun testNamedGroupsAndRe2SyntaxAreFine() {
        highlight("var a = regexp.MustCompile(`(?P<name>x)+`)")
        highlight("var a = regexp.MustCompile(`(?<name>x)+`)")
        highlight("var a = regexp.MustCompile(`\\Qa.b\\E[[:alpha:]]+\\pL(?i:x)`)")
        // the interpreted form: `\\d` is the escape of a backslash
        highlight("var a = regexp.MustCompile(\"^\\\\d+(?P<n>[a-z]+)\$\")")
    }

    fun testLookbehindIsReported() {
        myFixture.configureByText("a.go", "package p\n\nimport \"regexp\"\n\nvar a = regexp.MustCompile(`(?<=x)y`)\n")
        assertTrue(myFixture.doHighlighting().any { it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.ERROR.myVal })
    }

    // --- JSON ---

    private val jsonImports = "\"encoding/json\"\n\t\"strings\""

    fun testJsonInUnmarshalAndDecoder() {
        assertEquals(listOf("JSON"), injectedAt("var e = json.Unmarshal([]byte(`{\"a\": <caret>1}`), &struct{}{})", jsonImports))
        assertEquals(listOf("JSON"), injectedAt("var e = json.Unmarshal([]byte(\"{\\\"a\\\": <caret>1}\"), &struct{}{})", jsonImports))
        assertEquals(listOf("JSON"), injectedAt("var d = json.NewDecoder(strings.NewReader(`[1, <caret>2]`))", jsonImports))
    }

    fun testJsonInNamedRawLiteral() {
        assertEquals(listOf("JSON"), injectedAt("const sampleJSON = `{\"a\": <caret>[1, 2]}`", jsonImports))
        assertEquals(listOf("JSON"), injectedAt("var jsonDoc, other = `[ {\"a\": <caret>1} ]`, 1", jsonImports))
    }

    fun testJsonNotInjected() {
        assertEquals(emptyList<String>(), injectedAt("const text = `{\"a\": <caret>1}`", jsonImports))
        assertEquals(emptyList<String>(), injectedAt("const sampleJSON = `not <caret>json`", jsonImports))
        assertEquals(emptyList<String>(), injectedAt("const sampleJSON = \"{\\\"a\\\": <caret>1}\"", jsonImports))
        assertEquals(emptyList<String>(), injectedAt("var x = strings.NewReader(`{\"a\": <caret>1}`)", jsonImports))
        assertEquals(emptyList<String>(), injectedAt("var x = strings.ToUpper(\"a<caret>b\")", jsonImports))
    }

    fun testJsonDetectionIsPure() {
        assertTrue(GoJsonDetect.looksLikeJson(" {\"a\": [1, {\"b\": \"}\"}]} "))
        assertTrue(GoJsonDetect.looksLikeJson("[]"))
        assertFalse(GoJsonDetect.looksLikeJson("{\"a\": 1"))
        assertFalse(GoJsonDetect.looksLikeJson("{[}]"))
        assertFalse(GoJsonDetect.looksLikeJson("plain"))
        assertFalse(GoJsonDetect.looksLikeJson("{\"a\": \"x}"))
        assertTrue(GoJsonDetect.nameSaysJson("userJSON"))
        assertFalse(GoJsonDetect.nameSaysJson("user"))
    }

    // --- the host ---

    fun testInterpretedEscaperMapsOffsets() {
        myFixture.configureByText("a.go", "package p\n\nvar s = \"a\\tb\\\\c\\x41\"\n")
        val literal = PsiTreeUtil.findChildOfType(myFixture.file, GoStringLiteral::class.java)!! as PsiLanguageInjectionHost
        val escaper = literal.createLiteralTextEscaper()
        val range = escaper.relevantTextRange
        val out = StringBuilder()
        assertTrue(escaper.decode(range, out))
        assertEquals("a\tb\\cA", out.toString())
        // 'b' is decoded char 2 and sits after the two-char escape `\t` (host text: "a\tb...): quote, a, \, t, b
        assertEquals(4, escaper.getOffsetInHost(2, range))
        assertEquals(range.endOffset, escaper.getOffsetInHost(out.length, range))
        assertTrue(escaper.isOneLine)
    }

    fun testHostUpdateText() {
        myFixture.configureByText("a.go", "package p\n\nvar s = `abc`\n")
        val literal = PsiTreeUtil.findChildOfType(myFixture.file, GoStringLiteral::class.java)!! as PsiLanguageInjectionHost
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { literal.updateText("`xyz`") }
        assertEquals("package p\n\nvar s = `xyz`\n", myFixture.file.text)
    }
}
