package io.github.golangsupport.lang

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.awt.datatransfer.DataFlavor
import javax.swing.JComponent
import io.github.golangsupport.lang.psi.GoFile

/**
 * Go types for a JSON document, the way GoLand's "type from JSON" writes them: a struct per object, named after its key, with
 * `json` tags; a slice per array; `time.Time` for a string in RFC 3339; the elements of an array of objects merged into one struct.
 * The types of the values are read from the document only, so a number without a point is `int` and `null` alone is `any`.
 */
object GoJsonTypes {
    class Options(val omitEmpty: Boolean = false, val pointerForNull: Boolean = true)

    class Result(val code: String, val imports: List<String>)

    private sealed class Type {
        data class Primitive(val name: String) : Type()
        data class Slice(val element: Type) : Type()
        data class Pointer(val target: Type) : Type()
        /** [name] is given when the struct is written out; the fields keep the order of the document. */
        class Struct(val fields: LinkedHashMap<String, Type>, var name: String = "") : Type()
        object Any : Type()
    }

    private val DATE_TIME = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$""")
    private val WORDS = Regex("""[A-Za-z]+|\d+""")
    private val ACRONYMS = setOf("ID", "URL", "URI", "HTTP", "HTTPS", "API", "JSON", "XML", "SQL", "DB", "UUID", "IP", "TCP", "UDP", "HTML", "CSS", "OS", "TLS", "SSH", "RPC", "CPU", "TTL", "UID", "GID")

    /** A quick look at a text, for prefilling the dialog from the clipboard: it begins like a JSON object or array and parses. */
    fun looksLikeJson(text: String?): Boolean {
        val trimmed = text?.trim() ?: return false
        if (trimmed.length < 2 || trimmed.length > 200_000 || trimmed[0] != '{' && trimmed[0] != '[') return false
        return runCatching { JsonParser.parseString(trimmed).let { it.isJsonObject || it.isJsonArray } }.getOrDefault(false)
    }

    /** The declarations for [json], the root named [name]. Throws [IllegalArgumentException] when the text is not JSON. */
    fun generate(name: String, json: String, options: Options = Options()): Result {
        val root = runCatching { JsonParser.parseString(json) }.getOrElse { throw IllegalArgumentException(it.message ?: "not JSON") }
        val rootName = typeName(name).ifEmpty { "Data" }
        val type = infer(root, rootName, options)
        val structs = ArrayList<Type.Struct>()
        val used = HashSet<String>()
        fun collect(type: Type, hint: String) {
            when (type) {
                is Type.Struct -> {
                    if (type in structs) return
                    type.name = unique(hint.ifEmpty { "Item" }, used)
                    structs += type
                    for ((key, field) in type.fields) collect(field, typeName(key, singular = field is Type.Slice))
                }
                is Type.Slice -> collect(type.element, hint)
                is Type.Pointer -> collect(type.target, hint)
                else -> {}
            }
        }
        val imports = ArrayList<String>()
        val code = StringBuilder()
        if (type is Type.Struct) {
            collect(type, rootName)
        } else {
            collect(type, if (type is Type.Slice) singular(rootName) else rootName)
            code.append("type ").append(rootName).append(' ').append(render(type, imports)).append("\n")
        }
        for (struct in structs) {
            if (code.isNotEmpty()) code.append('\n')
            code.append(renderStruct(struct, options, imports))
        }
        return Result(code.toString(), imports.distinct())
    }

    /** A JSON object (not an array, not a scalar): what paste converts. */
    fun looksLikeJsonObject(text: String?): Boolean = looksLikeJson(text) && text!!.trim().startsWith("{")

    /**
     * The field lines of the root object of [json] for the body of an existing struct (paste between its braces): a nested object is an
     * anonymous struct in place, so that nothing has to be declared elsewhere. Lines indented by [indent]; throws when not a JSON object.
     */
    fun fields(json: String, options: Options = Options(), indent: String = "\t"): Result {
        val root = runCatching { JsonParser.parseString(json) }.getOrElse { throw IllegalArgumentException(it.message ?: "not JSON") }
        val type = infer(root, "Data", options) as? Type.Struct ?: throw IllegalArgumentException("not a JSON object")
        val imports = ArrayList<String>()
        return Result(inlineFields(type, options, indent, imports), imports.distinct())
    }

    private fun inlineFields(struct: Type.Struct, options: Options, indent: String, imports: MutableList<String>): String = buildString {
        val names = HashSet<String>()
        for ((key, type) in struct.fields) {
            val tag = "`json:\"$key${if (options.omitEmpty) ",omitempty" else ""}\"`"
            append(indent).append(unique(fieldName(key), names)).append(' ').append(renderInline(type, options, indent, imports)).append(' ').append(tag).append('\n')
        }
    }

    private fun renderInline(type: Type, options: Options, indent: String, imports: MutableList<String>): String = when (type) {
        is Type.Struct -> "struct {\n" + inlineFields(type, options, "$indent\t", imports) + indent + "}"
        is Type.Slice -> "[]" + renderInline(type.element, options, indent, imports)
        is Type.Pointer -> "*" + renderInline(type.target, options, indent, imports)
        else -> render(type, imports)
    }

    private fun infer(element: JsonElement, hint: String, options: Options): Type = when {
        element.isJsonNull -> Type.Any
        element is JsonPrimitive -> when {
            element.isBoolean -> Type.Primitive("bool")
            element.isNumber -> {
                val literal = element.asString
                when {
                    literal.any { it == '.' || it == 'e' || it == 'E' } -> Type.Primitive("float64")
                    literal.toLongOrNull()?.let { it > Int.MAX_VALUE || it < Int.MIN_VALUE } == true -> Type.Primitive("int64")
                    else -> Type.Primitive("int")
                }
            }
            DATE_TIME.matches(element.asString) -> Type.Primitive("time.Time")
            else -> Type.Primitive("string")
        }
        element is JsonArray -> {
            var merged: Type? = null
            for (item in element) merged = if (merged == null) infer(item, hint, options) else merge(merged, infer(item, hint, options), options)
            Type.Slice(merged ?: Type.Any)
        }
        element is JsonObject -> Type.Struct(LinkedHashMap<String, Type>().apply {
            for ((key, value) in element.entrySet()) put(key, infer(value, typeName(key, singular = value.isJsonArray), options))
        })
        else -> Type.Any
    }

    /** The type of two values seen in the same place: `1` and `1.5` are `float64`, an object and `null` a pointer, the rest `any`. */
    private fun merge(a: Type, b: Type, options: Options): Type = when {
        a == b -> a
        a is Type.Any -> if (options.pointerForNull && b !is Type.Any && b !is Type.Pointer && b !is Type.Slice) Type.Pointer(b) else if (b is Type.Slice || b is Type.Pointer) b else Type.Any
        b is Type.Any -> merge(b, a, options)
        a is Type.Pointer -> merge(a.target, b, options).let { if (it is Type.Pointer || it is Type.Any) it else Type.Pointer(it) }
        b is Type.Pointer -> merge(b, a, options)
        a is Type.Primitive && b is Type.Primitive && setOf(a.name, b.name) == setOf("int", "float64") -> Type.Primitive("float64")
        a is Type.Primitive && b is Type.Primitive && setOf(a.name, b.name) == setOf("int", "int64") -> Type.Primitive("int64")
        a is Type.Slice && b is Type.Slice -> Type.Slice(merge(a.element, b.element, options))
        a is Type.Struct && b is Type.Struct -> {
            // one struct for every element of an array: the fields of all of them, in the order they are first seen
            for ((key, type) in b.fields) a.fields[key] = a.fields[key]?.let { merge(it, type, options) } ?: type
            a
        }
        else -> Type.Any
    }

    private fun render(type: Type, imports: MutableList<String>): String = when (type) {
        is Type.Primitive -> type.name.also { if (it == "time.Time") imports += "time" }
        is Type.Slice -> "[]" + render(type.element, imports)
        is Type.Pointer -> "*" + render(type.target, imports)
        is Type.Struct -> type.name
        Type.Any -> "any"
    }

    private fun renderStruct(struct: Type.Struct, options: Options, imports: MutableList<String>): String {
        val names = HashSet<String>()
        val rows = struct.fields.map { (key, type) -> Triple(unique(fieldName(key), names), render(type, imports), "`json:\"$key${if (options.omitEmpty) ",omitempty" else ""}\"`") }
        val nameWidth = rows.maxOfOrNull { it.first.length } ?: 0
        val typeWidth = rows.maxOfOrNull { it.second.length } ?: 0
        return buildString {
            append("type ").append(struct.name).append(" struct {\n")
            // the columns are aligned the way gofmt aligns them, so the text is final as it is written
            for ((name, type, tag) in rows) append('\t').append(name.padEnd(nameWidth)).append(' ').append(type.padEnd(typeWidth)).append(' ').append(tag).append('\n')
            append("}\n")
        }
    }

    /** `user_id` → `UserID`, `first-name` → `FirstName`, `2fa` → `N2fa`: an exported field name for a key. */
    fun fieldName(key: String): String {
        val words = WORDS.findAll(key).map { it.value }.toList()
        val name = words.joinToString("") { word -> if (word.uppercase() in ACRONYMS) word.uppercase() else word.replaceFirstChar { it.uppercaseChar() } }
        return when {
            name.isEmpty() -> "Field"
            name[0].isDigit() -> "N$name"
            else -> name
        }
    }

    /** The name of the struct for a key: the field name, in the singular for the element of an array. */
    fun typeName(key: String, singular: Boolean = false): String = fieldName(key).let { if (singular) singular(it) else it }.let { if (it == "Field") "" else it }

    fun singular(name: String): String = when {
        name.endsWith("ies") && name.length > 4 -> name.dropLast(3) + "y"
        name.endsWith("ses") || name.endsWith("xes") || name.endsWith("shes") || name.endsWith("ches") -> name.dropLast(2)
        name.endsWith("s") && !name.endsWith("ss") && name.length > 2 -> name.dropLast(1)
        else -> name
    }

    private fun unique(name: String, used: MutableSet<String>): String {
        var candidate = name
        var n = 2
        while (!used.add(candidate)) candidate = name + n++
        return candidate
    }
}

/** The dialog of Type from JSON: the name of the root type, the document (the clipboard, when it holds JSON), the options. */
class GoJsonTypesDialog(project: Project, initialName: String = "Data") : DialogWrapper(project) {
    private val name = JBTextField(initialName, 20)
    private val json = JBTextArea(16, 60).apply { text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)?.takeIf(GoJsonTypes::looksLikeJson)?.trim() ?: "" }
    private val omitEmpty = JBCheckBox("omitempty in the tags", false)
    private val pointerForNull = JBCheckBox("Pointer for a value that is null in some elements", true)

    init {
        title = "Type From JSON"
        setOKButtonText("Generate")
        init()
    }

    fun typeName(): String = name.text.trim()
    fun options(): GoJsonTypes.Options = GoJsonTypes.Options(omitEmpty.isSelected, pointerForNull.isSelected)

    /** The types for what is in the dialog, or the message about why there are none. */
    fun result(): Result<GoJsonTypes.Result> = runCatching { GoJsonTypes.generate(typeName(), json.text, options()) }

    override fun createCenterPanel(): JComponent = panel {
        row("Type name:") { cell(name) }
        row { cell(JBScrollPane(json)).align(Align.FILL) }.resizableRow()
        row { cell(omitEmpty) }
        row { cell(pointerForNull) }
    }

    override fun doValidate() = result().exceptionOrNull()?.let { ValidationInfo("Not JSON: ${it.message}", json) }
    override fun getPreferredFocusedComponent(): JComponent = if (json.text.isEmpty()) json else name
}

/** Alt+Insert | Type from JSON…: the types after the declaration at the caret, or at the end of the file; `time` is imported when needed. */
class GoTypeFromJsonAction : GoGenerateAction() {
    override fun perform(context: GenerateContext) = generate(context.project, context.editor, context.file, at = null)

    companion object {
        /** [at] is where the types go; null puts them after the declaration at the caret. */
        fun generate(project: Project, editor: Editor, file: GoFile, at: Int?) {
            val dialog = GoJsonTypesDialog(project)
            if (!dialog.showAndGet()) return
            val result = dialog.result().getOrNull() ?: return
            val context = GenerateContext(project, editor, file)
            if (at == null) {
                context.insertAfter(context.typeAtCaret ?: context.functionAtCaret, result.code, TITLE)
            } else {
                WriteCommandAction.runWriteCommandAction(project, TITLE, null, {
                    editor.document.insertString(at, result.code)
                    editor.caretModel.moveToOffset(at)
                }, file)
            }
            if (result.imports.isEmpty()) return
            WriteCommandAction.runWriteCommandAction(project, TITLE, null, {
                for (path in result.imports) GoImports.add(editor.document.immutableCharSequence, path)?.let { editor.document.insertString(it.offset, it.text) }
                PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            }, file)
        }

        private const val TITLE = "Type From JSON"
    }
}
