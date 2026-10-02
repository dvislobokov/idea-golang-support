package io.github.golangsupport.catalogue

import io.github.golangsupport.ide.editor.paste.GoPasteImportResolver
import io.github.golangsupport.lang.psi.GoFile

/**
 * Imports for Go text pasted from outside the IDE (go-psi-ide `GoPasteImportsProcessor`): an unresolved qualifier gets the standard
 * package of that name which has every name used through it, and only when there is exactly one (`template.New` stays as it is:
 * `text/template` or `html/template`). The modules of the project are not guessed from: a wrong import there is worse than none.
 */
class GoCataloguePasteImportResolver : GoPasteImportResolver {
    override fun importPathFor(file: GoFile, packageName: String, members: Set<String>): String? =
        GoCatalogueService.getInstance(file.project).index.standardPackagesOf(packageName, members).singleOrNull()
}
