package io.github.golangsupport.ide.intentions

import com.intellij.refactoring.util.CommonRefactoringUtil

/** Remove method from interface and all its implementations: the spec goes, and so do the chosen methods nothing else calls. */
class GoRemoveInterfaceMethodIntentionTest : GoIntentionTestSupport() {
    private val text = GoRemoveInterfaceMethodIntention.TEXT

    override fun tearDown() {
        try {
            GoRemoveInterfaceMethodIntention.chooser = null
        } finally {
            super.tearDown()
        }
    }

    fun testRemovesTheSpecAndTheImplementations() = doTest(
        """
        package shapes

        type Shape interface {
        	Area() float64
        	// Name names the shape.
        	<caret>Name() string
        }

        type Circle struct{ R float64 }

        func (c Circle) Area() float64 { return 3.14 * c.R * c.R }

        // Name of the circle.
        func (c Circle) Name() string { return "circle" }

        type Square struct{ A float64 }

        func (s *Square) Area() float64 { return s.A * s.A }

        func (s *Square) Name() string { return "square" }
        """,
        text,
        """
        package shapes

        type Shape interface {
        	Area() float64
        }

        type Circle struct{ R float64 }

        func (c Circle) Area() float64 { return 3.14 * c.R * c.R }

        type Square struct{ A float64 }

        func (s *Square) Area() float64 { return s.A * s.A }
        """,
    )

    fun testAMethodCalledElsewhereIsKept() = doTest(
        """
        package shapes

        type Shape interface {
        	Area() float64
        	<caret>Name() string
        }

        type Circle struct{}

        func (c Circle) Area() float64 { return 1 }

        func (c Circle) Name() string { return "circle" }

        func describe(c Circle) string { return c.Name() }
        """,
        text,
        """
        package shapes

        type Shape interface {
        	Area() float64
        }

        type Circle struct{}

        func (c Circle) Area() float64 { return 1 }

        func (c Circle) Name() string { return "circle" }

        func describe(c Circle) string { return c.Name() }
        """,
    )

    fun testOnlyTheChosenMethodsGo() {
        GoRemoveInterfaceMethodIntention.chooser = { candidates -> candidates.filter { it.type == "B" } }
        doTest(
            """
            package p

            type I interface {
            	M()
            	<caret>N()
            }

            type A struct{}

            func (A) M() {}

            func (A) N() {}

            type B struct{}

            func (B) M() {}

            func (B) N() {}
            """,
            text,
            """
            package p

            type I interface {
            	M()
            }

            type A struct{}

            func (A) M() {}

            func (A) N() {}

            type B struct{}

            func (B) M() {}
            """,
        )
    }

    fun testNotOnAMethodOfAStruct() = assertNotOffered(
        """
        package p

        type T struct{}

        func (T) <caret>M() {}
        """,
        text,
    )

    fun testRefusedWhenCalledThroughTheInterface() {
        val before = """
            package shapes

            type Shape interface {
            	Area() float64
            	<caret>Name() string
            }

            type Circle struct{}

            func (c Circle) Area() float64 { return 1 }

            func (c Circle) Name() string { return "circle" }

            func label(s Shape) string { return s.Name() }
            """.trimIndent() + "\n"
        myFixture.configureByText("a.go", before)
        val action = myFixture.availableIntentions.single { it.text == text }
        try {
            myFixture.launchAction(action)
            fail("Expected the removal to be refused")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message, e.message!!.contains("called through the interface at a.go:14"))
        }
        myFixture.checkResult(before)
    }

    fun testMethodsOfOtherInterfacesAreNotPreChecked() = doTest(
        """
        package shapes

        type Shape interface {
        	<caret>Name() string
        	String() string
        	Area() float64
        }

        type Namer interface{ Name() string }

        type Circle struct{}

        func (c Circle) Name() string { return "circle" }

        func (c Circle) String() string { return "circle" }

        func (c Circle) Area() float64 { return 1 }
        """,
        text,
        """
        package shapes

        type Shape interface {
        	String() string
        	Area() float64
        }

        type Namer interface{ Name() string }

        type Circle struct{}

        func (c Circle) Name() string { return "circle" }

        func (c Circle) String() string { return "circle" }

        func (c Circle) Area() float64 { return 1 }
        """,
    )

    fun testWellKnownMethodNamesAreNotPreChecked() = doTest(
        """
        package shapes

        type Shape interface {
        	Area() float64
        	<caret>String() string
        }

        type Circle struct{}

        func (c Circle) Area() float64 { return 1 }

        func (c Circle) String() string { return "circle" }
        """,
        text,
        """
        package shapes

        type Shape interface {
        	Area() float64
        }

        type Circle struct{}

        func (c Circle) Area() float64 { return 1 }

        func (c Circle) String() string { return "circle" }
        """,
    )

    fun testNotOnAnInterfaceLiteral() = assertNotOffered(
        """
        package p

        func f(x interface{ <caret>M() }) {}
        """,
        text,
    )
}
