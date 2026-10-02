package io.github.golangsupport.lang.parser

import com.intellij.psi.impl.DebugUtil
import io.github.golangsupport.GoParsingTestCase
import java.io.File

/** Developer utility: parses `-Dgopsi.debugFile` (or testData/parser/debug.go, if present) and prints the PSI tree. */
class GoDebugParseTest : GoParsingTestCase() {
    fun testDebug() {
        val path = System.getProperty("gopsi.debugFile") ?: (myFullDataPath + "/debug.go")
        if (!File(path).exists()) {
            println("no debug file at $path; nothing to do")
            return
        }
        val text = File(path).readText()
        val psi = createPsiFile("debug", text)
        ensureParsed(psi)
        println("=== PSI for $path ===")
        println(DebugUtil.psiToString(psi, true, false))
    }
}
