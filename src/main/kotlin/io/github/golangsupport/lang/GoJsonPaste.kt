package io.github.golangsupport.lang

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.settings.GoPasteJson
import io.github.golangsupport.settings.GoSettings

/** Where JSON is pasted into a Go file and what it becomes there ([GoJsonTypes]); the conversion is pure, for the tests. */
object GoJsonPaste {
    sealed class Place {
        /** Between declarations: whole declarations, the root type named [ROOT]. */
        object Declarations : Place()

        /** After `type Name ` typed on a top-level line: the struct type of the root, then the nested declarations. */
        class AfterTypeName(val name: String) : Place()

        /** Between the braces of `type Name struct {}`: field lines, nested objects as anonymous structs; [indent] of the fields. */
        class StructBody(val indent: String) : Place()
    }

    const val ROOT = "Generated"

    private val TYPE_NAME = Regex("""^\s*type\s+([\p{L}_][\p{L}\p{N}_]*)\s+$""")

    /** The text to paste for [json] at [place]; [lineBefore] is the text of the line before the caret. Null when it does not convert. */
    fun convert(place: Place, json: String, lineBefore: String): String? = runCatching {
        val options = GoJsonTypes.Options(pointerForNull = false)
        when (place) {
            Place.Declarations -> GoJsonTypes.generate(ROOT, json, options).code
            is Place.AfterTypeName -> {
                val code = GoJsonTypes.generate(place.name, json, options).code
                val prefix = "type ${GoJsonTypes.typeName(place.name).ifEmpty { "Data" }} "
                if (code.startsWith(prefix)) code.removePrefix(prefix) else null
            }
            is Place.StructBody -> {
                val lines = GoJsonTypes.fields(json, options, place.indent).code
                when {
                    lineBefore.isBlank() -> lines.removePrefix(lineBefore.takeIf { place.indent.startsWith(it) }.orEmpty())
                    else -> "\n" + lines
                }
            }
        }
    }.getOrNull()

    /** The place of [offset] in [file], or null where JSON stays JSON (inside a function, a string, an expression). Committed PSI. */
    fun place(file: GoFile, offset: Int, lineBefore: String): Place? {
        TYPE_NAME.matchEntire(lineBefore)?.let { match ->
            // a local `type X ` inside a function body stays as typed: the declarations of a conversion are top-level ones
            val before = file.findElementAt(maxOf(offset - 1, 0))
            if (PsiTreeUtil.getParentOfType(before, GoBlock::class.java) == null) return Place.AfterTypeName(match.groupValues[1])
        }
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1)
        val struct = PsiTreeUtil.getParentOfType(leaf, GoStructType::class.java, false)
        if (struct != null && PsiTreeUtil.getParentOfType(leaf, GoFieldDeclaration::class.java, false) == null) {
            val lbrace = struct.lbrace ?: return null
            val rbrace = struct.rbrace ?: return null
            if (offset < lbrace.textRange.endOffset || offset > rbrace.textRange.startOffset) return null
            if (PsiTreeUtil.getParentOfType(struct, GoTypeSpec::class.java) == null) return null
            return Place.StructBody(indentOf(file.text, struct.textRange.startOffset) + "\t")
        }
        return if (lineBefore.isBlank() && topLevel(file, offset)) Place.Declarations else null
    }

    /** Nothing but blanks and comments of the file itself around [offset]. */
    private fun topLevel(file: PsiFile, offset: Int): Boolean {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return true
        return (leaf is PsiWhiteSpace || leaf is PsiComment) && leaf.parent == file
    }

    private fun indentOf(text: CharSequence, offset: Int): String {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        var end = start
        while (end < text.length && (text[end] == '\t' || text[end] == ' ')) end++
        return text.substring(start, end)
    }
}

/**
 * JSON pasted into a Go file becomes Go (GoLand's "When JSON is pasted"): between declarations a struct per object, after `type Name ` the
 * struct type, inside a struct the fields. [GoSettings.pasteJson] asks (with "Don't ask again"), converts, or leaves the text as it is.
 */
class GoJsonPastePreProcessor : CopyPastePreProcessor {
    override fun preprocessOnCopy(file: PsiFile, startOffsets: IntArray, endOffsets: IntArray, text: String): String? = null

    override fun preprocessOnPaste(project: Project, file: PsiFile, editor: Editor, text: String, rawText: RawText?): String {
        val settings = GoSettings.getInstance()
        if (file !is GoFile || settings.pasteJson == GoPasteJson.AS_IS || !GoJsonTypes.looksLikeJsonObject(text)) return text
        val document = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val offset = editor.caretModel.offset
        val lineBefore = document.charsSequence.subSequence(document.getLineStartOffset(document.getLineNumber(offset)), offset).toString()
        val place = GoJsonPaste.place(file, offset, lineBefore) ?: return text
        val converted = GoJsonPaste.convert(place, text.trim(), lineBefore) ?: return text
        if (settings.pasteJson == GoPasteJson.ASK && !ask(project)) return text
        return converted
    }

    private fun ask(project: Project): Boolean {
        val remember = object : DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                if (isSelected) GoSettings.getInstance().pasteJson = if (exitCode == Messages.YES) GoPasteJson.CONVERT else GoPasteJson.AS_IS
            }
        }
        return MessageDialogBuilder.yesNo("Paste JSON", "Convert the pasted JSON to a Go type?")
            .yesText("Convert").noText("Paste as Is").doNotAsk(remember).ask(project)
    }
}
