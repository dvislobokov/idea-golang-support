package io.github.golangsupport

import com.intellij.openapi.util.TextRange
import io.github.golangsupport.lang.GoBlockFolds
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoTestNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTestNavigationTest {
    @Test fun fileNames() {
        assertEquals("order_test.go", GoTestNames.testFileName("order.go"))
        assertEquals("order.go", GoTestNames.sourceFileName("order_test.go"))
    }

    @Test fun testsOfAFunctionAndOfAMethod() {
        assertTrue(GoTestNames.isTestOf("TestTotal", "Total", null))
        assertTrue(GoTestNames.isTestOf("TestTotal_empty", "Total", null))
        assertTrue(GoTestNames.isTestOf("BenchmarkTotal", "Total", null))
        assertTrue(GoTestNames.isTestOf("Test_calc", "calc", null))
        assertTrue(GoTestNames.isTestOf("TestOrder_Total", "Total", "Order"))
        assertTrue(GoTestNames.isTestOf("Test_Order_Total", "Total", "Order"))
        assertFalse(GoTestNames.isTestOf("TestTotals", "Total", null))
        assertFalse(GoTestNames.isTestOf("helper", "Total", null))
    }

    @Test fun subjectsOfATest() {
        assertEquals(listOf("Total" to null), GoTestNames.subjectsOf("TestTotal"))
        assertEquals(listOf("calc" to null), GoTestNames.subjectsOf("Test_calc"))
        assertEquals(listOf("Order_Total" to null, "Total" to "Order", "Order" to null), GoTestNames.subjectsOf("TestOrder_Total"))
        assertEquals(emptyList<Pair<String, String?>>(), GoTestNames.subjectsOf("helper"))
    }

    @Test fun blocksInsideAFunctionFold() {
        val source = """
            package p

            func f(items []int) int {
            	total := 0
            	for _, x := range items {
            		if x > 0 {
            			total += x
            		}
            	}
            	m := map[string]int{
            		"a": 1,
            	}
            	return total + m["a"]
            }
        """.trimIndent()
        val body = GoDeclarations.scan(source).declarations.first { it.name == "f" }.body!!
        val folds = GoBlockFolds.find(source, body).map { source.substring(it.startOffset, it.endOffset) }
        assertEquals(3, folds.size)
        assertTrue(folds[0].startsWith("{\n\t\tif x > 0"))
        assertTrue(folds[1].startsWith("{\n\t\t\ttotal += x"))
        assertTrue(folds[2].startsWith("{\n\t\t\"a\": 1"))
        // the body itself is folded by the declaration, not here
        assertFalse(folds.any { TextRange(body.startOffset, body.endOffset).length == it.length })
    }
}
