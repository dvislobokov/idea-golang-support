package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.catalogue.GoCatalogueScanner
import io.github.golangsupport.catalogue.GoProjectPackages
import io.github.golangsupport.catalogue.GoFileExports
import io.github.golangsupport.catalogue.GoModuleSymbols
import io.github.golangsupport.catalogue.GoPackageSymbols
import io.github.golangsupport.catalogue.GoSymbol
import io.github.golangsupport.catalogue.GoSymbolIndex
import io.github.golangsupport.lang.GoDeclarationKind

/** The packages of the project, as the stub indices of the platform give them. */
class GoProjectPackagesTest : BasePlatformTestCase() {
    private fun packages(): Map<String, List<String>> =
        GoProjectPackages.of(project).associate { pack -> pack.importPath to pack.symbols.map { "${it.kind.title} ${it.name}" } }

    fun testThePackagesOfAModule() {
        myFixture.addFileToProject("shop/go.mod", "module example.com/shop\n\ngo 1.22\n")
        myFixture.addFileToProject("shop/main.go", "package main\n\nfunc main() {}\n\nfunc Exported() {}\n")
        myFixture.addFileToProject("shop/store/order.go", "package store\n\ntype Order struct {\n\tID int\n}\n\nfunc NewOrder(currency string) *Order { return nil }\n\nfunc (o *Order) Total() int { return 0 }\n")
        myFixture.addFileToProject("shop/store/item.go", "package store\n\nconst MaxItems = 10\n\nfunc hidden() {}\n")
        myFixture.addFileToProject("shop/store/order_test.go", "package store\n\nfunc TestOrder(t *testing.T) {}\n\nfunc Helper() {}\n")
        myFixture.addFileToProject("shop/internal/money/money.go", "package money\n\nfunc Format(cents int) string { return \"\" }\n")
        myFixture.addFileToProject("shop/vendor/github.com/x/y/y.go", "package y\n\nfunc Vendored() {}\n")
        myFixture.addFileToProject("shop/store/testdata/sample.go", "package sample\n\nfunc Sample() {}\n")
        // no module above it: there is no path to import it by
        myFixture.addFileToProject("loose/loose.go", "package loose\n\nfunc Loose() {}\n")

        assertEquals(
            mapOf(
                "example.com/shop/internal/money" to listOf("func Format"),
                "example.com/shop/store" to listOf("const MaxItems", "func NewOrder", "struct Order"),
            ),
            packages(),
        )
    }

    fun testAChangedFileIsReadAgain() {
        myFixture.addFileToProject("shop/go.mod", "module example.com/shop\n")
        val file = myFixture.addFileToProject("shop/store/order.go", "package store\n\nfunc NewOrder() {}\n")
        assertEquals(mapOf("example.com/shop/store" to listOf("func NewOrder")), packages())
        val before = GoProjectPackages.stamp(project)

        myFixture.saveText(file.virtualFile, "package store\n\nfunc NewOrder() {}\n\nfunc Cancel(id int) error { return nil }\n")
        assertEquals(mapOf("example.com/shop/store" to listOf("func Cancel", "func NewOrder")), packages())
        assertTrue("the stamp tells that a declaration has changed", GoProjectPackages.stamp(project) != before)
    }

    fun testWhoMayImportAnInternalPackage() {
        assertTrue(GoCatalogueScanner.isVisible("example.com/shop/store", "example.com/other"))
        assertTrue(GoCatalogueScanner.isVisible("example.com/shop/internal/money", "example.com/shop"))
        assertTrue(GoCatalogueScanner.isVisible("example.com/shop/internal/money", "example.com/shop/cmd/api"))
        assertTrue(GoCatalogueScanner.isVisible("example.com/shop/internal/money/parse", "example.com/shop/store"))
        assertFalse(GoCatalogueScanner.isVisible("example.com/shop/internal/money", "example.com/other"))
        assertFalse(GoCatalogueScanner.isVisible("example.com/shop/internal/money", "example.com/shopping"))
        assertFalse(GoCatalogueScanner.isVisible("example.com/shop/store/internal/db", "example.com/shop/cmd"))
        assertFalse(GoCatalogueScanner.isVisible("example.com/shop/internal/money", null))
        // a word that has `internal` in it is not the directory
        assertTrue(GoCatalogueScanner.isVisible("example.com/shop/internals", "example.com/other"))
    }

    fun testTheProjectGoesBeforeTheStandardLibrary() {
        fun pack(path: String, name: String, vararg functions: String) = GoPackageSymbols(path, name, functions.map { GoSymbol(it, GoDeclarationKind.FUNCTION, "()") })
        val index = GoSymbolIndex(listOf(
            GoModuleSymbols("std", true, listOf(pack("fmt", "fmt", "Print", "Println"))),
            GoModuleSymbols("dep", false, listOf(pack("go.uber.org/zap", "zap", "Print"))),
            GoModuleSymbols("project", false, listOf(pack("example.com/shop/report", "report", "Print", "PrintAll"), pack("example.com/shop/internal/out", "out", "Print")), project = true),
        ))
        fun found(from: String?): List<String> = index.find("Print", 10) { it.importPath != from && GoCatalogueScanner.isVisible(it.importPath, from) }.map { "${it.pack.name}.${it.symbol.name}" }
        // all of the project, then all of the standard library, then the modules; a shorter path before a longer one
        assertEquals(listOf("report.Print", "out.Print", "report.PrintAll", "fmt.Print", "fmt.Println", "zap.Print"), found("example.com/shop/cmd"))
        // not the names of the package of the file, and not what is internal to another tree
        assertEquals(listOf("out.Print", "fmt.Print", "fmt.Println", "zap.Print"), found("example.com/shop/report"))
        assertEquals(listOf("report.Print", "report.PrintAll", "fmt.Print", "fmt.Println", "zap.Print"), found("example.com/other"))
        assertEquals("example.com/shop/report", index.packageOf("report", setOf("PrintAll")))
    }

    fun testAFileThatExportsNothingNamesItsPackage() {
        val files = listOf(GoFileExports("store", emptyList()), GoFileExports("store", emptyList()), GoFileExports("main", listOf(GoSymbol("Gen", GoDeclarationKind.FUNCTION, "()"))))
        // two files of `store` against one of a generator: the package is `store`, and it exports nothing
        assertNull(GoCatalogueScanner.merge("example.com/shop/store", files))
        assertEquals("store", GoCatalogueScanner.merge("example.com/shop/store", files + GoFileExports("store", listOf(GoSymbol("Order", GoDeclarationKind.STRUCT, null))))?.name)
    }

    fun testSignaturesComeFromTheStubsWithoutTheAst() {
        myFixture.addFileToProject("shop/go.mod", "module example.com/shop\n")
        val files = listOf(
            myFixture.addFileToProject("shop/store/order.go", """
                package store

                import "context"

                type Order struct{ ID int }

                type Table map[string][]*Order

                type Ch <-chan struct{}

                type Ref = Order

                const Max int = 3

                var Default, Spare *Order

                func NewOrder(ctx context.Context, ids ...int) (*Order, error) { return nil, nil }

                func Map[T, U any](xs []T, f func(T) U) []U { return nil }

                func Close() {}

                func (o *Order) Total() int { return 0 }
            """.trimIndent()),
            // exports nothing, still a file of the package
            myFixture.addFileToProject("shop/store/doc.go", "// Package store keeps orders.\npackage store\n"),
        )
        val packages = withoutAstLoading(project, testRootDisposable, files) { GoProjectPackages.of(project) }
        assertEquals(
            listOf(
                "type Ch <-chan struct{}", "func Close ()", "var Default *Order", "func Map [T, U any](xs []T, f func(T) U) []U", "const Max int",
                "func NewOrder (ctx context.Context, ids ...int) (*Order, error)", "struct Order struct{...}", "type Ref = Order", "var Spare *Order", "type Table map[string][]*Order",
            ),
            packages.single().symbols.map { "${it.kind.title} ${it.name} ${it.signature}" },
        )
    }
}
