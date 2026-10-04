package io.github.golangsupport.lang

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSpec
import java.awt.datatransfer.StringSelection

/**
 * A JSON document for a Go struct, the reverse of [GoJsonTypes]: the keys come from `json` tags the way encoding/json reads them
 * (`name,omitempty`, `-`), embedded structs are flattened, values are zero-like samples by the type of the field.
 * Pure over a small model; [GoJsonSamplePsi] reads the model from PSI.
 */
object GoJsonSample {
    class Field(val name: String, val typeText: String, val tag: String?, val embedded: Boolean = false)

    /** What a type name stands for: a struct with its fields, or another type spelled out (`type Status string`). */
    sealed class Declared {
        class Struct(val fields: List<Field>) : Declared()
        class Alias(val typeText: String) : Declared()
    }

    private const val TIMESTAMP = "2006-01-02T15:04:05Z"
    private val NUMBERS = setOf("int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "float32", "float64", "byte", "rune")
    private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
    private val JSON_TAG = Regex("""(?:^|\s)json:"([^"]*)"""")

    /** The JSON text for a struct with [fields]; [resolve] finds a struct or a named type of the same package by its name, null when unknown. */
    fun sample(fields: List<Field>, resolve: (String) -> Declared? = { null }): String = GSON.toJson(structValue(fields, resolve, emptySet()))

    private fun structValue(fields: List<Field>, resolve: (String) -> Declared?, visiting: Set<String>): JsonObject {
        val result = JsonObject()
        for (field in fields) {
            if (tagValue(field.tag) == "-") continue
            val tagName = tagName(field.tag)
            if (field.embedded && tagName.isNullOrEmpty()) {
                val name = field.typeText.trim().removePrefix("*")
                val declared = if (name in visiting) null else resolve(name)
                if (declared is Declared.Struct) {
                    // encoding/json: the fields of the outer struct win over those it embeds
                    for ((key, value) in structValue(declared.fields, resolve, visiting + name).entrySet()) if (!result.has(key)) result.add(key, value)
                    continue
                }
            }
            if (!field.name.first().isUpperCase()) continue
            val key = tagName?.takeIf { it.isNotEmpty() } ?: field.name
            if (!result.has(key)) result.add(key, value(field.typeText, resolve, visiting))
        }
        return result
    }

    private fun value(typeText: String, resolve: (String) -> Declared?, visiting: Set<String>): JsonElement {
        val type = typeText.trim()
        if (type.startsWith("*")) return value(type.substring(1), resolve, visiting)
        if (type.startsWith("[]byte")) return JsonPrimitive("")
        if (type.startsWith("[")) return JsonArray().also { it.add(value(type.substring(type.indexOf(']') + 1), resolve, visiting)) }
        if (type.startsWith("map[")) return JsonObject()
        if (type == "string") return JsonPrimitive("")
        if (type == "bool") return JsonPrimitive(false)
        if (type in NUMBERS || type == "time.Duration") return JsonPrimitive(0)
        if (type == "time.Time") return JsonPrimitive(TIMESTAMP)
        if (type.isEmpty() || type == "any" || type.startsWith("struct") || type.startsWith("interface") || '.' in type || type in visiting) return JsonNull.INSTANCE
        return when (val declared = resolve(type)) {
            is Declared.Struct -> structValue(declared.fields, resolve, visiting + type)
            is Declared.Alias -> value(declared.typeText, resolve, visiting + type)
            null -> JsonNull.INSTANCE
        }
    }

    private fun tagValue(tag: String?): String? {
        val text = tag?.trim()?.removeSurrounding("`")?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: return null
        return JSON_TAG.find(text)?.groupValues?.get(1)
    }

    /** The name part of `json:"..."`, empty for no name, null for no `json` key; `json:"-,"` names the field "-" (`json:"-"` skips it). */
    fun tagName(tag: String?): String? = tagValue(tag)?.substringBefore(',')
}

/** PSI side: the fields of a struct spec and the lookup of the types of its package. */
object GoJsonSamplePsi {
    fun fieldsOf(spec: GoTypeSpec): List<GoJsonSample.Field>? {
        val struct = GoStructPsi.structOf(spec) ?: return null
        return GoStructPsi.fields(struct).map { GoJsonSample.Field(it.name, it.typeText, it.tag, it.embedded) }
    }

    fun sampleOf(file: GoFile, spec: GoTypeSpec): String? {
        val fields = fieldsOf(spec) ?: return null
        val siblings = file.containingDirectory?.files?.filterIsInstance<GoFile>().orEmpty().filter { it != file && it.packageName == file.packageName && !it.isTestFile }
        val specs = (listOf(file) + siblings).flatMap { it.types }
        return GoJsonSample.sample(fields) { name ->
            val found = specs.firstOrNull { it.name == name } ?: return@sample null
            fieldsOf(found)?.let { GoJsonSample.Declared.Struct(it) } ?: found.type?.text?.let { GoJsonSample.Declared.Alias(it) }
        }
    }
}

/** Alt+Enter on a struct type: its JSON document to the clipboard. */
class GoCopyJsonSampleIntention : IntentionAction {
    override fun getText(): String = "Copy JSON sample to clipboard"
    override fun getFamilyName(): String = "Go: copy JSON sample"
    override fun startInWriteAction(): Boolean = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file is GoFile && editor != null && GenerateContext(project, editor, file).structTypeAtCaret != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is GoFile || editor == null) return
        val json = runReadAction { GenerateContext(project, editor, file).typeSpecAtCaret?.let { GoJsonSamplePsi.sampleOf(file, it) } } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(json))
        HintManager.getInstance().showInformationHint(editor, "JSON sample copied to the clipboard")
    }
}
