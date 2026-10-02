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
        colours = settings.semanticColorsSource
        settings.languageServerEnabled = false
    }

    override fun tearDown() {
        try {
            settings.languageServerEnabled = server
            settings.semanticColorsSource = colours
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
        settings.semanticColorsSource = GoFeatureSource.GOPLS
        assertTrue(GoIdentifierAnnotator.coloursIdentifiers(project))
        val keys = annotatorKeys()
        for (expected in listOf("GO_DIRECTIVE", "GO_PACKAGE", "GO_TYPE_DECLARATION", "GO_FIELD", "GO_BUILTIN_TYPE", "GO_FUNCTION_DECLARATION", "GO_BUILTIN_FUNCTION", "GO_FUNCTION_CALL", "GO_BUILTIN_CONSTANT")) {
            assertTrue("$expected in $keys", expected in keys)
        }
    }

    fun testWithTheBuiltInSourceTheTextRulesLeaveTheIdentifiersAlone() {
        myFixture.configureByText("shop.go", code)
        settings.languageServerEnabled = true
        settings.semanticColorsSource = GoFeatureSource.NATIVE
        assertFalse(GoIdentifierAnnotator.coloursIdentifiers(project))
        assertEquals(listOf("GO_DIRECTIVE"), annotatorKeys())
        settings.languageServerEnabled = false // without the server whatever is built in is the source
        assertEquals(listOf("GO_DIRECTIVE"), annotatorKeys())
    }

    /** The whole pass without the server, the semantic annotator of go-psi-ide shut by its gate: the directive is the one colour left. */
    fun testWithoutTheServerThePassColoursOnlyTheDirective() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = false
        }, testRootDisposable)
        myFixture.configureByText("shop.go", code)
        val keys = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.INFORMATION }
            .mapNotNull { it.forcedTextAttributesKey?.externalName?.takeIf { key -> key.startsWith("GO_") } }
        assertEquals(listOf(GoSyntaxHighlighter.DIRECTIVE.externalName), keys)
    }
}
