package io.github.golangsupport.benchmark

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.ParameterizedCachedValue
import com.intellij.util.keyFMap.KeyFMap
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.api.GoSemanticService
import java.nio.file.Files

/**
 * Memory probe (report only, no threshold): approximate heap retained by the go-psi caches after
 * `check` of every non-test file of `$GOROOT/src/net/http` (through the VFS), measured as used heap
 * after forced GCs (minimum of several samples) with the ASTs of the files already loaded and held,
 * so only the caches differ. Also counts the `CachedValue`s stored on the PSI of `server.go` (read
 * through the user data maps), i.e. the per-element cache entries of package-level code, and the
 * per-function body stores (`GoBodyCache`) with their value count.
 */
class GoCacheMemoryBenchmark : GoSemanticBenchmarkBase() {

    fun testNetHttpCaches() {
        val dir = BenchmarkSupport.goroot().resolve("src/net/http")
        val files: List<GoFile> = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().let { n -> n.endsWith(".go") && !n.endsWith("_test.go") } }.sorted().toList()
        }.map { gorootFile("net/http/${it.fileName}") }
        val semantic = GoSemanticService.getInstance(project)
        // Load and hold the ASTs (and the package model, universe, stub indices) before the baseline.
        val asts: List<ASTNode> = files.map { it.node }
        semantic.packageOf(files.first())
        val before = usedHeap()
        var diagnostics = 0
        val ms = BenchmarkSupport.timed { for (f in files) diagnostics += semantic.check(f).size }
        val after = usedHeap()
        val server = files.single { it.name == "server.go" }
        val (holders, values) = countCachedValues(server)
        val stores = PsiTreeUtil.findChildrenOfType(server, GoBlock::class.java).mapNotNull { GoBodyCache.existingStore(it) }
        BenchmarkSupport.report(
            "GoCacheMemoryBenchmark.netHttp",
            "files=${files.size} checkMs=${BenchmarkSupport.format(ms)} diagnostics=$diagnostics " +
                "retainedMB=${BenchmarkSupport.format((after - before) / 1_048_576.0)} " +
                "server.go cachedValues=${values ?: "n/a"} onElements=${holders ?: "n/a"} " +
                "bodyStores=${stores.size} bodyStoreValues=${stores.sumOf { it.size }}",
        )
        assertTrue(asts.size == files.size) // keeps the ASTs reachable until after the second sample
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        var best = Long.MAX_VALUE
        repeat(SAMPLES) {
            System.gc()
            Thread.sleep(100)
            best = minOf(best, rt.totalMemory() - rt.freeMemory())
        }
        return best
    }

    /** (elements holding at least one cached value, cached values) on the PSI of [file]; nulls when the user data is not readable. */
    private fun countCachedValues(file: GoFile): Pair<Int?, Int?> = runCatching {
        val getUserMap = UserDataHolderBase::class.java.getDeclaredMethod("getUserMap").apply { isAccessible = true }
        var holders = 0
        var values = 0
        for (e in SyntaxTraverser.psiTraverser(file)) {
            if (e !is UserDataHolderBase) continue
            val map = getUserMap.invoke(e) as KeyFMap
            @Suppress("UNCHECKED_CAST")
            val n = map.keys.count { map.get(it as Key<Any>).let { v -> v is CachedValue<*> || v is ParameterizedCachedValue<*, *> } }
            if (n > 0) {
                holders++
                values += n
            }
        }
        Pair<Int?, Int?>(holders, values)
    }.getOrElse { Pair(null, null) }

    private companion object {
        const val SAMPLES = 4
    }
}
