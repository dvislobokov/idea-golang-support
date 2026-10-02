package io.github.golangsupport.ide.hints

import com.intellij.codeInsight.hints.InlayDumpUtil
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayProviderPassInfo
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPass
import com.intellij.codeInsight.hints.declarative.impl.util.DeclarativeHintsDumpUtil
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.codeInsight.multiverse.codeInsightContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoField
import io.github.golangsupport.semantic.types.GoStructType

/**
 * The inlay hints over the PSI, through the declarative hints pass of the platform (the dump marks a hint `/*<# text #>*/`, the way
 * `DeclarativeInlayHintsProviderTestCase` of the platform does; that base cannot be used here, it is not a [GoSemanticIdeTestBase]):
 * every hint kind, the skip rules of parameter names, the options of the type hints, the gate, the struct size on linux/amd64.
 */
class GoInlayHintsTest : GoSemanticIdeTestBase() {

    private fun doTest(expected: String, provider: InlayHintsProvider, options: Map<String, Boolean> = emptyMap()) {
        val expectedText = expected.trimIndent()
        val source = InlayDumpUtil.removeInlays(expectedText)
        myFixture.configureByText("hints.go", source)
        assertEquals(expectedText.trim(), dump(source, provider, options).trim())
    }

    private fun dump(source: String, provider: InlayHintsProvider, options: Map<String, Boolean>): String {
        val pass = ActionUtil.underModalProgress(project, "") {
            DeclarativeInlayHintsPass(myFixture.file, myFixture.editor, listOf(InlayProviderPassInfo(provider, "provider.id", options)), isPreview = false)
        }
        pass.setContext(myFixture.file.codeInsightContext)
        ActionUtil.underModalProgress(project, "") { pass.doCollectInformation(EmptyProgressIndicator()) }
        pass.applyInformationToEditor()
        return DeclarativeHintsDumpUtil.dumpHints(source, editor = myFixture.editor, renderer = { list ->
            list.getEntries().joinToString("") { (it as TextInlayPresentationEntry).text }
        })
    }

    fun testParameterNames() = doTest("""
        package p

        func greet(name string, times int, loud bool) {}
        func SetName(name string)                     {}
        func lower(s string) string                   { return s }
        func wait(seconds int)                        {}
        func log(format string, args ...any)          {}
        func pair() (string, int)                     { return "", 0 }
        func two(a string, b int)                     {}

        type user struct{ Name string }

        func use(u user, userName string, times int) {
        	greet(/*<# name: #>*/"a", /*<# times: #>*/3, /*<# loud: #>*/true)
        	greet(u.Name, times, /*<# loud: #>*/false)
        	greet(userName, /*<# times: #>*/1+2, /*<# loud: #>*/len("x") > 0)
        	SetName("x")
        	_ = lower("X")
        	wait(/*<# seconds: #>*/5)
        	log(/*<# format: #>*/"%d %d", /*<# args...: #>*/1, 2)
        	two(pair())
        	println("builtin")
        }
    """, GoParameterNameHintsProvider())

    fun testParameterLabelRules() {
        assertEquals("name:", GoInlayHints.parameterLabel("name", "\"x\"", "greet", 3, variadic = false))
        assertNull(GoInlayHints.parameterLabel("name", "name", "greet", 3, variadic = false))
        assertNull(GoInlayHints.parameterLabel("name", "u.Name", "greet", 3, variadic = false))
        assertNull(GoInlayHints.parameterLabel("name", "userName", "greet", 3, variadic = false))
        assertNull(GoInlayHints.parameterLabel("ctx", "reqCtx", "do", 2, variadic = false))
        assertEquals("id:", GoInlayHints.parameterLabel("id", "valid", "find", 2, variadic = false))
        assertNull(GoInlayHints.parameterLabel("name", "\"x\"", "SetName", 1, variadic = false))
        assertNull(GoInlayHints.parameterLabel("s", "\"X\"", "ToLower", 1, variadic = false))
        assertNull(GoInlayHints.parameterLabel("_", "1", "f", 2, variadic = false))
        assertNull(GoInlayHints.parameterLabel(null, "1", "f", 2, variadic = false))
        assertEquals("args...:", GoInlayHints.parameterLabel("args", "1", "log", 2, variadic = true))
    }

    fun testLiteralFieldNames() = doTest("""
        package p

        type Point struct{ X, Y int }

        type Line struct {
        	From, To Point
        }

        var a = Point{/*<# X: #>*/1, /*<# Y: #>*/2}
        var b = Point{X: 1, Y: 2}
        var c = &Point{/*<# X: #>*/3, /*<# Y: #>*/4}
        var d = []Point{{/*<# X: #>*/5, /*<# Y: #>*/6}}
        var e = Line{/*<# From: #>*/a, /*<# To: #>*/Point{}}
        var f = []int{1, 2}
    """, GoLiteralFieldHintsProvider())

    private val allTypes = mapOf(GoTypeHintsProvider.ASSIGN to true, GoTypeHintsProvider.RANGE to true, GoTypeHintsProvider.LITERAL to true, GoTypeHintsProvider.INSTANTIATION to true)

    fun testVariableTypes() {
        doTest("""
            package p

            import (
            	"bytes"
            	str "strings"
            )

            func pair() (int, error) { return 0, nil }

            func use(names []string, m map[string]*bytes.Buffer) {
            	n/*<# : int #>*/ := len(names)
            	v/*<# : int #>*/, err/*<# : error #>*/ := pair()
            	b/*<# : *bytes.Buffer #>*/, ok/*<# : bool #>*/ := m["x"]
            	r/*<# : *str.Reader #>*/ := str.NewReader("x")
            	var any interface{} = 1
            	x/*<# : any #>*/ := any
            	f/*<# : float64 #>*/ := 1.5
            	_ = 2
            	for i/*<# : int #>*/, s/*<# : string #>*/ := range names {
            		_, _ = i, s
            	}
            	for k/*<# : string #>*/ := range m {
            		_ = k
            	}
            	var j int
            	for j = range names {
            	}
            	_, _, _, _, _, _, _, _ = n, v, err, b, ok, r, x, f
            	_ = j
            }
        """, GoTypeHintsProvider(), allTypes)
    }

    fun testTypeOptionsAreSeparate() = doTest("""
        package p

        func Max[T int | float64](a, b T) T { return a }

        type Point struct{ X, Y int }

        func use(names []string) {
        	n := Max(1, 2)
        	for i/*<# : int #>*/ := range names {
        		_ = i
        	}
        	_ = []Point{{1, 2}}
        	_ = n
        }
    """, GoTypeHintsProvider(), mapOf(GoTypeHintsProvider.ASSIGN to false, GoTypeHintsProvider.RANGE to true, GoTypeHintsProvider.LITERAL to false, GoTypeHintsProvider.INSTANTIATION to false))

    fun testElidedLiteralTypesAndTypeArguments() = doTest("""
        package p

        type Point struct{ X, Y int }

        func Max[T int | float64](a, b T) T { return a }

        func Map[S ~[]E, E any, R any](s S, f func(E) R) []R { return nil }

        func use() {
        	_ = []Point{/*<# Point #>*/{1, 2}, /*<# Point #>*/{3, 4}}
        	_ = []*Point{/*<# &Point #>*/{1, 2}}
        	_ = map[Point]string{/*<# Point #>*/{1, 2}: "a"}
        	_ = [][]int{/*<# []int #>*/{1}}
        	_ = Max/*<# [int] #>*/(1, 2)
        	_ = Max[float64](1, 2)
        	_ = Map/*<# [[]string, string, int] #>*/([]string{"a"}, func(s string) int { return len(s) })
        }
    """, GoTypeHintsProvider(), mapOf(GoTypeHintsProvider.ASSIGN to false, GoTypeHintsProvider.RANGE to false, GoTypeHintsProvider.LITERAL to true, GoTypeHintsProvider.INSTANTIATION to true))

    fun testConstantValues() = doTest("""
        package p

        type Kind int

        const (
        	KindA Kind = iota/*<# = 0 #>*/
        	KindB/*<# = 1 #>*/
        	KindC/*<# = 2 #>*/
        )

        const (
        	KB = 1 << (10 * (iota + 1))/*<# = 1024 #>*/
        	MB/*<# = 1048576 #>*/
        )

        const Plain = 42
        const Text = "text"
        const Flag = true
        const Sum = Plain + 1/*<# = 43 #>*/
        const X, Y = 1, 2
        const Long = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + "b"/*<# = "aaaaaaaaaaaaaaaaaaaaaaaaaa… #>*/
    """, GoConstantValueHintsProvider())

    fun testStructSize() = doTest("""
        package p

        type Padded struct {/*<# 24 bytes, 11 padding (16 if reordered) #>*/
        	A bool
        	B int64
        	C int32
        }

        type Packed struct {/*<# 16 bytes, 3 padding #>*/
        	B int64
        	C int32
        	A bool
        }

        type Pair struct {/*<# 16 bytes, 6 padding #>*/
        	X, Y int8
        	Z int64
        }

        type Empty struct{}

        type Generic[T any] struct {
        	V T
        }

        type Strings struct{ S string; B []byte }/*<# 40 bytes #>*/
    """, GoStructSizeHintsProvider())

    fun testStructLayoutOfTypes() {
        fun field(name: String, type: io.github.golangsupport.semantic.types.GoType) = GoField(name, type, false, null, null, null)
        val padded = GoStructType(listOf(field("A", GoBasicType.BOOL), field("B", GoBasicType.INT64), field("C", GoBasicType.INT32)))
        assertEquals(GoInlayHints.StructLayout(24, 11, 16), GoInlayHints.structLayout(padded))
        assertEquals("24 bytes, 11 padding (16 if reordered)", GoInlayHints.structLayout(padded)!!.text)
        assertNull(GoInlayHints.structLayout(GoStructType(emptyList())))
    }

    fun testStructSizeNotOn32Bit() {
        val arm = toolchain.copy(goarch = "386")
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, object : GoToolchainProvider {
            override fun toolchainFor(project: Project?): GoToolchainInfo = arm
        }, testRootDisposable)
        doTest("""
            package p

            type Padded struct {
            	A bool
            	B int64
            }
        """, GoStructSizeHintsProvider())
    }

    fun testClosedGateLeavesTheGoplsSetAndKeepsTheStructSize() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.INLAY_HINTS
        }, testRootDisposable)
        val text = """
            package p

            type Kind int

            const (
            	A Kind = iota
            	B
            )

            func f(name string) {}

            func use(names []string) {
            	n := len(names)
            	f("x")
            	_ = n
            }
        """
        doTest(text, GoParameterNameHintsProvider())
        doTest(text, GoTypeHintsProvider(), allTypes)
        doTest(text, GoConstantValueHintsProvider())
        doTest("""
            package p

            type Padded struct {/*<# 16 bytes, 7 padding #>*/
            	A bool
            	B int64
            }
        """, GoStructSizeHintsProvider())
    }

    /** Types of variables come from the stubs of other files: the file declaring the called function keeps its AST unloaded. */
    fun testHintsDoNotLoadOtherFiles() {
        val other = myFixture.addFileToProject("other.go", """
            package p

            type Conn struct{ id int }

            func Dial(address string) (*Conn, error) { return nil, nil }
        """.trimIndent()) as PsiFileImpl
        ApplicationManager.getApplication().runWriteAction { other.onContentReload() }
        assertNull(other.treeElement)
        doTest("""
            package p

            func use() {
            	c/*<# : *Conn #>*/, err/*<# : error #>*/ := Dial("localhost")
            	_, _ = c, err
            }
        """, GoTypeHintsProvider(), allTypes)
        assertNull(other.treeElement)
        doTest("""
            package p

            func use() {
            	c, err := Dial(/*<# address: #>*/"localhost")
            	_, _ = c, err
            }
        """, GoParameterNameHintsProvider())
        assertNull(other.treeElement)
    }
}
