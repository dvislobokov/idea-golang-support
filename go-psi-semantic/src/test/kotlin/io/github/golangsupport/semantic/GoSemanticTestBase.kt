package io.github.golangsupport.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoTypeRenderer
import java.io.File

/**
 * Base for semantic tests over fixture directories under `testData/<group>/<name>/`. Every `.go`
 * file of the directory is copied into the light project (same package directory), then the
 * markers are checked:
 *
 * - `/*ref*/ident` : the reference starting right after the marker must resolve to the
 *   declaration marked `/*def*/` with the same name in the same fixture (any file).
 * - `/*ref:Name*/ident` : resolves to the `/*def:Name*/` declaration (names a specific def).
 * - `/*no ref*/ident` : must not resolve.
 * - `/*ref GOROOT:path/file.go*/ident` : resolves into `$GOROOT/src/path/file.go`.
 * - `expr /*T: type*/` : the expression ending right before the marker has the rendered type.
 *   The expression is the largest `GoExpression` whose end offset equals the marker start.
 */
abstract class GoSemanticTestBase : GoProjectModelTestBase() {

    protected abstract val group: String

    protected val semantic: GoSemanticService get() = GoSemanticService.getInstance(project)

    protected fun loadFixture(name: String): List<GoFile> {
        val dir = File(ProjectTestUtil.testDataPath().resolve(group).resolve(name).toString())
        val files = dir.listFiles { f -> f.extension == "go" }?.sortedBy { it.name } ?: error("no fixture $dir")
        assertTrue("fixture $dir is empty", files.isNotEmpty())
        return files.map { f -> myFixture.addFileToProject("$name/${f.name}", f.readText().replace("\r\n", "\n")) as GoFile }
    }

    protected fun checkFixture(name: String) {
        val files = loadFixture(name)
        val defs = HashMap<String, MutableList<PsiElement>>()
        val refs = ArrayList<Triple<GoFile, Int, String>>()
        val typeChecks = ArrayList<Triple<GoFile, Int, String>>()
        for (file in files) {
            val text = file.text
            for (m in MARKER.findAll(text)) {
                val kind = m.groupValues[1]
                val arg = m.groupValues[2].trim()
                when {
                    kind == "def" -> {
                        val ident = identifierAfter(file, m.range.last + 1)
                        if (ident != null) defs.getOrPut(arg.ifEmpty { ident.text }) { ArrayList() } += ident
                        else {
                            // `/*def*/ "path"`: the import spec itself is the declaration.
                            val spec = PsiTreeUtil.getParentOfType(file.findElementAt(skipSpaces(text, m.range.last + 1)), GoImportSpec::class.java)
                                ?: error("${file.name}: no identifier or import after /*def*/ at ${m.range.first}: '${text.substring(m.range.last + 1, minOf(text.length, m.range.last + 20))}'")
                            defs.getOrPut(arg.ifEmpty { spec.name ?: spec.path }) { ArrayList() } += spec
                        }
                    }
                    kind == "ref" || kind == "no ref" -> refs += Triple(file, m.range.last + 1, if (kind == "no ref") "!" else arg)
                    kind == "T" -> typeChecks += Triple(file, m.range.first, arg)
                }
            }
        }
        val failures = ArrayList<String>()
        for ((file, offset, spec) in refs) {
            val ident = identifierAfter(file, offset) ?: run { failures += "${file.name}@$offset: no identifier after marker"; continue }
            val ref = referenceAt(ident) ?: run { if (spec != "!") failures += "${file.name}@$offset '${ident.text}': no reference"; continue }
            val targets: List<PsiElement> = if (ref is com.intellij.psi.PsiPolyVariantReference) ref.multiResolve(false).mapNotNull { it.element } else listOfNotNull(ref.resolve())
            when {
                spec == "!" -> if (targets.isNotEmpty()) failures += "${file.name}@$offset '${ident.text}': expected unresolved, got ${targets.map(::describe)}"
                spec.startsWith("GOROOT:") -> {
                    val expected = spec.removePrefix("GOROOT:").replace('\\', '/')
                    val paths = targets.map { it.containingFile?.virtualFile?.path?.replace('\\', '/') ?: "?" }
                    if (paths.none { it.endsWith("/src/$expected") }) failures += "${file.name}@$offset '${ident.text}': expected GOROOT $expected, got $paths"
                }
                else -> {
                    val key = spec.ifEmpty { ident.text }
                    val expected = defs[key] ?: run { failures += "${file.name}@$offset '${ident.text}': no /*def*/ for '$key'"; continue }
                    val hit = targets.any { t -> expected.any { d -> sameDeclaration(t, d) } }
                    if (!hit) failures += "${file.name}@$offset '${ident.text}': expected def '$key' (${expected.map { it.containingFile.name + "@" + it.textOffset }}), got ${targets.map(::describe)}"
                }
            }
        }
        for ((file, markerStart, expectedType) in typeChecks) {
            val expr = expressionEndingAt(file, markerStart) ?: run { failures += "${file.name}@$markerStart: no expression before /*T:*/"; continue }
            val actual = GoTypeRenderer.render(semantic.typeOf(expr))
            if (actual != expectedType) failures += "${file.name}@$markerStart '${expr.text.take(40)}': expected type '$expectedType', got '$actual'"
        }
        assertTrue("${failures.size} failures in $name:\n" + failures.joinToString("\n"), failures.isEmpty())
        println("$group/$name: ${refs.size} refs, ${typeChecks.size} type checks ok")
    }

    private fun skipSpaces(text: String, offset: Int): Int {
        var o = offset
        while (o < text.length && (text[o] == ' ' || text[o] == '	')) o++
        return o
    }

    private fun sameDeclaration(target: PsiElement, defIdentifier: PsiElement): Boolean {
        if (target == defIdentifier) return true
        if (defIdentifier is GoImportSpec) return target == defIdentifier
        val named = target as? PsiNamedElement ?: return false
        val id = (named as? GoNamedElement)?.nameIdentifier ?: return false
        return id == defIdentifier || (id.containingFile == defIdentifier.containingFile && id.textRange == defIdentifier.textRange)
    }

    private fun describe(e: PsiElement): String = "${e.javaClass.simpleName}'${(e as? PsiNamedElement)?.name ?: e.text.take(20)}'@${e.containingFile?.name}:${e.textOffset}"

    private fun identifierAfter(file: GoFile, offset: Int): PsiElement? {
        var o = offset
        val text = file.text
        while (o < text.length && (text[o] == ' ' || text[o] == '\t')) o++
        val leaf = file.findElementAt(o) ?: return null
        return if (leaf.node.elementType == io.github.golangsupport.lang.psi.GoTypes.IDENTIFIER) leaf else null
    }

    private fun referenceAt(identifier: PsiElement): PsiReference? {
        var e: PsiElement? = identifier.parent
        while (e != null && e !is GoFile) {
            e.reference?.let { r -> if (r.rangeInElement.shiftRight(e.textRange.startOffset).contains(identifier.textRange.startOffset)) return r }
            e = e.parent
        }
        // Keys: the reference is on the Key element.
        val key = PsiTreeUtil.getParentOfType(identifier, GoKey::class.java)
        return key?.reference
    }

    private fun expressionEndingAt(file: GoFile, markerStart: Int): GoExpression? {
        var end = markerStart
        val text = file.text
        while (end > 0 && text[end - 1] == ' ') end--
        val leaf = file.findElementAt(end - 1) ?: return null
        var best: GoExpression? = null
        var e: PsiElement? = leaf
        while (e != null && e !is GoFile) {
            if (e is GoExpression && e.textRange.endOffset == end) best = e
            e = e.parent
        }
        return best
    }

    companion object {
        private val MARKER = Regex("""/\*(def|ref|no ref|T)(?::([^*]*)|( GOROOT:[^*]*))?\*/""").let { Regex("""/\*(def|ref|no ref|T)(?: ?:?([^*]*))?\*/""") }
    }
}
