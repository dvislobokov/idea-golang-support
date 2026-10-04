package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSpecType
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition

/**
 * govet `stdversion`: a reference to a standard-library symbol added in a Go release newer than the file's Go version (the module's
 * `go` directive, or a `//go:build go1.N` line of the file, never below go1.21). Fields and methods of a type that is itself too new
 * are not reported. Silent for modules before go1.21. Data: `resources/lint/stdlib-since.txt` (from GOROOT/api).
 */
class GoVetStdVersionRule : GoVetFileRule() {
    override val id: String get() = "govet:stdversion"
    override val title: String get() = "Standard library symbol newer than the Go version"
    override val description: String get() =
        "govet <code>stdversion</code>: the symbol was added to the standard library in a Go release higher than the one the file targets " +
            "(the <code>go</code> directive of <code>go.mod</code>, or a <code>//go:build go1.N</code> line), so the module does not build " +
            "with the toolchains it claims to support. Raise the <code>go</code> directive or avoid the symbol."
    override val needs: Set<GoRuleNeed> get() = GoVetPsi.TYPES_INDEX

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        val pkgVersion = GoVetPsi.moduleGoVersion(file) ?: return
        if (GoVetPsi.compare(pkgVersion, "go1.21") < 0) return
        val fileVersion = GoVetPsi.lang(GoVetPsi.fileGoVersion(file)?.let { if (GoVetPsi.compare(it, "go1.21") < 0) "go1.21" else it } ?: pkgVersion) ?: return
        val names = GoStdlibTables.sinceNames
        for (e in SyntaxTraverser.psiTraverser(file)) {
            val (id, target) = when (e) {
                is GoReferenceExpression -> {
                    if (e.identifier.text !in names) continue
                    e.identifier to (ctx.resolve(e).singleOrNull() ?: continue)
                }
                is GoTypeReferenceExpression -> {
                    val ident = e.identifier ?: continue
                    if (ident.text !in names) continue
                    ident to (ctx.semantic.resolve(e) ?: continue)
                }
                else -> continue
            }
            val named = target as? GoNamedElement ?: continue
            val path = GoAnalysisPsi.packagePath(named) ?: continue
            val symbols = GoStdlibTables.since[path] ?: continue
            if (path == "testing/synctest" && GoVetPsi.compare(fileVersion, "go1.24") >= 0) continue
            if ((path == "encoding/json/v2" || path == "encoding/json/jsontext") && GoVetPsi.compare(fileVersion, "go1.25") >= 0) continue
            val (key, owner) = symbolKey(named) ?: continue
            val sym = symbols[key] ?: continue
            if (GoVetPsi.compare(fileVersion, sym.version) >= 0) continue
            if (owner != null && symbols[owner]?.let { GoVetPsi.compare(fileVersion, it.version) < 0 } == true) continue // the type is too new itself
            val pkgName = (named.containingFile as? GoFile)?.packageName ?: path.substringAfterLast('/')
            val which = if (fileVersion != pkgVersion) "file" else "module"
            ctx.report(id, "$pkgName.${sym.name} requires ${sym.version} or later ($which is $fileVersion)")
        }
    }

    /** The key of [e] in the table (`Name`, `T.F`, `(*T).M`, `(T).M`) and, for fields and methods, the name of their type. */
    private fun symbolKey(e: GoNamedElement): Pair<String, String?>? {
        val name = e.name ?: return null
        return when (e) {
            is GoFunctionDeclaration -> name to null
            is GoTypeSpec -> if (GoVetPsi.isTopLevelType(e)) name to null else null
            is GoVarDefinition, is GoConstDefinition -> if (e.parent?.parent?.parent is GoFile) name to null else null
            is GoMethodDeclaration -> {
                val recv = e.receiverTypeName ?: return null
                (if (e.isPointerReceiver) "(*$recv).$name" else "($recv).$name") to recv
            }
            is GoMethodSpec -> {
                val type = ownerType(e.parent as? GoInterfaceType) ?: return null
                "($type).$name" to type
            }
            is GoFieldDefinition, is GoAnonymousFieldDefinition -> {
                val type = ownerType(e.parent?.parent as? GoStructType) ?: return null
                "$type.$name" to type
            }
            else -> null
        }
    }

    /** The name of the top-level type spec whose type is [type]. */
    private fun ownerType(type: PsiElement?): String? {
        var p = type?.parent
        while (p is GoSpecType) p = p.parent
        val spec = p as? GoTypeSpec ?: return null
        return if (GoVetPsi.isTopLevelType(spec)) spec.name else null
    }
}
