package io.github.golangsupport

import io.github.golangsupport.lang.GoSemanticColors
import io.github.golangsupport.lang.GoSyntaxHighlighter
import io.github.golangsupport.lint.GoErrcheckFixes
import io.github.golangsupport.lint.GoNolintFix
import io.github.golangsupport.lint.GoSignatures
import io.github.golangsupport.lsp.GoplsActionKinds
import io.github.golangsupport.lsp.GoplsOptions
import io.github.golangsupport.settings.GoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoFixesTest {
    @Test fun signatures() {
        assertEquals(2, GoSignatures.resultCount("func os.Open(name string) (*os.File, error)"))
        assertEquals(1, GoSignatures.resultCount("func (f *os.File) Close() error"))
        assertEquals(0, GoSignatures.resultCount("func fmt.Println(a ...any)"))
        assertEquals(3, GoSignatures.resultCount("func Map[K comparable, V any](m map[K]V, f func(K, V) (K, error)) (map[K]V, func() error, error)"))
        assertEquals(1, GoSignatures.resultCount("func New() func(int, int) (int, error)"))
        assertNull(GoSignatures.resultCount("var x int"))
        // the hover of gopls 0.23, as tools/gopls shows it
        assertEquals("func os.Open(name string) (*os.File, error)", GoSignatures.inHover("```go\nfunc os.Open(name string) (*os.File, error)\n```\n\n---\n\nOpen opens the named file"))
        assertNull(GoSignatures.inHover("```go\nvar store.ErrEmpty error\n```"))
    }

    @Test fun uncheckedErrorFixes() {
        assertEquals("os.Open(\"x\")", GoErrcheckFixes.callStatement("\tos.Open(\"x\")  "))
        assertNull(GoErrcheckFixes.callStatement("\tdefer os.Remove(\"y\")"))
        assertNull(GoErrcheckFixes.callStatement("\tgo run()"))
        assertNull(GoErrcheckFixes.callStatement("\tx := f()"))
        assertNull(GoErrcheckFixes.callStatement("\tf(func() {"))
        assertEquals(4, GoErrcheckFixes.functionNameOffset("\tos.Open(\"x\")"))
        assertEquals("_, _ = os.Open(\"x\")", GoErrcheckFixes.ignore("os.Open(\"x\")", 2))
        assertEquals("_ = f.Close()", GoErrcheckFixes.ignore("f.Close()", 1))
        assertEquals("_, err := os.Open(\"x\")\n\tif err != nil {\n\t\treturn err\n\t}", GoErrcheckFixes.handle("os.Open(\"x\")", 2, "\t", "\t"))
        assertEquals("if err := f.Close(); err != nil {\n\t\treturn err\n\t}", GoErrcheckFixes.handle("f.Close()", 1, "\t", "\t"))
    }

    @Test fun nolint() {
        assertEquals("\tos.Open(\"x\") //nolint:errcheck", GoNolintFix.withNolint("\tos.Open(\"x\")  ", "errcheck"))
        assertEquals("\tf() //nolint:gosec,errcheck // why", GoNolintFix.withNolint("\tf() //nolint:gosec // why", "errcheck"))
        assertEquals("\tf() //nolint:errcheck", GoNolintFix.withNolint("\tf() //nolint:errcheck", "errcheck"))
    }

    /** The tokens as `tools/gopls/semantic_tokens.py` prints them for the playground. */
    @Test fun coloursOfSemanticTokens() {
        fun key(type: String, vararg modifiers: String) = GoSemanticColors.key(type, modifiers.toList())
        assertEquals(GoSyntaxHighlighter.PACKAGE, key("namespace"))
        assertEquals(GoSyntaxHighlighter.TYPE_REFERENCE, key("type", "struct"))
        assertEquals(GoSyntaxHighlighter.TYPE_DECLARATION, key("type", "definition", "interface"))
        assertEquals(GoSyntaxHighlighter.BUILTIN_TYPE, key("type", "defaultLibrary", "string"))
        assertEquals(GoSyntaxHighlighter.FUNCTION_DECLARATION, key("function", "definition", "signature"))
        assertEquals(GoSyntaxHighlighter.FUNCTION_CALL, key("method", "signature"))
        assertEquals(GoSyntaxHighlighter.BUILTIN_FUNCTION, key("function", "defaultLibrary"))
        assertEquals(GoSyntaxHighlighter.FIELD, key("property", "string"))
        assertEquals(GoSyntaxHighlighter.CONSTANT, key("variable", "definition", "readonly"))
        assertEquals(GoSyntaxHighlighter.BUILTIN_CONSTANT, key("variable", "readonly", "defaultLibrary"))
        assertEquals(GoSyntaxHighlighter.PACKAGE_VARIABLE, key("variable", "static", "slice"))
        assertEquals(GoSyntaxHighlighter.LOCAL_VARIABLE, key("variable", "definition", "pointer"))
        assertEquals(GoSyntaxHighlighter.PARAMETER, key("parameter"))
        // the lexer has coloured these already
        assertNull(key("keyword"))
        assertNull(key("string"))
    }

    @Test fun packageNames() {
        assertEquals("fmt", GoSemanticColors.packageName("fmt"))
        assertEquals("uuid", GoSemanticColors.packageName("github.com/google/uuid"))
        assertEquals("chi", GoSemanticColors.packageName("github.com/go-chi/chi/v5"))
        assertEquals("yaml", GoSemanticColors.packageName("gopkg.in/yaml.v3"))
        assertEquals("redis", GoSemanticColors.packageName("github.com/redis/go-redis"))
    }

    /** gopls shows a balloon that never goes away for settings it does not like; checked against the real server with tools/gopls/probe.py. */
    @Test fun settingsOfGopls() {
        val options = GoplsOptions.build(GoSettings())
        val lenses = options["codelenses"] as Map<*, *>
        assertTrue("vulncheck" in lenses)
        // superseded by `vulncheck`: "Only 'vulncheck' should be set"
        assertFalse("run_govulncheck" in lenses)
        // tests are run from the gutter, with the test tree
        assertFalse("test" in lenses)
        // with it `os.Op` + Tab gives `os.Open(name string)`: the signature as text (tools/gopls/completion.py shows both forms)
        assertFalse("usePlaceholders" in options)
        assertEquals(true, options["staticcheck"])
        assertEquals(true, options["semanticTokens"])
    }

    @Test fun actionsOfGoplsWorthShowing() {
        assertTrue(listOf("refactor.extract.variable", "refactor.inline.call", "source.addTest", "refactor.rewrite.fillStruct", "quickfix", null).all(GoplsActionKinds::isEditing))
        assertFalse(listOf("source.doc", "source.assembly", "gopls.doc.features", "source.freesymbols", "source.splitPackage").any(GoplsActionKinds::isEditing))
    }
}
