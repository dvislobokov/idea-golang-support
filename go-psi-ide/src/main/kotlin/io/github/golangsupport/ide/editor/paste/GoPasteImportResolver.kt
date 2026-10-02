package io.github.golangsupport.ide.editor.paste

import com.intellij.openapi.extensions.ExtensionPointName
import io.github.golangsupport.lang.psi.GoFile

/**
 * `io.github.golangsupport.pasteImportResolver`: the import path for a qualifier of pasted text that came without copy metadata
 * (`json.Marshal` pasted from a browser). go-psi has no catalogue of importable packages; a host plugin that has one answers here.
 */
interface GoPasteImportResolver {
    /** The path of the one package called [packageName] that declares every name of [members]; null when none or several do. */
    fun importPathFor(file: GoFile, packageName: String, members: Set<String>): String?

    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<GoPasteImportResolver> = ExtensionPointName.create("io.github.golangsupport.pasteImportResolver")
    }
}
