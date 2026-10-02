package io.github.golangsupport

import com.intellij.lang.LanguageASTFactory
import com.intellij.openapi.util.io.FileUtil
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.impl.GoASTFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.golangsupport.lang.parser.GoParserDefinition
import java.io.File

/**
 * Base class for parser golden tests. Sources live in `testData/parser/<TestName>.go`, the
 * expected PSI dump in `<TestName>.txt` next to it.
 *
 * With `-Dgopsi.updateGoldens=true` the expected file is (re)written from the actual tree and the
 * test passes; review the golden diff by hand. Without the flag, a missing golden is created and
 * the test fails so that new goldens are always reviewed.
 */
abstract class GoParsingTestCase(dataPath: String = "parser") : ParsingTestCase(dataPath, "go", GoParserDefinition()) {

    override fun setUp() {
        super.setUp()
        // The production plugin.xml registers it; ParsingTestCase does not load descriptors.
        addExplicitExtension(LanguageASTFactory.INSTANCE, GoLanguage, GoASTFactory())
    }

    override fun getTestDataPath(): String = GoTestUtil.testDataPath()

    override fun skipSpaces(): Boolean = false

    override fun includeRanges(): Boolean = true

    /**
     * Parses `<TestName>.go` and compares the PSI dump with the golden (always); with [checkErrors]
     * also asserts there are no error elements. Replaces the platform's `doTest(checkResult)`.
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun doTest(checkErrors: Boolean) {
        val name = getTestName(false)
        val text = loadFile("$name.$myFileExt")
        myFile = createPsiFile(name, text)
        ensureParsed(myFile)
        assertEquals("PSI text differs from the source text", text, myFile.text)

        val actual = toParseTreeText(myFile, skipSpaces(), includeRanges()).trim()
        val golden = File(myFullDataPath, "$myFilePrefix$name.txt")

        if (GoTestUtil.updateGoldens || !golden.exists()) {
            val existed = golden.exists()
            FileUtil.writeToFile(golden, actual)
            if (!GoTestUtil.updateGoldens && !existed) {
                fail("Golden file did not exist and was created: ${golden.path}. Review it and re-run.")
            }
        } else {
            assertSameLinesWithFile(golden.path, actual)
        }

        if (checkErrors) {
            ensureNoErrorElements()
        }
    }

    /** True if [element] contains a [PsiErrorElement]. */
    protected fun hasErrorElements(element: PsiElement): Boolean =
        PsiTreeUtil.findChildOfType(element, PsiErrorElement::class.java) != null
}
