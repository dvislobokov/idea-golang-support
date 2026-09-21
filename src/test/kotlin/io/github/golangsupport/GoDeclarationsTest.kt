package io.github.golangsupport

import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoTypeNameMacro
import io.github.golangsupport.run.GoBreakpointLines
import io.github.golangsupport.run.GoHoverExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoDeclarationsTest {
    private val source = """
        // Package store keeps orders.
        package store

        import (
            "errors"
            str "strings"
        )

        var ErrEmpty = errors.New("empty")

        const (
            A = iota
            B
            c, d = 1, 2
        )

        type (
            Item struct {
                Name     string `json:"name"`
                x, y     int
                data     []byte
                *Embedded
                pkg.Other
                List[int]
            }

            Priced interface {
                Total() int
                fmt.Stringer
            }
        )

        type ID = string

        type Order[T any] struct {
            items []T
        }

        func NewOrder(currency string) *Order[int] {
            return &Order[int]{}
        }

        func (o *Order[T]) Total() (total int, err error) {
            for range o.items {
                total++
            }
            return
        }

        func external(x int) interface{}

        func returnsLiteral() map[string]struct{} {
            return nil
        }
    """.trimIndent()

    private val structure = GoDeclarations.scan(source)

    private fun names(kind: GoDeclarationKind) = structure.all().filter { it.kind == kind }.map { it.name }

    @Test fun packageAndImports() {
        assertEquals("store", structure.packageName)
        assertEquals(listOf("errors", "strings"), structure.imports.map { it.path })
        assertEquals("str", structure.imports[1].alias)
    }

    @Test fun valuesOfGroupsAndSingles() {
        assertEquals(listOf("ErrEmpty"), names(GoDeclarationKind.VAR))
        assertEquals(listOf("A", "B", "c", "d"), names(GoDeclarationKind.CONST))
    }

    @Test fun typesWithMembers() {
        assertEquals(listOf("Item", "Order"), names(GoDeclarationKind.STRUCT))
        assertEquals(listOf("Priced"), names(GoDeclarationKind.INTERFACE))
        assertEquals(listOf("ID"), names(GoDeclarationKind.TYPE))
        val item = structure.declarations.first { it.name == "Item" }
        assertEquals(listOf("Name", "x", "y", "data", "Embedded", "Other", "List"), item.children.map { it.name })
        assertEquals("string", item.children[0].signature)
        assertEquals("[]byte", item.children[3].signature)
        assertEquals(listOf("Total"), structure.declarations.first { it.name == "Priced" }.children.map { it.name })
    }

    @Test fun functionsAndMethods() {
        assertEquals(listOf("NewOrder", "external", "returnsLiteral"), names(GoDeclarationKind.FUNCTION))
        val total = structure.declarations.first { it.name == "Total" }
        assertEquals(GoDeclarationKind.METHOD, total.kind)
        assertEquals("Order", total.receiver)
        assertEquals("(Order) Total() (total int, err error)", total.presentation)
        assertNull(structure.declarations.first { it.name == "external" }.body)
        assertTrue(source.substring(structure.declarations.first { it.name == "returnsLiteral" }.body!!.startOffset).startsWith("{\n    return nil"))
    }

    @Test fun groupsFold() = assertEquals(3, structure.groups.size)

    @Test fun brokenCodeDoesNotThrow() {
        for (end in source.indices step 7) GoDeclarations.scan(source.substring(0, end))
        GoDeclarations.scan("func (")
        GoDeclarations.scan("type ( A struct {")
    }

    @Test fun receiverOfTheMethodTemplate() = assertEquals("*Order", GoTypeNameMacro.receiverFor(structure, source.length))

    @Test fun breakpointLines() {
        val lines = GoBreakpointLines.find(source).map { source.lines()[it].trim() }
        assertTrue("total++" in lines)
        assertTrue("return nil" in lines)
        assertTrue(lines.none { it.startsWith("Name ") || it.startsWith("import") || it == "B" })
    }

    @Test fun hoverExpression() {
        val text = "x := order.Currency + f(a).b + g()"
        fun at(marker: String, shift: Int = 0) = GoHoverExpression.rangeAt(text, text.indexOf(marker) + shift)?.substring(text)
        assertEquals("order.Currency", at("Currency"))
        assertEquals("order", at("order"))
        assertNull(at("f("))
        assertNull(at(".b", 1))
        assertNull(at("g("))
    }
}
