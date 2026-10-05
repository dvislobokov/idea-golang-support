package io.github.golangsupport.ide.hints

import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.documentation.GoDocSignature
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoValue
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.lang.psi.GoStructType as PsiStructType

/**
 * The inlay hints of a Go file over the PSI (Settings | Editor | Inlay Hints | Go): the hint set of gopls, one provider per group of the
 * settings tree. The providers of the gopls set ask [GoIdeFeatureGate] ([GoIdeFeature.INLAY_HINTS]) and collect nothing when it is
 * off (the host then lets gopls show its hints); the struct size hint has no gopls counterpart and is not gated.
 *
 * Cost: the platform runs the collector in the background under a read action, element by element. Every type comes from
 * [GoSemanticService] / [GoExpressionTyper], whose results are cached per function body (`GoBodyCache`): a body is inferred once,
 * the hints of its `:=` read that cache, an edit in one function leaves the others cached. Nothing here loads the AST of another file
 * beyond what the type checker does.
 */
abstract class GoHintsProvider(private val gated: Boolean = true) : InlayHintsProvider {
    final override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is GoFile) return null
        if (gated && !GoIdeFeatureGate.enabled(GoIdeFeature.INLAY_HINTS, file.project)) return null
        return collector(file, editor)
    }

    protected abstract fun collector(file: GoFile, editor: Editor): InlayHintsCollector?

    companion object {
        fun inline(sink: InlayTreeSink, offset: Int, text: String, relatedToPrevious: Boolean, tooltip: String? = null) =
            add(sink, InlineInlayPosition(offset, relatedToPrevious), text, tooltip)

        fun add(sink: InlayTreeSink, position: InlayPosition, text: String, tooltip: String? = null) =
            sink.addPresentation(position, null, tooltip, HintFormat.default) { text(text) }
    }
}

/**
 * `f(/*name:*/ 1)`: the names of the parameters at the literal arguments of a call, with the rules of [GoInlayHints.parameterLabel]; with
 * the option [RETURN] (GoLand's "Show return parameters") also the names of named results at the literal values of `return`:
 * `return /*n:*/ 0, /*err:*/ nil`.
 */
class GoParameterNameHintsProvider : GoHintsProvider() {
    override fun collector(file: GoFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        private val semantic = GoSemanticService.getInstance(file.project)

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            when (element) {
                is GoCallExpr -> arguments(element, sink)
                is GoReturnStatement -> sink.whenOptionEnabled(RETURN) { results(element, sink) }
            }
        }

        private fun arguments(call: GoCallExpr, sink: InlayTreeSink) {
            val arguments = call.arguments
            // the signature is resolved only for a call with a literal argument: most calls have none
            if (arguments.none(::isLiteral)) return
            val signature = semantic.calleeSignature(call) ?: return
            val params = signature.params
            if (params.isEmpty()) return
            val callee = calleeName(call.expression)
            val last = params.lastIndex
            for ((i, argument) in arguments.withIndex()) {
                if (i > last) break
                if (!isLiteral(argument)) continue
                val variadic = signature.variadic && i == last
                val label = GoInlayHints.parameterLabel(params[i].name, callee, params.size, variadic) ?: continue
                inline(sink, argument.textRange.startOffset, label, relatedToPrevious = false)
            }
        }

        /** The values of `return` against the named results of the function or literal around it; nothing for `return f()` of a tuple. */
        private fun results(statement: GoReturnStatement, sink: InlayTreeSink) {
            val values = statement.expressionList
            if (values.none(::isLiteral)) return
            val signature = when (val owner = PsiTreeUtil.getParentOfType(statement, GoFunctionLit::class.java, GoFunctionDeclaration::class.java, GoMethodDeclaration::class.java)) {
                is GoFunctionLit -> owner.signature
                is GoFunctionDeclaration -> owner.signature
                is GoMethodDeclaration -> owner.signature
                else -> null
            } ?: return
            val names = signature.result?.parameters?.parameterDeclarationList?.flatMap { d -> d.paramDefinitionList.map { it.name } } ?: return
            if (names.size != values.size) return
            for ((i, value) in values.withIndex()) {
                if (!isLiteral(value)) continue
                inline(sink, value.textRange.startOffset, GoInlayHints.resultLabel(names[i]) ?: continue, relatedToPrevious = false)
            }
        }
    }

    private fun calleeName(callee: GoExpression?): String? = when (callee) {
        is GoReferenceExpression -> callee.identifier?.text
        is GoIndexOrSliceExpr -> calleeName(callee.expression)
        is GoParenthesesExpr -> calleeName(callee.inner as? GoExpression)
        else -> null
    }

    companion object {
        const val RETURN = "return"

        /** A basic literal, a signed number, or `nil` / `true` / `false`: the arguments GoLand puts a parameter hint at (seen live, docs/goland-analysis). */
        fun isLiteral(element: PsiElement): Boolean = when (element) {
            is GoLiteral, is GoStringLiteral -> true
            is GoUnaryExpr -> (element.sub != null || element.add != null) && element.expression is GoLiteral
            is GoReferenceExpression -> element.text in GoInlayHints.LITERAL_NAMES
            else -> false
        }
    }
}

/** `T{/*Name:*/ v}`: the field names at the elements of a struct literal written without keys (gopls `compositeLiteralFields`). */
class GoLiteralFieldHintsProvider : GoHintsProvider() {
    override fun collector(file: GoFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        private val typer = GoExpressionTyper.getInstance(file.project)

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val value = element as? GoLiteralValue ?: return
            val elements = value.elements
            if (elements.isEmpty() || elements.any { it.key != null }) return
            val struct = typer.typeOfLiteralValue(value)?.let(::structOf) ?: return
            for ((i, item) in elements.withIndex()) {
                val field = struct.fields.getOrNull(i) ?: break
                inline(sink, item.textRange.startOffset, field.name + ":", relatedToPrevious = false)
            }
        }
    }

    private fun structOf(type: GoType): GoStructType? = when (val u = type.underlying()) {
        is GoStructType -> u
        is GoPointerType -> u.elem.underlying() as? GoStructType
        else -> null
    }
}

/**
 * The types the code leaves out: `x/*: T*/ := …` and the variables of `for … range` (options `assign`, `range`, gopls `assignVariableTypes`
 * and `rangeVariableTypes`), the type of a nested literal whose type is elided (`literal`, `compositeLiteralTypes`), the inferred type
 * arguments of a call of a generic function (`instantiation`, `functionTypeParameters`).
 */
class GoTypeHintsProvider : GoHintsProvider() {
    override fun collector(file: GoFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        private val semantic = GoSemanticService.getInstance(file.project)
        private val typer = GoExpressionTyper.getInstance(file.project)
        private val types = GoHintTypes(file)

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            when (element) {
                is GoShortVarDeclaration -> sink.whenOptionEnabled(ASSIGN) { variables(element.varDefinitionList, sink) }
                is GoRangeClause -> if (element.define != null) sink.whenOptionEnabled(RANGE) { variables(element.varDefinitionList, sink) }
                is GoLiteralValue -> sink.whenOptionEnabled(LITERAL) { literalType(element, sink) }
                is GoCallExpr -> sink.whenOptionEnabled(INSTANTIATION) { typeArguments(element, sink) }
            }
        }

        private fun variables(definitions: List<GoVarDefinition>, sink: InlayTreeSink) {
            for (definition in definitions) {
                if (definition.name == null || definition.name == "_") continue
                val text = types.render(semantic.declarationType(definition)) ?: continue
                inline(sink, definition.textRange.endOffset, ": $text", relatedToPrevious = true)
            }
        }

        /** An elided literal (`{1, 2}` in `[]Point{{1, 2}}`, a key of a map literal): the element type of the literal around it, `&T` for pointers. */
        private fun literalType(value: GoLiteralValue, sink: InlayTreeSink) {
            val holder = value.parent
            if (holder !is GoValue && holder !is GoKey) return
            val outer = ((holder.parent as? GoElement)?.parent as? GoLiteralValue)?.let(typer::typeOfLiteralValue) ?: return
            val underlying = ((outer as? GoTypeParamType)?.coreType ?: outer).underlying()
            val elementType = when {
                holder is GoKey -> (underlying as? GoMapType)?.key
                else -> when (underlying) {
                    is GoSliceType -> underlying.elem
                    is GoArrayType -> underlying.elem
                    is GoMapType -> underlying.value
                    else -> null
                }
            } ?: return
            val text = if (elementType is GoPointerType) types.render(elementType.elem)?.let { "&$it" } else types.render(elementType)
            inline(sink, value.textRange.startOffset, text ?: return, relatedToPrevious = false)
        }

        /** `f/*[int]*/(x)`: the type arguments inference gave a generic function; nothing when they are written out (`f[int](x)`). */
        private fun typeArguments(call: GoCallExpr, sink: InlayTreeSink) {
            val callee = call.expression ?: return
            val arguments = call.argumentList ?: return
            val generic = semantic.typeOf(callee) as? GoSignatureType ?: return
            if (!generic.isGeneric) return
            val instance = semantic.calleeSignature(call) ?: return
            val texts = generic.typeParams.map { param -> instance.partialSubst[param]?.let(types::render) ?: return }
            inline(sink, arguments.textRange.startOffset, texts.joinToString(", ", "[", "]"), relatedToPrevious = true)
        }
    }

    companion object {
        const val ASSIGN = "assign"
        const val RANGE = "range"
        const val LITERAL = "literal"
        const val INSTANTIATION = "instantiation"
    }
}

/**
 * `const B /*= 1*/`: the values of constants whose source does not show them — an `iota` row, a repeated row, an expression
 * (gopls `constantValues`: a spec of literals or of `true`/`false` gets nothing).
 */
class GoConstantValueHintsProvider : GoHintsProvider() {
    override fun collector(file: GoFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        private val typer = GoExpressionTyper.getInstance(file.project)

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val spec = element as? GoConstSpec ?: return
            val definitions = spec.constDefinitionList
            if (definitions.isEmpty()) return
            val expressions = spec.expressionList
            var show = expressions.isEmpty()
            val checkValues = definitions.size == expressions.size
            val values = definitions.mapIndexed { i, definition ->
                val value = typer.constantValueOf(definition) ?: return
                if (checkValues && !isBasicLiteral(expressions[i]) && value !is GoConstant.Bool) show = true
                value
            }
            if (!show) return
            val text = GoInlayHints.constantsText(values)
            inline(sink, spec.textRange.endOffset, text, relatedToPrevious = true, tooltip = values.joinToString(", ") { GoDocSignature.constantText(it) }.takeIf { it.length + 2 > text.length })
        }
    }

    private fun isBasicLiteral(expression: GoExpression): Boolean = expression is GoLiteral || expression is GoStringLiteral
}

/**
 * `type T struct { /*24 bytes, 8 padding (16 if reordered)*/`: the size of a struct type as gc lays it out, its padding, and the size
 * the Reorder Fields intention of the host would reach. Only where [GoInlayHints.ARCH_64] holds for the GOARCH of the build (the sizes of
 * [io.github.golangsupport.semantic.infer.GoSizes] are those of 64-bit targets). No gopls counterpart: not gated.
 */
class GoStructSizeHintsProvider : GoHintsProvider(gated = false) {
    override fun collector(file: GoFile, editor: Editor): InlayHintsCollector? {
        val toolchain = GoToolchainProvider.getInstance().toolchainFor(file.project)
        if (toolchain != null && toolchain.goarch !in GoInlayHints.ARCH_64) return null
        val target = toolchain?.let { "${it.goos}/${it.goarch}" } ?: "64-bit"
        val document = editor.document
        val semantic = GoSemanticService.getInstance(file.project)
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                val spec = element as? GoTypeSpec ?: return
                val struct = spec.type as? PsiStructType ?: return
                if (spec.typeParameters != null) return
                val type = semantic.declarationType(spec).underlying() as? GoStructType ?: return
                val layout = GoInlayHints.structLayout(type) ?: return
                val offset = struct.lbrace?.textRange?.startOffset ?: return
                if (offset > document.textLength) return
                val tooltip = "On $target: ${layout.size} bytes, ${layout.padding} of them padding" +
                    if (layout.optimalSize < layout.size) "; ${layout.optimalSize} bytes with the fields reordered (Alt+Enter, Reorder fields)" else ""
                add(sink, EndOfLinePosition(document.getLineNumber(offset)), layout.text, tooltip)
            }
        }
    }
}
