package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G4, literals: Remove keys from struct literal, Move field assignment to struct initialization. */
class GoLiteralIntentionsTest : GoSemanticIdeTestBase() {

    private fun doTest(before: String, intention: String, after: String) {
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == intention } ?: error("$intention not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun assertNotOffered(text: String, intention: String) {
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertFalse("$intention in $offered", intention in offered)
    }

    // --- remove keys ---

    fun testRemoveKeysReordersAndFillsZeroValues() = doTest(
        """
        package p

        type T struct {
        	A int
        	B string
        	C []int
        }

        var v = T{B: "a", <caret>A: 2}
        """,
        "Remove keys from struct literal",
        """
        package p

        type T struct {
        	A int
        	B string
        	C []int
        }

        var v = T{2, "a", nil}
        """,
    )

    fun testRemoveKeysKeepsAMultilineLiteralMultiline() = doTest(
        """
        package p

        type T struct{ A, B int }

        func f() T {
        	return T{
        		A: 1,
        		B: 2,<caret>
        	}
        }
        """,
        "Remove keys from struct literal",
        """
        package p

        type T struct{ A, B int }

        func f() T {
        	return T{
        		1,
        		2,
        	}
        }
        """,
    )

    fun testNoRemoveKeysForAPositionalOrEmptyLiteral() {
        assertNotOffered("package p\n\ntype T struct{ A, B int }\n\nvar v = T{1, <caret>2}\n", "Remove keys from struct literal")
        assertNotOffered("package p\n\ntype T struct{ A, B int }\n\nvar v = T{<caret>}\n", "Remove keys from struct literal")
    }

    fun testNoRemoveKeysForUnexportedFieldsOfAnotherPackage() = assertNotOffered(
        """
        package p

        import "time"

        var t = time.Timer{<caret>C: nil}
        """,
        "Remove keys from struct literal",
    )

    // --- move field assignment ---

    fun testMoveFieldAssignments() = doTest(
        """
        package p

        type S struct{ Host string; Port, Mode int }

        func f() S {
        	s := S{Host: "h"}
        	s.Port = 80
        	s.<caret>Mode = 1
        	return s
        }
        """,
        "Move field assignment to struct initialization",
        """
        package p

        type S struct{ Host string; Port, Mode int }

        func f() S {
        	s := S{Host: "h", Port: 80, Mode: 1}
        	return s
        }
        """,
    )

    fun testMoveFieldAssignmentIntoAPointerLiteralOfAVar() = doTest(
        """
        package p

        type S struct{ foo string }

        func f() *S {
        	var s = &S{}
        	s.foo = `bar`<caret>
        	return s
        }
        """,
        "Move field assignment to struct initialization",
        """
        package p

        type S struct{ foo string }

        func f() *S {
        	var s = &S{foo: `bar`}
        	return s
        }
        """,
    )

    fun testNoMoveWhenTheValueReadsTheVariableOrTheFieldIsKeyed() {
        assertNotOffered(
            "package p\n\ntype S struct{ A, B int }\n\nfunc f() {\n\ts := S{A: 1}\n\ts.<caret>B = s.A\n\t_ = s\n}\n",
            "Move field assignment to struct initialization",
        )
        assertNotOffered(
            "package p\n\ntype S struct{ A, B int }\n\nfunc f() {\n\ts := S{A: 1}\n\ts.<caret>A = 2\n\t_ = s\n}\n",
            "Move field assignment to struct initialization",
        )
        assertNotOffered(
            "package p\n\ntype S struct{ A, B int }\n\nfunc f() {\n\ts := S{A: 1}\n\tprintln()\n\ts.<caret>B = 2\n\t_ = s\n}\n",
            "Move field assignment to struct initialization",
        )
    }
}
