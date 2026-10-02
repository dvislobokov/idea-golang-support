package io.github.golangsupport.project

import com.intellij.openapi.vfs.VfsUtilCore
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner
import io.github.golangsupport.project.api.GoImportResolution
import io.github.golangsupport.project.api.GoPackageResolver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Corpus gate (run by `:go-psi-semantic:corpusTest`): every import of every non-test `.go` file of
 * every package under `$GOROOT/src` (any build constraints; `testdata` and `_`/`.` directories
 * skipped) must resolve to a directory inside GOROOT, except `import "C"`. Imports of
 * `golang.org/x/...` from std and cmd must resolve through `src/vendor` and `src/cmd/vendor`.
 * Metrics: `testData/metrics/goroot-src-imports.json`.
 */
class GorootImportsCorpusTest : GoProjectModelTestBase() {

    fun testResolveAllGorootImports() {
        val src = ProjectTestUtil.goroot().resolve("src")
        assertTrue("GOROOT/src not found: $src", Files.isDirectory(src))
        val srcVf = vfs(src)
        val resolver = GoPackageResolver.getInstance(project)

        val dirs = Files.walk(src).use { stream ->
            stream.filter { it.isDirectory() && !skipped(src, it) }.sorted().toList()
        }
        var packages = 0L
        var files = 0L
        var imports = 0L
        var cImports = 0L
        var vendorImports = 0L
        val unresolved = mutableListOf<String>()
        val denied = mutableListOf<String>()
        val outside = mutableListOf<String>()
        val started = System.nanoTime()

        for (dir in dirs) {
            val goFiles = Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && it.extension == "go" && !it.name.endsWith("_test.go") }.sorted().toList() }
            if (goFiles.isEmpty()) continue
            packages++
            for (file in goFiles) {
                files++
                val vf = vfs(file)
                val header = GoFileHeaderScanner.scan(Files.readString(file))
                for (imp in header.imports) {
                    imports++
                    val rel = src.relativize(file).joinToString("/")
                    when (val r = resolver.resolveImport(imp.path, vf)) {
                        GoImportResolution.CPseudoPackage -> cImports++
                        is GoImportResolution.Resolved -> {
                            val target = r.pkg.directory
                            if (!VfsUtilCore.isAncestor(srcVf, target, false)) outside += "$rel: ${imp.path} -> ${target.path}"
                            val targetRel = VfsUtilCore.getRelativePath(target, srcVf, '/').orEmpty()
                            if (targetRel.startsWith("vendor/") || targetRel.startsWith("cmd/vendor/")) vendorImports++
                        }
                        is GoImportResolution.InternalDenied -> denied += "$rel: ${imp.path}"
                        is GoImportResolution.Unresolved -> unresolved += "$rel: ${imp.path} (${r.reason})"
                    }
                }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println(
            """
            |GorootImportsCorpusTest summary
            |  root:            $src
            |  packages:        $packages
            |  files:           $files
            |  imports:         $imports
            |  import "C":      $cImports
            |  via vendor dirs: $vendorImports
            |  unresolved:      ${unresolved.size}
            |  internal denied: ${denied.size}
            |  outside GOROOT:  ${outside.size}
            |  time:            $millis ms
            """.trimMargin(),
        )
        unresolved.take(30).forEach { println("  UNRESOLVED $it") }
        denied.take(30).forEach { println("  DENIED $it") }
        outside.take(30).forEach { println("  OUTSIDE $it") }

        ProjectTestUtil.checkMetrics(
            ProjectTestUtil.testDataPath().resolve("metrics/goroot-src-imports.json"),
            linkedMapOf(
                "packages" to packages,
                "files" to files,
                "imports" to imports,
                "cImports" to cImports,
                "vendorImports" to vendorImports,
                "unresolved" to unresolved.size.toLong(),
                "internalDenied" to denied.size.toLong(),
                "outsideGoroot" to outside.size.toLong(),
            ),
            informational = setOf("packages", "files", "imports", "cImports", "vendorImports"),
        )
        assertEquals("unresolved imports, first: ${unresolved.take(5)}", 0, unresolved.size)
        assertEquals("internal denied, first: ${denied.take(5)}", 0, denied.size)
        assertEquals("resolved outside GOROOT, first: ${outside.take(5)}", 0, outside.size)
    }

    private fun skipped(src: Path, dir: Path): Boolean =
        src.relativize(dir).any { val n = it.toString(); n == "testdata" || n.startsWith("_") || n.startsWith(".") }
}
