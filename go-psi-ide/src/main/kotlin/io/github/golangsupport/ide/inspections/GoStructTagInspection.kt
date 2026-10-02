package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTag

/**
 * vet `structtag` over the struct types of a file, from the PSI only (no types needed):
 * - a tag `reflect.StructTag.Get` cannot read (vet's messages; fix "Fix quoting" when [GoStructTags.repaired] knows the repair);
 * - the same key twice in one tag (reflect reads only the first; fix "Remove duplicate key");
 * - two fields of one struct with the same `json` / `xml` / `yaml` / `db` name (`-` and empty names skipped, XML attributes in their
 *   own namespace, `XMLName` skipped; embedded structs are not descended into, so promoted names are not compared);
 * - a `json` / `xml` tag on an unexported field (encoders ignore the field).
 */
class GoStructTagInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoStructType) return
        val seen = HashMap<String, String>()
        for (declaration in element.fieldDeclarationList) {
            val tagElement = declaration.tag ?: continue
            val literal = tagElement.stringLiteral
            val tag = valueOf(literal) ?: continue
            val parsed = GoStructTags.parse(tag)
            parsed.error?.let { error ->
                val fix = if (error == GoStructTags.ERR_VALUE_SPACE) null else GoStructTags.repaired(tag)?.let { GoReplaceStructTagFix("Fix quoting", it) }
                holder.registerProblem(tagElement, "struct field tag `$tag` not compatible with reflect.StructTag.Get: $error", *listOfNotNull(fix).toTypedArray())
            }
            val keys = HashSet<String>()
            for (pair in parsed.pairs) if (!keys.add(pair.key)) {
                holder.registerProblem(tagElement, "Duplicate key \"${pair.key}\" in struct field tag", GoReplaceStructTagFix("Remove duplicate key", GoStructTags.without(tag, pair)))
            }
            val anonymous = declaration.anonymousFieldDefinition
            val names = if (anonymous != null) listOfNotNull(anonymous.name) else declaration.fieldDefinitionList.mapNotNull { it.name }
            for (key in NAME_KEYS) {
                val value = parsed.lookup(key) ?: continue
                if (value == "-" || value.isEmpty() || value[0] == ',') continue
                val name = value.substringBefore(',')
                val namespace = if (key == "xml" && value.substringAfter(',', "").split(',').contains("attr")) "xml attribute" else key
                for (field in names) {
                    if (key == "xml" && field == "XMLName") continue
                    val previous = seen.putIfAbsent("$namespace:$name", field) ?: continue
                    holder.registerProblem(tagElement, "struct field $field repeats $key tag \"$name\" also at field $previous")
                }
            }
            if (anonymous != null) continue
            for (definition in declaration.fieldDefinitionList) {
                val name = definition.name ?: continue
                if (Character.isUpperCase(name.codePointAt(0))) continue
                val encoding = ENCODINGS.firstOrNull { parsed.lookup(it).let { v -> v != null && v != "" && v != "-" } } ?: continue
                holder.registerProblem(definition.identifier, "struct field $name has $encoding tag but is not exported")
            }
        }
    }

    companion object {
        /** Keys whose names must be unique among the fields of one struct. */
        private val NAME_KEYS = listOf("json", "xml", "yaml", "db")

        /** Keys vet checks on unexported fields. */
        private val ENCODINGS = listOf("json", "xml")

        /** The tag value of [literal]: a raw string without its backquotes (and carriage returns, like the compiler), or an unquoted interpreted one. */
        fun valueOf(literal: GoStringLiteral): String? {
            val text = literal.text
            return if (literal.rawString != null) {
                if (text.length < 2 || !text.endsWith("`")) null else text.substring(1, text.length - 1).replace("\r", "")
            } else GoStructTags.unquote(text)
        }
    }
}

/** Replaces the tag literal by [newTag], keeping its kind (raw or interpreted string). */
class GoReplaceStructTagFix(private val text: String, private val newTag: String) : LocalQuickFix {
    override fun getFamilyName(): String = text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val tag = descriptor.psiElement as? GoTag ?: return
        val literal = tag.stringLiteral
        val document = GoImportEdits.document(tag.containingFile) ?: return
        val replacement = if (literal.rawString != null && '`' !in newTag) "`$newTag`" else GoStructTags.quote(newTag)
        document.replaceString(literal.textRange.startOffset, literal.textRange.endOffset, replacement)
        GoImportEdits.commit(tag.containingFile, document)
    }
}
