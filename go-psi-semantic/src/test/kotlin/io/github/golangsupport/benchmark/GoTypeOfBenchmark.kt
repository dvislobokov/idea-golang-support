package io.github.golangsupport.benchmark

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * `typeOf` of every expression of `net/http/server.go` and `go/types/expr.go`. Cold: caches of the
 * two files discarded before every iteration; warm: caches kept. Unit: ms per 1000 expressions.
 */
class GoTypeOfBenchmark : GoSemanticBenchmarkBase() {

    fun testCold() = measure("GoTypeOfBenchmark.cold", cold = true)

    fun testWarm() = measure("GoTypeOfBenchmark.warm", cold = false)

    private fun measure(name: String, cold: Boolean) {
        val files = inputs.map(::gorootFile)
        val semantic = GoSemanticService.getInstance(project)
        val expressions = files.flatMap { PsiTreeUtil.findChildrenOfType(it, GoExpression::class.java) }
        assertTrue("too few expressions: ${expressions.size}", expressions.size > 1000)
        var known = 0
        // Warm passes are a few milliseconds: repeat them to get a measurable, stable time.
        val passes = if (cold) 1 else WARM_PASSES
        BenchmarkSupport.run(name, "ms/1000exprs", expressions.size * passes / 1000.0, prepare = { if (cold) invalidate(files) }) {
            repeat(passes) {
                known = 0
                for (e in expressions) if (semantic.typeOf(e) !is GoUnknownType) known++
            }
        }
        assertTrue("only $known of ${expressions.size} expressions have a type", known > expressions.size * 0.5)
    }

    private companion object {
        const val WARM_PASSES = 100
    }
}
