package io.github.golangsupport.benchmark

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.resolve.GoResolver

/**
 * Resolves every value reference, type reference and struct literal field key of
 * `net/http/server.go` and `go/types/expr.go`. Cold: caches of the two files discarded before
 * every iteration; warm: caches kept. Unit: ms per 1000 references.
 */
class GoResolveBenchmark : GoSemanticBenchmarkBase() {

    private class Refs(val names: List<GoReferenceExpression>, val types: List<GoTypeReferenceExpression>, val keys: List<GoKey>) {
        val count: Int get() = names.size + types.size + keys.size
    }

    private fun collect(files: List<GoFile>, resolver: GoResolver): Refs {
        val names = ArrayList<GoReferenceExpression>()
        val types = ArrayList<GoTypeReferenceExpression>()
        val keys = ArrayList<GoKey>()
        for (file in files) {
            names += PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java)
                .filter { it.referenceName.let { n -> n != null && n != "_" } && !(it.parent is GoKey && it.qualifier == null && resolver.isFieldKey(it.parent as GoKey)) }
            types += PsiTreeUtil.findChildrenOfType(file, GoTypeReferenceExpression::class.java)
            keys += PsiTreeUtil.findChildrenOfType(file, GoKey::class.java).filter { resolver.isFieldKey(it) }
        }
        return Refs(names, types, keys)
    }

    private fun resolveAll(resolver: GoResolver, refs: Refs): Int {
        var resolved = 0
        for (r in refs.names) if (resolver.resolveReferenceExpression(r).isNotEmpty()) resolved++
        for (r in refs.types) if (resolver.resolveTypeReference(r) != null) resolved++
        for (k in refs.keys) if (resolver.resolveFieldKey(k).isNotEmpty()) resolved++
        return resolved
    }

    fun testCold() = measure("GoResolveBenchmark.cold", cold = true)

    fun testWarm() = measure("GoResolveBenchmark.warm", cold = false)

    private fun measure(name: String, cold: Boolean) {
        val files = inputs.map(::gorootFile)
        val resolver = GoResolver.getInstance(project)
        val refs = collect(files, resolver)
        assertTrue("too few references: ${refs.count}", refs.count > 1000)
        var resolved = 0
        // Warm passes are a few milliseconds: repeat them to get a measurable, stable time.
        val passes = if (cold) 1 else WARM_PASSES
        BenchmarkSupport.run(name, "ms/1000refs", refs.count * passes / 1000.0, prepare = { if (cold) invalidate(files) }) {
            repeat(passes) { resolved = resolveAll(resolver, refs) }
        }
        // Sanity: the vast majority of the references resolve (the corpus gate owns the exact number).
        assertTrue("only $resolved of ${refs.count} references resolved", resolved > refs.count * 0.9)
    }

    private companion object {
        const val WARM_PASSES = 100
    }
}
