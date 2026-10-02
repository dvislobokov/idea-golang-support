package io.github.golangsupport.semantic

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.types.GoUnknownType
import io.github.golangsupport.semantic.scope.GoUniverse
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolve gate over real corpora (run by `corpusTest`): every value reference, type reference,
 * struct literal key, label and import of every buildable non-test file must resolve. Unresolved
 * references are classified; the metrics file may only improve.
 */
abstract class GoResolveCorpusTestBase : GoProjectModelTestBase() {

    override fun setUp() {
        super.setUp()
        // Real code has cyclic declarations the platform guards against; the corpus measures results, not caching purity.
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    protected fun runCorpus(name: String, root: Path, metricsFile: String, skipDirs: Set<String>) {
        val resolver = GoResolver.getInstance(project)
        val pkgResolver = GoPackageResolver.getInstance(project)
        val context = toolchain.buildContext
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no $root")
        val byKind = sortedMapOf<String, Long>()
        val byKindAndDir = HashMap<String, MutableMap<String, Long>>()
        val samples = ArrayList<String>()
        var files = 0L
        var refs = 0L
        var unresolved = 0L
        val start = System.currentTimeMillis()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".go") && !it.toString().endsWith("_test.go") }.sorted().forEach { path ->
                val rel = root.relativize(path).toString().replace('\\', '/')
                if (rel.split('/').any { it in skipDirs || it.startsWith(".") || it.startsWith("_") }) return@forEach
                val vf: VirtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@forEach
                val text = String(vf.contentsToByteArray(), vf.charset)
                if (!GoBuildConstraintEvaluator.matchFile(vf.name, text, context)) return@forEach
                val psi = PsiManager.getInstance(project).findFile(vf) as? GoFile ?: return@forEach
                if (psi.isCgo) { byKind.merge("cgo-file-skipped", 1, Long::plus); return@forEach }
                files++
                val fileStart = System.currentTimeMillis()
                val fileRel = rel
                fun miss(kind: String, what: String, offset: Int) {
                    unresolved++
                    byKind.merge(kind, 1, Long::plus)
                    byKindAndDir.getOrPut(kind) { HashMap() }.merge(fileRel.substringBeforeLast('/', ""), 1, Long::plus)
                    if (samples.size < 40 || samples.count { it.contains(" $kind ") } < 5) samples += "$fileRel:$offset $kind '$what'"
                }
                for (ref in PsiTreeUtil.findChildrenOfType(psi, GoReferenceExpression::class.java)) {
                    val n = ref.referenceName ?: continue
                    if (n == "_") continue
                    if (ref.parent is GoKey && ref.qualifier == null && resolver.isFieldKey(ref.parent as GoKey)) continue
                    refs++
                    val results = resolver.resolveReferenceExpression(ref)
                    if (results.isEmpty() && !(ref.qualifier == null && GoUniverse.isBuiltin(n))) {
                        val q = ref.qualifier
                        val kind = when {
                            q == null -> "unqualified"
                            q is GoReferenceExpression && q.qualifier == null && resolver.resolveReferenceExpression(q).any { it is GoResolver.Result.Import } -> {
                                val spec = resolver.resolveReferenceExpression(q).filterIsInstance<GoResolver.Result.Import>().first().element
                                if (resolver.resolveImport(spec) == null) "package-member.missing-dependency" else "package-member"
                            }
                            q != null && GoExpressionTyper.getInstance(project).typeOf(q) is GoUnknownType -> "selector.unknown-qualifier-type"
                            else -> "selector.missing-member"
                        }
                        miss(kind, ref.text, ref.textOffset)
                    }
                }
                for (ref in PsiTreeUtil.findChildrenOfType(psi, GoTypeReferenceExpression::class.java)) {
                    refs++
                    if (resolver.resolveTypeReference(ref) == null && !GoUniverse.isBuiltin(ref.identifier?.text ?: "")) miss("type", ref.text, ref.textOffset)
                }
                for (key in PsiTreeUtil.findChildrenOfType(psi, GoKey::class.java)) {
                    if (!resolver.isFieldKey(key)) continue
                    refs++
                    if (resolver.resolveFieldKey(key).isEmpty()) miss("field-key", key.text, key.textOffset)
                }
                for (label in PsiTreeUtil.findChildrenOfType(psi, GoLabelRef::class.java)) {
                    refs++
                    if (resolver.resolveLabel(label) == null) miss("label", label.text, label.textOffset)
                }
                for (imp in psi.imports) {
                    refs++
                    if (imp.path != "C" && resolver.resolveImport(imp) == null) miss("import.missing-dependency", imp.path, imp.textOffset)
                }
                val fileMillis = System.currentTimeMillis() - fileStart
                if (fileMillis > 5000) { println("SLOW resolve: $fileRel $fileMillis ms"); System.out.flush() }
            }
        }
        val millis = System.currentTimeMillis() - start
        println("""
            |$name resolve corpus
            |  root:        $root
            |  files:       $files
            |  references:  $refs
            |  unresolved:  $unresolved
            |  by kind:     $byKind
            |  time:        $millis ms (${if (refs == 0L) 0 else millis * 1000 / refs} ms per 1000 refs)
            |  top directories per kind:
            |${byKindAndDir.entries.sortedBy { it.key }.joinToString("\n") { (k, dirs) -> "    $k: " + dirs.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" } }}
            |  samples:
            |${samples.take(80).joinToString("\n") { "    $it" }}
        """.trimMargin())
        val metrics = linkedMapOf("files" to files, "references" to refs, "unresolved" to unresolved, "millis" to millis)
        byKind.forEach { (k, v) -> metrics["unresolved.$k"] = v }
        ProjectTestUtil.checkMetrics(ProjectTestUtil.testDataPath().resolve("metrics/$metricsFile"), metrics, informational = setOf("files", "references", "millis"))
    }
}

class GorootResolveCorpusTest : GoResolveCorpusTestBase() {
    fun testGorootSources() = runCorpus("GOROOT/src", ProjectTestUtil.goroot().resolve("src"), "goroot-src-resolve.json", setOf("testdata"))
}

class GomodcacheResolveCorpusTest : GoResolveCorpusTestBase() {
    fun testGolangOrgX() {
        val root = ProjectTestUtil.gomodcache().resolve("golang.org/x")
        if (!Files.isDirectory(root)) { println("skipped: $root"); return }
        runCorpus("golang.org/x", root, "gomodcache-golang-org-x-resolve.json", setOf("testdata"))
    }
}
