package io.github.golangsupport

import io.github.golangsupport.catalogue.GoSourceScanner
import io.github.golangsupport.lang.GoDeclarationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner of the catalogue: the package-level declarations of files outside the indices, by the tokens of the lexer of go-psi. */
class GoSourceScannerTest {
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

    private val structure = GoSourceScanner.scan(source)

    private fun names(kind: GoDeclarationKind) = structure.declarations.flatMap { listOf(it) + it.children }.filter { it.kind == kind }.map { it.name }

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

    @Test fun brokenCodeDoesNotThrow() {
        for (end in source.indices step 7) GoSourceScanner.scan(source.substring(0, end))
        GoSourceScanner.scan("func (")
        GoSourceScanner.scan("type ( A struct {")
    }

    /** The catalogue runs the scanner on every file of GOROOT and the module cache: it must finish on anything, a file cut anywhere or not Go at all. */
    @Test(timeout = 10_000) fun scannerTerminatesOnBrokenInput() {
        for (end in source.indices) GoSourceScanner.scan(source.substring(0, end))
        for (start in source.indices) GoSourceScanner.scan(source.substring(start))
        val random = java.util.Random(7)
        val pieces = listOf("package", "import", "func", "type", "struct", "interface", "var", "const", "map", "chan", "(", ")", "[", "]", "{", "}",
            ".", ",", ";", "=", "\n", "\t", " ", "\"", "'", "`", "/", "*", "a1", "x", "_")
        repeat(200) { GoSourceScanner.scan((0 until 200).joinToString("") { pieces[random.nextInt(pieces.size)] }) }
    }
}
