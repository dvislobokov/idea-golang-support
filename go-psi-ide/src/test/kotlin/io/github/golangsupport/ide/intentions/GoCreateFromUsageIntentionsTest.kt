package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Create function / method / field / variable / type from an undefined name (docs/FEATURES.md §11, wave 3 B1). */
class GoCreateFromUsageIntentionsTest : GoSemanticIdeTestBase() {

    private fun doTest(before: String, intention: String, after: String) {
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == intention } ?: error("$intention not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun assertNotOffered(text: String, prefix: String) {
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertTrue("$prefix in $offered", offered.none { it.startsWith(prefix) })
    }

    // --- function ---

    fun testCreateFunctionWithArgumentTypesAndAssignmentArity() = doTest(
        """
        package p

        type User struct{ Name string }

        func run(u *User, ids []int) error {
        	n, err := <caret>load(u, ids, "x", 1.5, nil, u.Name)
        	_ = n
        	return err
        }
        """,
        "Create function 'load'",
        """
        package p

        type User struct{ Name string }

        func run(u *User, ids []int) error {
        	n, err := load(u, ids, "x", 1.5, nil, u.Name)
        	_ = n
        	return err
        }

        func load(u *User, ids []int, s string, f float64, v any, name string) (any, error) {
        	panic("not implemented")
        }
        """,
    )

    fun testCreateFunctionResultsFromExpectedTypeAndNumberedNames() = doTest(
        """
        package p

        import "time"

        func f(a, b int) time.Duration {
        	return <caret>delay(a+1, b*2)
        }
        """,
        "Create function 'delay'",
        """
        package p

        import "time"

        func f(a, b int) time.Duration {
        	return delay(a+1, b*2)
        }

        func delay(i1 int, i2 int) time.Duration {
        	panic("not implemented")
        }
        """,
    )

    fun testCreateFunctionInStatementHasNoResultsAndAddsImport() = doTest(
        """
        package p

        import "bytes"

        func f(buf *bytes.Buffer) {
        	go <caret>flush(buf)
        }
        """,
        "Create function 'flush'",
        """
        package p

        import "bytes"

        func f(buf *bytes.Buffer) {
        	go flush(buf)
        }

        func flush(buf *bytes.Buffer) {
        	panic("not implemented")
        }
        """,
    )

    fun testCreateFunctionImportsThePackageOfAnArgumentType() {
        myFixture.addFileToProject("b.go", "package p\n\nimport \"net/http\"\n\nfunc client() *http.Client { return nil }\n")
        doTest(
            """
            package p

            func f() {
            	<caret>use(client())
            }
            """,
            "Create function 'use'",
            """
            package p

            import "net/http"

            func f() {
            	use(client())
            }

            func use(client *http.Client) {
            	panic("not implemented")
            }
            """,
        )
    }

    fun testNoCreateFunctionWhenTheNameResolves() = assertNotOffered(
        """
        package p

        func load() {}

        func f() {
        	<caret>load()
        }
        """,
        "Create function",
    )

    fun testNoCreateFunctionForGenericArguments() = assertNotOffered(
        """
        package p

        func f[T any](v T) {
        	<caret>use(v)
        }
        """,
        "Create function",
    )

    fun testNoCreateFunctionInAPackageOfTheStandardLibrary() = assertNotOffered(
        """
        package p

        import "strings"

        func f() {
        	strings.<caret>NoSuchFunction()
        }
        """,
        "Create function",
    )

    fun testCreateFunctionInAnotherPackageOfTheModule() {
        val tmp = com.intellij.openapi.util.io.FileUtil.createTempDirectory("gopsi-create", null, true)
        java.io.File(tmp, "go.mod").writeText("module example.com/app\n\ngo 1.22\n")
        java.io.File(tmp, "store").mkdirs()
        java.io.File(tmp, "store/store.go").writeText("package store\n\ntype Item struct{}\n")
        val main = "package main\n\nimport \"example.com/app/store\"\n\nfunc main() {\n\tstore.Load(1, \"k\")\n}\n"
        java.io.File(tmp, "main.go").writeText(main)
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        myFixture.configureFromExistingVirtualFile(dir.findChild("main.go")!!)
        myFixture.editor.caretModel.moveToOffset(main.indexOf("Load") + 1)
        // a file outside the light project's content: the intention is asked directly (the daemon does not collect intentions there)
        val action = GoCreateFunctionFromUsageIntention()
        assertTrue(action.isAvailable(project, myFixture.editor, myFixture.file))
        assertEquals("Create function 'Load'", action.text)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { action.invoke(project, myFixture.editor, myFixture.file) }
        val store = dir.findFileByRelativePath("store/store.go")!!
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(store)!!.text
        assertEquals("package store\n\ntype Item struct{}\n\nfunc Load(i int, s string) {\n\tpanic(\"not implemented\")\n}\n", text)
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
    }

    // --- method ---

    fun testCreateMethodOnPointerReceiverTypeInAnotherFile() {
        myFixture.addFileToProject(
            "store.go",
            "package p\n\ntype Store struct{ n int }\n\nfunc (st *Store) Len() int { return st.n }\n\ntype Other struct{}\n",
        )
        myFixture.configureByText(
            "a.go",
            "package p\n\nfunc f(s Store) bool {\n\treturn s.<caret>Has(\"k\")\n}\n",
        )
        val action = myFixture.availableIntentions.singleOrNull { it.text == "Create method 'Has' on Store" }
            ?: error(myFixture.availableIntentions.map { it.text }.toString())
        myFixture.launchAction(action)
        myFixture.checkResult("package p\n\nfunc f(s Store) bool {\n\treturn s.Has(\"k\")\n}\n")
        val store = myFixture.findFileInTempDir("store.go")
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(store)!!.text
        assertEquals(
            "package p\n\ntype Store struct{ n int }\n\nfunc (st *Store) Len() int { return st.n }\n\n" +
                "func (st *Store) Has(s string) bool {\n\tpanic(\"not implemented\")\n}\n\ntype Other struct{}\n",
            text,
        )
    }

    fun testCreateMethodWithValueReceiverAfterTheType() = doTest(
        """
        package p

        type Celsius float64

        func (c Celsius) String() string { return "" }

        func f(c Celsius) {
        	c.<caret>Print(true)
        }
        """,
        "Create method 'Print' on Celsius",
        """
        package p

        type Celsius float64

        func (c Celsius) String() string { return "" }

        func (c Celsius) Print(b bool) {
        	panic("not implemented")
        }

        func f(c Celsius) {
        	c.Print(true)
        }
        """,
    )

    fun testCreateMethodOnStructWithoutMethodsIsPointer() = doTest(
        """
        package p

        type Point struct{ X int }

        func f(p *Point) int {
        	return p.<caret>Dist(p)
        }
        """,
        "Create method 'Dist' on Point",
        """
        package p

        type Point struct{ X int }

        func (p *Point) Dist(p1 *Point) int {
        	panic("not implemented")
        }

        func f(p *Point) int {
        	return p.Dist(p)
        }
        """,
    )

    fun testNoCreateMethodOnATypeOfTheStandardLibrary() = assertNotOffered(
        """
        package p

        import "strings"

        func f(b *strings.Builder) {
        	b.<caret>Flush()
        }
        """,
        "Create method",
    )

    fun testNoCreateMethodWhenItExists() = assertNotOffered(
        """
        package p

        type T struct{}

        func (T) M() {}

        func f(t T) {
        	t.<caret>M()
        }
        """,
        "Create method",
    )

    // --- field ---

    fun testCreateFieldFromAssignment() = doTest(
        """
        package p

        import "time"

        type User struct {
        	Name string
        }

        func f(u *User) {
        	u.<caret>Born = time.Now()
        }
        """,
        "Create field 'Born' in User",
        """
        package p

        import "time"

        type User struct {
        	Name string
        	Born time.Time
        }

        func f(u *User) {
        	u.Born = time.Now()
        }
        """,
    )

    fun testCreateFieldInEmptyStructFromExpectedType() = doTest(
        """
        package p

        type Config struct{}

        func f(c Config) int {
        	return c.<caret>Port
        }
        """,
        "Create field 'Port' in Config",
        """
        package p

        type Config struct {
        	Port int
        }

        func f(c Config) int {
        	return c.Port
        }
        """,
    )

    // seen live: the field went after `Profile Profile` on the line of the brace
    fun testCreateFieldInOneLineStructSpreadsIt() = doTest(
        """
        package p

        type Profile struct{ Email string }
        type User struct{ Profile Profile; Name string }

        func f(u *User) {
        	u.<caret>Age = 3
        }
        """,
        "Create field 'Age' in User",
        """
        package p

        type Profile struct{ Email string }
        type User struct {
        	Profile Profile
        	Name string
        	Age int
        }

        func f(u *User) {
        	u.Age = 3
        }
        """,
    )

    // --- variable ---

    fun testCreateLocalVariableFromExpectedType() = doTest(
        """
        package p

        func f() string {
        	if true {
        		return <caret>name
        	}
        	return ""
        }
        """,
        "Create variable 'name'",
        """
        package p

        func f() string {
        	if true {
        		name := ""
        		return name
        	}
        	return ""
        }
        """,
    )

    fun testCreateLocalVariableWithVarWhenTheZeroLiteralHasAnotherType() = doTest(
        """
        package p

        func f(limit int64) bool {
        	return limit > <caret>ceiling
        }
        """,
        "Create variable 'ceiling'",
        """
        package p

        func f(limit int64) bool {
        	var ceiling int64
        	return limit > ceiling
        }
        """,
    )

    fun testCreatePackageVariable() = doTest(
        """
        package p

        var timeout = 2 * <caret>unit
        """,
        "Create variable 'unit'",
        """
        package p

        var timeout = 2 * unit

        var unit int
        """,
    )

    fun testNoCreateVariableForAQualifier() = assertNotOffered(
        """
        package p

        func f() {
        	<caret>strings.ToUpper("x")
        }
        """,
        "Create variable",
    )

    // seen live: `time` of `time.Time` without the import was offered as a variable
    fun testNoCreateVariableForTheQualifierOfAType() = assertNotOffered(
        """
        package p

        type Span struct{ At <caret>time.Time }

        func f(t time.Duration) {}
        """,
        "Create variable",
    )

    // --- type ---

    fun testCreateStructType() = doTest(
        """
        package p

        func f(o *<caret>Options) {}
        """,
        "Create type 'Options'",
        """
        package p

        func f(o *Options) {}

        type Options struct{}
        """,
    )

    fun testCreateInterfaceTypeForAConstraint() = doTest(
        """
        package p

        func Sum[T <caret>Number](xs []T) {}
        """,
        "Create type 'Number'",
        """
        package p

        func Sum[T Number](xs []T) {}

        type Number interface{}
        """,
    )

    fun testNoCreateTypeWhenItResolves() = assertNotOffered(
        """
        package p

        type Options struct{}

        func f(o *<caret>Options) {}
        """,
        "Create type",
    )
}
