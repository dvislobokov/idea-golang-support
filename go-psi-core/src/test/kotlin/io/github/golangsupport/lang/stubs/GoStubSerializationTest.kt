package io.github.golangsupport.lang.stubs

import com.intellij.psi.stubs.IStubElementType
import io.github.golangsupport.GoStubTestCase
import io.github.golangsupport.lang.psi.GoTypes

/** Registration contract of the stub element types and serialization of edge-case stub data. */
class GoStubSerializationTest : GoStubTestCase() {

    fun testStubElementTypesAndExternalIds() {
        val stubbed = GoTypes::class.java.declaredFields.mapNotNull { field ->
            val type = field.get(null) as? IStubElementType<*, *> ?: return@mapNotNull null
            // The stubElementTypeHolder registration derives the id from the field name.
            assertEquals("external id of ${field.name}", "go.${field.name}", type.externalId)
            field.name
        }.sorted()
        assertEquals(
            listOf(
                "ANONYMOUS_FIELD_DEFINITION", "ARRAY_OR_SLICE_TYPE", "CHANNEL_TYPE", "CONSTRAINT_ELEM", "CONSTRAINT_TERM",
                "CONST_DECLARATION", "CONST_DEFINITION", "CONST_SPEC", "FIELD_DECLARATION", "FIELD_DEFINITION",
                "FUNCTION_DECLARATION", "FUNCTION_TYPE", "IMPORT_SPEC", "INTERFACE_TYPE", "MAP_TYPE", "METHOD_DECLARATION",
                "METHOD_SPEC", "PACKAGE_CLAUSE", "PARAMETERS", "PARAMETER_DECLARATION", "PARAM_DEFINITION", "PAR_TYPE",
                "POINTER_TYPE", "RECEIVER", "RESULT", "SIGNATURE", "STRUCT_TYPE", "TAG", "TYPE", "TYPE_ARGUMENTS",
                "TYPE_LIST", "TYPE_PARAMETERS", "TYPE_PARAMETER_DECLARATION", "TYPE_PARAM_DEFINITION",
                "TYPE_REFERENCE_EXPRESSION", "TYPE_SPEC", "VAR_DECLARATION", "VAR_DEFINITION", "VAR_SPEC",
            ),
            stubbed,
        )
        assertEquals(4, GoFileElementType.STUB_VERSION)
        assertEquals("go.FILE", GoFileElementType.INSTANCE.externalId)
    }

    fun testRoundTripOfEdgeCaseData() {
        val text = """
            //go:build ignore

            package édition

            import (
            	. "math"
            	_ "embed"
            	"C"
            )

            type 型[T interface{ ~int | ~string }] [len("abc")]T

            type S struct {
            	a, b int `x:"1"`
            	*T
            	*pkg.U
            }

            const (
            	X, Y = iota, "multi" + "line"
            	Z
            )

            var v = struct{ a int }{}

            func (*S) m(int, ...string) (a, b int) { return }

            func (S) n(x chan<- int, y <-chan int, z chan int) {}
        """.trimIndent()
        val stub = buildFromPsi(createLightGoFile("edge.go", text))
        val restored = roundTrip(stub)
        assertEquals(dump(stub), dump(restored))
        val fileStub = restored as GoFileStub
        assertEquals("édition", fileStub.packageName)
        assertEquals("ignore", fileStub.buildConstraint.goBuild)
        assertTrue(fileStub.isCgo)
        val dumpText = dump(restored)
        listOf(
            "GoTypeSpecStub(name=型, alias=false, generic=true)",
            "ARRAY_OR_SLICE_TYPE:GoTypeStub(len(\"abc\"))",
            "GoConstSpecStub(iota=0, values=[iota, \"multi\" + \"line\"])",
            "GoConstSpecStub(iota=1, values=[])",
            "GoMethodDeclarationStub(name=m, arity=2, receiver=*S)",
            "GoReceiverStub(name=null)",
            "GoAnonymousFieldDefinitionStub(name=U, pointer=true)",
            "TYPE_REFERENCE_EXPRESSION:GoTypeReferenceExpressionStub(pkg.U)",
            "CHANNEL_TYPE:GoTypeStub(chan<-)",
            "CHANNEL_TYPE:GoTypeStub(<-chan)",
            "CHANNEL_TYPE:GoTypeStub(chan)",
            "GoParameterDeclarationStub(variadic=true)",
            "GoVarSpecStub(values=[struct{ a int }{}])",
        ).forEach { assertTrue("missing '$it' in\n$dumpText", dumpText.contains(it)) }
        // The struct type of the var initializer is an expression, not a stub.
        assertEquals(1, Regex("STRUCT_TYPE").findAll(dumpText).count())
    }
}
