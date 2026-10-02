package io.github.golangsupport

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
}
