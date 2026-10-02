package io.github.golangsupport

import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase
import io.github.golangsupport.lang.lexer.GoLexer

/** Base class for lexer tests; file-based tests read `testData/lexer/<TestName>.go`. */
abstract class GoLexerTestCase : LexerTestCase() {

    override fun createLexer(): Lexer = GoLexer()

    override fun getDirPath(): String = "lexer"

    override fun getPathToTestDataFile(extension: String): String =
        GoTestUtil.testDataPath(dirPath) + "/" + getTestName(false) + extension
}
