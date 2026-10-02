package io.github.golangsupport.ide.formatter

import com.intellij.formatting.Block
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingMode
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.Indent
import com.intellij.psi.formatter.FormattingDocumentModelImpl
import io.github.golangsupport.ide.formatter.printer.GoLayout
import io.github.golangsupport.lang.GoLanguage

/**
 * gofmt-compatible formatter.
 *
 * For Reformat Code the whole file is laid out by the `go/printer` port ([GoLayout], cached per
 * file until it changes); the block tree then hands that layout to the platform engine as exact
 * spacings (line breaks and spaces) and as indents, so the engine only edits whitespace and the
 * usual range reformat, caret and fold preservation keep working:
 * - Reformat Code with a complete layout uses [GoSegmentRootBlock]: a flat list of leaf blocks,
 *   one per run of tokens whose whitespace already is gofmt's, so the engine only sees the places
 *   that change;
 * - Auto-Indent Lines and other indent requests, and partial layouts (syntax errors), use the
 *   PSI-shaped [GoBlock] tree, whose child attributes and structural fallback need the structure.
 *
 * On Enter, and where a partial layout has no gap, the block tree falls back to structural
 * indents and the [GoSpacingBuilder] rules.
 */
class GoFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val mode = formattingContext.formattingMode
        // Enter needs no layout (and must stay fast while the code is incomplete); Reformat Code and
        // Auto-Indent Lines use gofmt's indentation
        val layout = if (mode != FormattingMode.ADJUST_INDENT_ON_ENTER) GoLayout.cached(file, settings) else null
        val context = GoBlockContext(settings, layout, file.node)
        // (formatter on/off tags need no block structure: the platform splits the range by text before formatting)
        val root: Block = if (mode == FormattingMode.REFORMAT && layout != null && layout.isComplete && segmentsEnabled) {
            GoSegmentRootBlock(file.node, context)
        } else {
            GoBlock(file.node, Indent.getNoneIndent(), context, 0)
        }
        return GoFormattingModel(file, root, FormattingDocumentModelImpl.createOn(file))
    }

    companion object {
        /** Test hook: false forces the PSI-shaped block tree for Reformat Code (both must give the same result). */
        @Volatile
        internal var segmentsEnabled = true
    }
}

/** Settings and layout shared by all blocks of one formatting model. */
class GoBlockContext(
    val settings: com.intellij.psi.codeStyle.CodeStyleSettings,
    val layout: GoLayout?,
    val fileNode: com.intellij.lang.ASTNode,
) {
    /** The file text when the model was built (all spacings are computed before any change). */
    val text: CharSequence = fileNode.chars

    val common = settings.getCommonSettings(GoLanguage)
    val indentOptions = settings.getIndentOptions(io.github.golangsupport.lang.GoFileType)
    val spacingBuilder by lazy(LazyThreadSafetyMode.NONE) { GoSpacingBuilder.create(settings) }

    /** Indentation width (in columns) of the line a layout gap starts, or -1 if the gap has no line break. */
    fun lineIndentColumns(gap: String): Int {
        val nl = gap.lastIndexOf('\n')
        if (nl < 0) return -1
        val tabSize = indentOptions.TAB_SIZE
        var cols = 0
        for (i in nl + 1 until gap.length) {
            cols += if (gap[i] == '\t') tabSize else 1
        }
        return cols
    }
}
