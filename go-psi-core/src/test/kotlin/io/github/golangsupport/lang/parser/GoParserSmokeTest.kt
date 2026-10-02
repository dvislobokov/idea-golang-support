package io.github.golangsupport.lang.parser

import io.github.golangsupport.GoParsingTestCase

class GoParserSmokeTest : GoParsingTestCase() {

    /** testData/parser/Smoke.go */
    fun testSmoke() = doTest(true)
}
