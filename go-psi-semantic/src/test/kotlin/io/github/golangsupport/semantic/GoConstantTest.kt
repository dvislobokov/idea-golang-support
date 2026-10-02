package io.github.golangsupport.semantic

import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.project.GoProjectModelTestBase
import java.math.BigInteger

class GoConstantTest : GoProjectModelTestBase() {

    private fun constants(text: String): Map<String, GoConstant?> {
        val file = myFixture.configureByText("c.go", text) as GoFile
        val typer = GoExpressionTyper.getInstance(project)
        return file.consts.associate { it.name!! to typer.constantValueOf(it as GoConstDefinition) }
    }

    fun testIotaPatterns() {
        val c = constants("""
            package p
            const (
                A = iota
                B
                C
                _
                E
            )
            const (
                KB = 1 << (10 * (iota + 1))
                MB
                GB
            )
            const (
                X = iota * 10
                Y
                S = "s"
                Z = iota
            )
        """.trimIndent())
        assertEquals(GoConstant.Int(0), c["A"]); assertEquals(GoConstant.Int(1), c["B"]); assertEquals(GoConstant.Int(2), c["C"]); assertEquals(GoConstant.Int(4), c["E"])
        assertEquals(GoConstant.Int(1024), c["KB"]); assertEquals(GoConstant.Int(1 shl 20), c["MB"]); assertEquals(GoConstant.Int(1 shl 30), c["GB"])
        assertEquals(GoConstant.Int(0), c["X"]); assertEquals(GoConstant.Int(10), c["Y"]); assertEquals(GoConstant.Str("s"), c["S"]); assertEquals(GoConstant.Int(3), c["Z"])
    }

    /** Repetition and iota come from a per-group layout computed in one pass (opGen.go has thousands of specs). */
    fun testRepetitionLayout() {
        val c = constants("""
            package p
            const (
                A = iota * 10
                B
                T string = "t"
                U
                M, N = iota, iota + 1
                O, P
                Q int8 = 7
                R
            )
            const Single = iota
        """.trimIndent())
        assertEquals(GoConstant.Int(0), c["A"]); assertEquals(GoConstant.Int(10), c["B"])
        assertEquals(GoConstant.Str("t"), c["T"]); assertEquals(GoConstant.Str("t"), c["U"])
        assertEquals(GoConstant.Int(4), c["M"]); assertEquals(GoConstant.Int(5), c["N"])
        assertEquals(GoConstant.Int(5), c["O"]); assertEquals(GoConstant.Int(6), c["P"])
        assertEquals(GoConstant.Int(7), c["Q"]); assertEquals(GoConstant.Int(7), c["R"])
        assertEquals(GoConstant.Int(0), c["Single"])
    }

    fun testRepetitionInFunctionBodyAndAfterEdit() {
        val file = myFixture.configureByText("c.go", "package p\nfunc f() {\n\tconst (\n\t\tA = iota + 1\n\t\tB\n\t)\n\t_ = B\n}\n") as GoFile
        val typer = GoExpressionTyper.getInstance(project)
        fun b() = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, GoConstDefinition::class.java).first { it.name == "B" }
        assertEquals(GoConstant.Int(2), typer.constantValueOf(b()))
        val doc = myFixture.editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(doc.text.indexOf("\t\tB"), "\t\tX\n")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
        assertEquals(GoConstant.Int(3), typer.constantValueOf(b()))
    }

    fun testLargeGroup() {
        val n = 5000
        val text = buildString {
            append("package p\nconst (\n\tC0 = iota * 2\n")
            for (i in 1 until n) append("\tC").append(i).append('\n')
            append(")\n")
        }
        val t0 = System.currentTimeMillis()
        val c = constants(text)
        assertEquals(GoConstant.Int(2L * (n - 1)), c["C${n - 1}"])
        assertEquals(GoConstant.Int(2L * 1234), c["C1234"])
        // Quadratic sibling walks took seconds here; linear is well under one second.
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 5000)
    }

    fun testArithmeticAndLiterals() {
        val c = constants("""
            package p
            const A = 0x10 + 0b11 + 0o7 + 010 + 1_000
            const B = 7 / 2
            const F = 7.0 / 2
            const S = "a" + "b"
            const L = len("héllo")
            const Big = 1 << 100
            const Neg = -A
            const Cmp = A > 10 && S == "ab"
            const R = 'a' + 1
            const Shift = Big >> 99
            const Unused = undefinedName + 1
        """.trimIndent())
        assertEquals(GoConstant.Int(16 + 3 + 7 + 8 + 1000), c["A"])
        assertEquals(GoConstant.Int(3), c["B"])
        assertEquals(0, (c["F"] as GoConstant.Float).value.compareTo(java.math.BigDecimal("3.5")))
        assertEquals(GoConstant.Str("ab"), c["S"])
        assertEquals(GoConstant.Int(6), c["L"])
        assertEquals(GoConstant.Int(BigInteger.ONE.shiftLeft(100)), c["Big"])
        assertEquals(GoConstant.Int(-(16 + 3 + 7 + 8 + 1000)), c["Neg"])
        assertEquals(GoConstant.Bool(true), c["Cmp"])
        assertEquals(GoConstant.Int(98), c["R"])
        assertEquals(GoConstant.Int(2), c["Shift"])
        assertNull(c["Unused"])
    }

    fun testCrossReferenceAndTypedConstants() {
        val c = constants("""
            package p
            type Weekday int
            const (
                Sunday Weekday = iota
                Monday
            )
            const Days = Monday + 6
            const Ref = Days * 2
        """.trimIndent())
        assertEquals(GoConstant.Int(0), c["Sunday"]); assertEquals(GoConstant.Int(1), c["Monday"])
        assertEquals(GoConstant.Int(7), c["Days"]); assertEquals(GoConstant.Int(14), c["Ref"])
    }
}
