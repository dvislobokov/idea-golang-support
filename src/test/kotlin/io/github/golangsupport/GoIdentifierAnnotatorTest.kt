package io.github.golangsupport

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.codeInsight.daemon.impl.AnnotationSessionImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoIdentifierAnnotator
import io.github.golangsupport.lang.GoSyntaxHighlighter
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/**
 * The text rules of [GoIdentifierAnnotator] colour the identifiers only while gopls is the source of the semantic colours; with the
 * Built-in source the semantic annotator of go-psi-ide colours them by resolve, and these rules stand down (MIGRATION.md step 8d).
 * The compiler directives are coloured by the text rules in every mode: no other source knows them.
 */
class GoIdentifierAnnotatorTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var server = true
    private var colours = GoFeatureSource.GOPLS

    private val code = """
        //go:build linux

        package shop

        import "fmt"

        type Server struct{ Port int }

        func (s *Server) Start() error {
        	if s == nil || len(fmt.Sprint(s.Port)) == 0 {
        		return fmt.Errorf("no port")
        	}
        	return nil
        }
    """.trimIndent()

    // a file opened in an editor starts gopls, where it is installed: not in a test (the server-on case calls the annotator directly)
    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        colours = settings.languageFeaturesSource
        settings.languageServerEnabled = false
    }

    override fun tearDown() {
        try {
            settings.languageServerEnabled = server
            settings.languageFeaturesSource = colours
        } finally {
            super.tearDown()
        }
    }

    /** The keys the annotator alone lays over the file, by running it on every element with a session of its own (no daemon, no gopls). */
    private fun annotatorKeys(): List<String> {
        val annotator = GoIdentifierAnnotator()
        return AnnotationSessionImpl.computeWithSession(myFixture.file, false, annotator) { holder ->
            holder as AnnotationHolderImpl
            SyntaxTraverser.psiTraverser(myFixture.file).forEach { holder.runAnnotatorWithContext(it, annotator) }
            holder.map { it.textAttributes.externalName }
        }
    }

    fun testWithGoplsTheTextRulesColourTheIdentifiers() {
        myFixture.configureByText("shop.go", code)
        settings.languageServerEnabled = true
        settings.languageFeaturesSource = GoFeatureSource.GOPLS
        assertTrue(GoIdentifierAnnotator.coloursIdentifiers(project))
        val keys = annotatorKeys()
        for (expected in listOf("GO_COMMENT_KEYWORD", "GO_BUILD_TAG", "GO_PACKAGE", "GO_TYPE_DECLARATION", "GO_FIELD", "GO_BUILTIN_TYPE", "GO_FUNCTION_DECLARATION", "GO_BUILTIN_FUNCTION", "GO_FUNCTION_CALL", "GO_BUILTIN_CONSTANT")) {
            assertTrue("$expected in $keys", expected in keys)
        }
    }

    fun testWithTheBuiltInSourceTheTextRulesLeaveTheIdentifiersAlone() {
        myFixture.configureByText("shop.go", code)
        settings.languageServerEnabled = true
        settings.languageFeaturesSource = GoFeatureSource.NATIVE
        assertFalse(GoIdentifierAnnotator.coloursIdentifiers(project))
        assertEquals(DIRECTIVE_KEYS, annotatorKeys())
        settings.languageServerEnabled = false // without the server whatever is built in is the source
        assertEquals(DIRECTIVE_KEYS, annotatorKeys())
    }

    /** The whole pass without the server, the semantic annotator of go-psi-ide shut by its gate: the directive is the one colour left. */
    fun testWithoutTheServerThePassColoursOnlyTheDirective() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = false
        }, testRootDisposable)
        myFixture.configureByText("shop.go", code)
        val keys = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.INFORMATION }
            .mapNotNull { it.forcedTextAttributesKey?.externalName?.takeIf { key -> key.startsWith("GO_") } }
        assertEquals(DIRECTIVE_KEYS, keys)
    }

    /** GoLand's parts of a directive: the name a comment keyword, the arguments the directive, build expressions split up. */
    fun testDirectiveParts() {
        fun parts(comment: String) = GoIdentifierAnnotator.directiveRanges(comment).map { (range, key) -> "${range.substring(comment)} ${key.externalName}" }
        assertEquals(
            listOf("go:build GO_COMMENT_KEYWORD", "( GO_BUILD_PAREN", "linux GO_BUILD_TAG", "|| GO_BUILD_OPERATOR", "! GO_BUILD_OPERATOR",
                "windows GO_BUILD_TAG", ") GO_BUILD_PAREN", "&& GO_BUILD_OPERATOR", "go1.21 GO_BUILD_TAG"),
            parts("//go:build (linux || !windows) && go1.21"),
        )
        assertEquals(listOf("linux GO_BUILD_TAG", ", GO_BUILD_OPERATOR", "arm GO_BUILD_TAG", "! GO_BUILD_OPERATOR", "cgo GO_BUILD_TAG"), parts("// +build linux,arm !cgo"))
        assertEquals(listOf("go:generate GO_COMMENT_KEYWORD", "stringer -type=Level GO_DIRECTIVE"), parts("//go:generate stringer -type=Level"))
        assertEquals(listOf("go:embed GO_COMMENT_KEYWORD", "static/* GO_DIRECTIVE"), parts("//go:embed static/*"))
        assertEquals(listOf("line GO_COMMENT_KEYWORD", "a.go:10 GO_DIRECTIVE"), parts("//line a.go:10"))
        assertEquals(emptyList<String>(), parts("// go:build is not a directive with a space"))
        assertEquals(emptyList<String>(), parts("// plain comment"))
    }

    private companion object {
        val DIRECTIVE_KEYS = listOf("GO_COMMENT_KEYWORD", "GO_BUILD_TAG")
    }
}
