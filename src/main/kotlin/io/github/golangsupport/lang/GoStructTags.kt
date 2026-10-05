package io.github.golangsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.ide.completion.GoStructTagCompletion
import io.github.golangsupport.lang.psi.GoFile

/**
 * What a struct tag may say, for the completion inside its backquotes: the keys the common libraries read, the names a field is written
 * under, the options of each key, the rules of go-playground/validator (`validate`, and `binding` of gin) and the settings of gorm.
 */
object GoStructTags {
    class Key(val name: String, val description: String, val naming: Naming, val separators: String = ",", val options: List<Option> = emptyList())

    class Option(val text: String, val description: String)

    /** How the first part of a value is made from the name of the field; NONE for keys whose value is a list of rules. */
    enum class Naming { FIELD, UPPER_SNAKE, NONE }

    private fun o(vararg pairs: Pair<String, String>): List<Option> = pairs.map { Option(it.first, it.second) }

    private val VALIDATOR: List<Option> = o(
        "required" to "not the zero value", "omitempty" to "skip the other rules when empty", "omitnil" to "skip the other rules when nil",
        "required_if=" to "required if another field equals: Field value", "required_unless=" to "required unless another field equals",
        "required_with=" to "required if any of the fields is present", "required_without=" to "required if any of the fields is absent",
        "required_with_all=" to "required if all of the fields are present", "required_without_all=" to "required if all of the fields are absent",
        "excluded_if=" to "must be empty if another field equals", "excluded_unless=" to "must be empty unless another field equals",
        "len=" to "length (or value) equal to", "min=" to "minimum length or value", "max=" to "maximum length or value",
        "eq=" to "equal to", "ne=" to "not equal to", "gt=" to "greater than", "gte=" to "greater than or equal", "lt=" to "less than", "lte=" to "less than or equal",
        "oneof=" to "one of the values separated by spaces", "eqfield=" to "equal to another field", "nefield=" to "not equal to another field",
        "gtfield=" to "greater than another field", "gtefield=" to "greater than or equal to another field", "ltfield=" to "less than another field", "ltefield=" to "less than or equal to another field",
        "email" to "e-mail address", "url" to "URL", "http_url" to "http(s) URL", "uri" to "URI", "uuid" to "UUID", "uuid4" to "UUID version 4", "ulid" to "ULID",
        "ip" to "IP address", "ipv4" to "IPv4 address", "ipv6" to "IPv6 address", "cidr" to "CIDR notation", "mac" to "MAC address",
        "hostname" to "host name (RFC 952)", "hostname_rfc1123" to "host name (RFC 1123)", "hostname_port" to "host:port", "fqdn" to "fully qualified domain name",
        "alpha" to "letters only", "alphanum" to "letters and digits", "alphaunicode" to "Unicode letters", "numeric" to "a number as text", "number" to "digits only",
        "boolean" to "a boolean as text", "ascii" to "ASCII only", "printascii" to "printable ASCII", "lowercase" to "lower case", "uppercase" to "upper case",
        "contains=" to "contains the text", "containsany=" to "contains any of the characters", "excludes=" to "does not contain the text",
        "startswith=" to "starts with", "endswith=" to "ends with", "datetime=" to "time in the layout: datetime=2006-01-02",
        "e164" to "phone number in E.164", "json" to "valid JSON", "jwt" to "JSON web token", "base64" to "Base64", "hexadecimal" to "hexadecimal",
        "hexcolor" to "hex colour", "rgb" to "rgb() colour", "latitude" to "latitude", "longitude" to "longitude", "semver" to "semantic version",
        "iso3166_1_alpha2" to "country code, 2 letters", "iso4217" to "currency code", "timezone" to "time zone name", "file" to "existing file", "dir" to "existing directory",
        "dive" to "apply the rules that follow to every element", "keys" to "rules for the keys of a map (end with endkeys)", "endkeys" to "end of the rules for the keys",
        "unique" to "no duplicates", "isdefault" to "the zero value", "required_if" to "", "-" to "skip the field",
    ).filter { it.description.isNotEmpty() }

    val KEYS: List<Key> = listOf(
        Key("json", "encoding/json", Naming.FIELD, options = o("omitempty" to "left out when empty", "omitzero" to "left out when zero (Go 1.24)", "string" to "a number or bool as a JSON string", "-" to "never encoded")),
        Key("yaml", "gopkg.in/yaml", Naming.FIELD, options = o("omitempty" to "left out when empty", "flow" to "flow style", "inline" to "fields of the embedded struct inline")),
        Key("xml", "encoding/xml", Naming.FIELD, options = o("attr" to "an attribute", "chardata" to "character data", "innerxml" to "raw inner XML", "comment" to "a comment", "omitempty" to "left out when empty", "any" to "any element")),
        Key("toml", "BurntSushi/toml, pelletier/go-toml", Naming.FIELD, options = o("omitempty" to "left out when empty", "inline" to "inline table")),
        Key("db", "sqlx and database mappers: the column", Naming.FIELD),
        Key("bson", "MongoDB driver", Naming.FIELD, options = o("omitempty" to "left out when empty", "inline" to "fields inline", "minsize" to "int32 when it fits", "truncate" to "truncate floats")),
        Key("mapstructure", "mitchellh/mapstructure, viper", Naming.FIELD, options = o("omitempty" to "left out when empty", "squash" to "fields of the embedded struct at this level", "remain" to "the keys left over")),
        Key("koanf", "knadh/koanf", Naming.FIELD),
        Key("msgpack", "vmihailenco/msgpack", Naming.FIELD, options = o("omitempty" to "left out when empty", "inline" to "fields inline")),
        Key("csv", "gocarina/gocsv: the column", Naming.FIELD, options = o("omitempty" to "left out when empty")),
        Key("validate", "go-playground/validator rules", Naming.NONE, ",|", VALIDATOR),
        Key("binding", "gin: validation rules (go-playground/validator)", Naming.NONE, ",|", VALIDATOR),
        Key("form", "gin / gorilla schema: the form field", Naming.FIELD, options = o("omitempty" to "left out when empty")),
        Key("query", "echo / fiber: the query parameter", Naming.FIELD),
        Key("param", "echo: the path parameter", Naming.FIELD),
        Key("uri", "gin: the path parameter", Naming.FIELD),
        Key("header", "gin / echo: the header", Naming.FIELD),
        Key("url", "google/go-querystring: the query parameter", Naming.FIELD, options = o("omitempty" to "left out when empty")),
        Key("env", "caarlos0/env, kelseyhightower/envconfig: the variable", Naming.UPPER_SNAKE,
            options = o("required" to "must be set", "notEmpty" to "must not be empty", "file" to "the value is a path to read", "expand" to "expand \$VARS", "unset" to "unset after reading", "init" to "initialize nil pointers")),
        Key("envDefault", "caarlos0/env: the default value", Naming.NONE),
        Key("envPrefix", "caarlos0/env: the prefix of a nested struct", Naming.UPPER_SNAKE),
        Key("default", "creasty/defaults: the default value", Naming.NONE),
        Key("gorm", "GORM settings of the column", Naming.NONE, ";", o(
            "column:" to "the column name", "type:" to "the column type", "primaryKey" to "primary key", "autoIncrement" to "auto increment", "unique" to "unique",
            "uniqueIndex" to "unique index", "index" to "index", "not null" to "NOT NULL", "default:" to "default value", "size:" to "size / length",
            "precision:" to "precision", "scale:" to "scale", "comment:" to "column comment", "embedded" to "embed the fields", "embeddedPrefix:" to "prefix of the embedded fields",
            "foreignKey:" to "foreign key field", "references:" to "referenced field", "constraint:" to "OnUpdate / OnDelete", "many2many:" to "join table",
            "autoCreateTime" to "set on create", "autoUpdateTime" to "set on update", "serializer:json" to "stored as JSON", "check:" to "CHECK constraint", "-" to "ignored", "->" to "read only", "<-" to "write only",
        )),
        Key("description", "a description (huma, swaggo, openapi generators)", Naming.NONE),
        Key("example", "an example value (huma, swaggo)", Naming.NONE),
    )

    private val BY_NAME = KEYS.associateBy { it.name }

    fun key(name: String): Key? = BY_NAME[name]

    sealed class Context {
        /** At a key: [prefix] typed so far; [present] the keys the tag has already. */
        class AtKey(val prefix: String, val present: Set<String>) : Context()

        /** Inside the quotes of [key]: [prefix] is the part after the last separator; [first] when it is the first part of the value. */
        class AtValue(val key: String, val prefix: String, val first: Boolean, val chosen: Set<String>) : Context()
    }

    private val PAIR = Regex("""\G\s*(\w+):"((?:[^"\\]|\\.)*)"""")

    /** What the caret is at, from the text of the tag between the opening backquote and the caret; null when it is none of the above. */
    fun contextOf(tagBeforeCaret: String): Context? {
        var position = 0
        val present = LinkedHashSet<String>()
        while (true) {
            val match = PAIR.find(tagBeforeCaret, position) ?: break
            if (match.range.first != position) break
            present += match.groupValues[1]
            position = match.range.last + 1
        }
        val rest = tagBeforeCaret.substring(position).trimStart()
        Regex("""^(\w*)$""").matchEntire(rest)?.let { return Context.AtKey(it.groupValues[1], present) }
        val value = Regex("""^(\w+):"((?:[^"\\]|\\.)*)$""").matchEntire(rest) ?: return null
        val key = value.groupValues[1]
        val text = value.groupValues[2]
        val separators = key(key)?.separators ?: ","
        val lastSeparator = text.indexOfLast { it in separators }
        val prefix = text.substring(lastSeparator + 1)
        // `oneof=red green`, `column:name`: a rule with its argument is being typed, nothing to offer
        if (prefix.contains('=') || prefix.contains(':')) return null
        val chosen = text.split(*separators.toCharArray()).map { it.trim() }.filter { it.isNotEmpty() }.dropLast(if (prefix.isEmpty()) 0 else 1).toSet()
        return Context.AtValue(key, prefix.trimStart(), lastSeparator < 0, chosen)
    }

    /** The values offered at the first part of a key: the name of the field in GoLand's four styles, then in lower case. */
    fun names(key: Key, fieldName: String): List<String> = when (key.naming) {
        Naming.FIELD -> (GoStructTagCompletion.nameStyles(fieldName) + fieldName.lowercase()).distinct()
        Naming.UPPER_SNAKE -> listOf(GoGenerators.snakeCase(fieldName).uppercase())
        Naming.NONE -> emptyList()
    }

    /**
     * Where a tag starts and the field it belongs to, from the PSI: [offset] inside the backquotes of the tag of a field declaration of
     * any struct type (a named one, a field of a function, a variable). A tag still being typed has no closing backquote: the raw string
     * runs to the end of the file and is the tag all the same. The field is the first name of the declaration, or the type of an
     * embedded field. Null anywhere else, an interpreted string tag (`"json:\"id\""`) included. Needs read access.
     */
    fun tagAt(file: PsiFile, offset: Int): Pair<Int, String>? {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        val tag = GoStructPsi.tagAround(leaf) ?: return null
        val start = tag.textRange.startOffset
        if (offset <= start || file.viewProvider.contents.getOrNull(start) != '`') return null
        // after the closing backquote the caret is out of the tag
        if (tag.textLength > 1 && tag.text.endsWith('`') && offset >= tag.textRange.endOffset) return null
        val declaration = tag.parent as? GoFieldDeclaration ?: return null
        val field = GoStructPsi.fieldsOf(declaration).firstOrNull()?.name ?: return null
        return start to field
    }
}

/**
 * Completion inside the backquotes of a struct tag: the keys (`json`, `yaml`, `validate`, `gorm`…), the name of the field in the cases
 * each key uses, the options after a comma, the rules of the validator and the settings of gorm. A chosen key becomes `json:""` with
 * the caret inside and the completion open again.
 */
class GoStructTagCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is GoFile) return
        val text = parameters.editor.document.immutableCharSequence
        val offset = parameters.offset
        // the copy of the file completion runs in has the dummy identifier after the caret: offsets up to the caret are those of the editor
        val (open, field) = GoStructTags.tagAt(parameters.position.containingFile, offset) ?: return
        val context = GoStructTags.contextOf(text.subSequence(open + 1, offset).toString()) ?: return
        when (context) {
            is GoStructTags.Context.AtKey -> {
                val keys = result.withPrefixMatcher(context.prefix)
                // GoLand's first item; only in a closed tag (`Age int ``): a tag still being typed runs to the end of the file
                if (GoStructTagKeyToAllFields.applicable(parameters.originalFile, offset)) keys.addElement(GoStructTagKeyToAllFields.item())
                for (key in GoStructTags.KEYS) {
                    if (key.name in context.present) continue
                    keys.addElement(LookupElementBuilder.create(key.name).withTypeText(key.description, true).withIcon(AllIcons.Nodes.Tag).withInsertHandler(KEY_INSERT))
                }
            }
            is GoStructTags.Context.AtValue -> {
                val values = result.withPrefixMatcher(context.prefix)
                val key = GoStructTags.key(context.key)
                // the style the struct's other fields use for this key comes first (`user_id` next to `first_name`), then GoLand's four
                val styled = parameters.originalFile.findElementAt(offset - 1)?.let { GoStructTagCompletion.tagAt(it, offset) }?.first
                    ?.let { GoStructTagCompletion.namesFor(it, context.key, observedOnly = true) }.orEmpty().takeIf { key?.naming == GoStructTags.Naming.FIELD }.orEmpty()
                if (context.first && key != null) (styled + GoStructTags.names(key, field)).distinct().forEachIndexed { i, name ->
                    values.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(name).withTypeText("name of $field", true).withIcon(AllIcons.Nodes.Field), 100.0 - i,
                    ))
                }
                val options = key?.options.orEmpty().filter { it.text !in context.chosen }
                // the first part of a naming key is its name; options come after the comma
                if (!(context.first && key?.naming != GoStructTags.Naming.NONE)) for (option in options) {
                    values.addElement(LookupElementBuilder.create(option.text).withTypeText(option.description, true).withIcon(AllIcons.Nodes.Property))
                }
            }
        }
        // nothing else knows what a tag may say: the words of other contributors would be noise
        result.stopHere()
    }

    private companion object {
        val KEY_INSERT = InsertHandler<LookupElement> { context: InsertionContext, _ ->
            val document = context.document
            val tail = context.tailOffset
            // `json` -> `json:""`, unless the colon is there already
            if (document.charsSequence.getOrNull(tail) != ':') {
                document.insertString(tail, ":\"\"")
                context.editor.caretModel.moveToOffset(tail + 2)
            }
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }
}
