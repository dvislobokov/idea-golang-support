package io.github.golangsupport.ide.refactoring

import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.completion.GoImportPaths
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes

/**
 * Imports for Go text a refactoring writes into a file (a method spec, new parameter types, default values at the calls): the dialogs
 * complete package names without importing them (the text lives in a fragment), so the processors add them where the text lands.
 */
object GoMissingImports {

    private val QUALIFIED = Regex("""\b([A-Za-z_]\w*)\.([A-Z]\w*)""")

    /**
     * The import paths for the `pkg.Name` qualifiers of [text] that [file] does not import and its package does not declare: a
     * standard-library or module package of that name exporting the name, standard library first (like the "Add import" fix).
     */
    fun of(file: GoFile, text: String): List<String> {
        val project = file.project
        val imported = file.imports.map { GoScopes.importName(it) }.toSet()
        val model = GoPackageModel.getInstance(project)
        val result = ArrayList<String>()
        for (m in QUALIFIED.findAll(text)) {
            val (qualifier, selected) = m.destructured
            if (qualifier in imported || model.scopeOf(file).lookup(qualifier).isNotEmpty()) continue
            val path = GoImportPaths.all(project, file.originalFile.virtualFile).sortedByDescending { it.isStd }.firstOrNull { entry ->
                entry.name == qualifier && model.resolveImport(entry.path, file)?.let { pkg ->
                    (pkg.name == null || pkg.name == qualifier) && model.scopeOf(pkg).lookup(selected).any { it.isPublic() }
                } == true
            }?.path ?: continue
            if (path !in result) result += path
        }
        return result
    }

    /** Adds [of] to [file] (its document committed before and after); the paths added. */
    fun add(file: GoFile, text: String): List<String> {
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return emptyList()
        documents.commitDocument(document)
        val imports = of(file, text)
        // Committed after each: the inserter reads the import declarations from the PSI (two imports make a group).
        for (path in imports) {
            GoImportInserter.addImport(file, document, path)
            documents.commitDocument(document)
        }
        return imports
    }
}
