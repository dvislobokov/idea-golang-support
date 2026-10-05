package io.github.golangsupport

import io.github.golangsupport.debugger.GoDataViews
import io.github.golangsupport.debugger.GoIntegerFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debugger | Data Views | Go over the texts delve sends: integers, pointer addresses, which values may have a String(). */
class GoDebugDataViewsTest {
    private fun render(type: String?, value: String, integers: GoIntegerFormat = GoIntegerFormat.DECIMAL, pointers: Boolean = true) =
        GoDataViews.render(type, value, integers, pointers)

    @Test fun integersInEachFormat() {
        assertEquals("42", render("int", "42"))
        assertEquals("0x2a", render("int", "42", GoIntegerFormat.HEXADECIMAL))
        assertEquals("-0x2a", render("int64", "-42", GoIntegerFormat.HEXADECIMAL))
        assertEquals("0b101010", render("int32", "42", GoIntegerFormat.BINARY))
        assertEquals("42 = 0x2a", render("int", "42", GoIntegerFormat.BOTH))
        assertEquals("-1 = -0x1", render("int8", "-1", GoIntegerFormat.BOTH))
    }

    @Test fun anUnsignedValueIsDelveOwnTextInDecimal() {
        // delve writes `= 0x…` after every unsigned value, of a named type too
        assertEquals("200 = 0xc8", render("uint8", "200 = 0xc8"))
        assertEquals("0xc8", render("uint8", "200 = 0xc8", GoIntegerFormat.HEXADECIMAL))
        assertEquals("0b11001000", render("main.Flags", "200 = 0xc8", GoIntegerFormat.BINARY))
        assertEquals("200 = 0xc8", render("uint8", "200 = 0xc8", GoIntegerFormat.BOTH))
        assertEquals("0xffffffffffffffff", render("uint64", "18446744073709551615 = 0xffffffffffffffff", GoIntegerFormat.HEXADECIMAL))
    }

    @Test fun whatIsNoIntegerStaysAsItIs() {
        // a float of a round value is `1` in delve too: the type decides
        assertEquals("1", render("float64", "1", GoIntegerFormat.HEXADECIMAL))
        assertEquals("\"42\"", render("string", "\"42\"", GoIntegerFormat.HEXADECIMAL))
        assertEquals("main.Red (1)", render("main.Color", "main.Red (1)", GoIntegerFormat.HEXADECIMAL))
        assertNull(GoDataViews.integer(null, "42", GoIntegerFormat.HEXADECIMAL))
        assertEquals("{X: 1, Y: 2}", render("main.Point", "{X: 1, Y: 2}", GoIntegerFormat.HEXADECIMAL))
    }

    @Test fun pointerAddressesAreHiddenWhenAsked() {
        assertEquals("(*main.Node)(0xc000012345)", render("*main.Node", "(*main.Node)(0xc000012345)"))
        assertEquals("*main.Node", render("*main.Node", "(*main.Node)(0xc000012345)", pointers = false))
        assertEquals("*example.com/store.Order", render("*example.com/store.Order", "(\"*example.com/store.Order\")(0xc0000a4000)", pointers = false))
        assertEquals("{next: *main.Node, value: 3}", render("main.Node", "{next: (*main.Node)(0xc000012345), value: 3}", pointers = false))
        assertEquals("error(*main.MyErr) …", render("error", "error(*main.MyErr) 0xc000012345", pointers = false))
        // a loaded pointer has no address in the text of delve; a string is never touched
        assertEquals("*{X: 1}", render("*main.Point", "*{X: 1}", pointers = false))
        assertEquals("\"(a)(0x1)\"", render("string", "\"(a)(0x1)\"", pointers = false))
        // the value of an unsafe.Pointer is its address
        assertEquals("unsafe.Pointer(0xc000012345)", render("unsafe.Pointer", "unsafe.Pointer(0xc000012345)", pointers = false))
    }

    @Test fun theStringViewAsksNamedTypesOnly() {
        assertTrue(GoDataViews.stringViewCandidate("main.Color"))
        assertTrue(GoDataViews.stringViewCandidate("*example.com/store.Order"))
        assertTrue(GoDataViews.stringViewCandidate("time.Duration"))
        assertFalse(GoDataViews.stringViewCandidate("time.Time"))
        assertFalse(GoDataViews.stringViewCandidate("int"))
        assertFalse(GoDataViews.stringViewCandidate("error"))
        assertFalse(GoDataViews.stringViewCandidate("[]main.Color"))
        assertFalse(GoDataViews.stringViewCandidate("map[string]main.Color"))
        assertFalse(GoDataViews.stringViewCandidate("chan main.Event"))
        assertFalse(GoDataViews.stringViewCandidate("func(main.T)"))
        assertFalse(GoDataViews.stringViewCandidate(null))
        assertEquals("call (p.items[0]).String()", GoDataViews.stringCall("p.items[0]"))
        assertTrue(GoDataViews.isNoStringMethod("p (type main.Point) has no member String"))
        assertFalse(GoDataViews.isNoStringMethod("call stopped"))
    }
}
