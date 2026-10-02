package io.github.golangsupport.lang.stubs

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.NamedStubBase
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement
import io.github.golangsupport.lang.psi.*

// Stub classes for everything outside function bodies (docs/GRAMMAR.md section M). Data-less stubs
// only record structure; expressions are kept as text in the stub of the enclosing spec.

private typealias Parent = StubElement<*>?
private typealias Type = IStubElementType<*, *>

/** Base of the stubs without data; [toString] is the simple class name so stub dumps stay readable. */
abstract class GoStubBase<T : PsiElement>(parent: Parent, type: Type) : StubBase<T>(parent, type) {
    override fun toString(): String = javaClass.simpleName
}

/** Base of the named stubs. */
abstract class GoNamedStub<T : GoNamedElement>(parent: Parent, type: Type, name: String?) :
    NamedStubBase<T>(parent, type, name) {
    val isPublic: Boolean get() = isExportedName(name)

    /** Extra data for [toString]; empty for a plain named stub. */
    protected open fun details(): String = ""

    override fun toString(): String {
        val details = details()
        return "${javaClass.simpleName}(name=$name${if (details.isEmpty()) "" else ", $details"})"
    }
}

internal fun isExportedName(name: String?): Boolean =
    !name.isNullOrEmpty() && Character.isUpperCase(name.codePointAt(0))

// --- File level -----------------------------------------------------------------------------------

class GoPackageClauseStub(parent: Parent, type: Type, name: String?) : GoNamedStub<GoPackageClause>(parent, type, name)

/** [name] is derived: the alias, or the last path segment. */
class GoImportSpecStub(parent: Parent, type: Type, val path: String, val alias: String?) :
    GoNamedStub<GoImportSpec>(parent, type, alias ?: path.substringAfterLast('/')) {
    val isDot: Boolean get() = alias == "."
    val isBlank: Boolean get() = alias == "_"

    override fun details(): String = "path=$path, alias=$alias"
}

// --- Functions and methods --------------------------------------------------------------------------

/** [arity]: number of parameters, used by the fingerprint indices (`name/arity`). */
abstract class GoFunctionOrMethodDeclarationStub<T : GoFunctionOrMethodDeclaration>(
    parent: Parent, type: Type, name: String?, val arity: Int,
) : GoNamedStub<T>(parent, type, name) {
    override fun details(): String = "arity=$arity"
}

class GoFunctionDeclarationStub(parent: Parent, type: Type, name: String?, arity: Int) :
    GoFunctionOrMethodDeclarationStub<GoFunctionDeclaration>(parent, type, name, arity)

class GoMethodDeclarationStub(
    parent: Parent, type: Type, name: String?, arity: Int,
    val receiverTypeName: String?, val isPointerReceiver: Boolean,
) : GoFunctionOrMethodDeclarationStub<GoMethodDeclaration>(parent, type, name, arity) {
    override fun details(): String = "arity=$arity, receiver=${if (isPointerReceiver) "*" else ""}$receiverTypeName"
}

class GoReceiverStub(parent: Parent, type: Type, name: String?) : GoNamedStub<GoReceiver>(parent, type, name)

class GoSignatureStub(parent: Parent, type: Type) : GoStubBase<GoSignature>(parent, type)

class GoParametersStub(parent: Parent, type: Type) : GoStubBase<GoParameters>(parent, type)

class GoResultStub(parent: Parent, type: Type) : GoStubBase<GoResult>(parent, type)

class GoParameterDeclarationStub(parent: Parent, type: Type, val isVariadic: Boolean) :
    GoStubBase<GoParameterDeclaration>(parent, type) {
    override fun toString(): String = "${javaClass.simpleName}(variadic=$isVariadic)"
}

class GoParamDefinitionStub(parent: Parent, type: Type, name: String?) : GoNamedStub<GoParamDefinition>(parent, type, name)

// --- Types and type parameters ---------------------------------------------------------------------

class GoTypeSpecStub(parent: Parent, type: Type, name: String?, val isAlias: Boolean, val hasTypeParameters: Boolean) :
    GoNamedStub<GoTypeSpec>(parent, type, name) {
    override fun details(): String = "alias=$isAlias, generic=$hasTypeParameters"
}

class GoTypeParametersStub(parent: Parent, type: Type) : GoStubBase<GoTypeParameters>(parent, type)

class GoTypeParameterDeclarationStub(parent: Parent, type: Type) : GoStubBase<GoTypeParameterDeclaration>(parent, type)

class GoTypeParamDefinitionStub(parent: Parent, type: Type, name: String?) :
    GoNamedStub<GoTypeParamDefinition>(parent, type, name)

class GoConstraintElemStub(parent: Parent, type: Type) : GoStubBase<GoConstraintElem>(parent, type)

class GoConstraintTermStub(parent: Parent, type: Type, val hasTilde: Boolean) : GoStubBase<GoConstraintTerm>(parent, type) {
    override fun toString(): String = "${javaClass.simpleName}(tilde=$hasTilde)"
}

/**
 * Stub of every type node (`TYPE`, `POINTER_TYPE`, `ARRAY_OR_SLICE_TYPE`, ...). [detail] holds the
 * node-specific text: the array length expression (`"..."` for `[...]T`, `null` for slices) or the
 * channel direction (`chan`, `<-chan`, `chan<-`); `null` for the other kinds.
 */
class GoTypeStub(parent: Parent, type: Type, val detail: String?) : GoStubBase<GoType>(parent, type) {
    override fun toString(): String = if (detail == null) javaClass.simpleName else "${javaClass.simpleName}($detail)"
}

/** `T` or `pkg.T`: [name] is the type name, [qualifier] the package name. */
class GoTypeReferenceExpressionStub(parent: Parent, type: Type, val name: String, val qualifier: String?) :
    GoStubBase<GoTypeReferenceExpression>(parent, type) {
    val qualifiedText: String get() = if (qualifier == null) name else "$qualifier.$name"

    override fun toString(): String = "${javaClass.simpleName}($qualifiedText)"
}

class GoTypeArgumentsStub(parent: Parent, type: Type) : GoStubBase<GoTypeArguments>(parent, type)

// --- Vars and consts --------------------------------------------------------------------------------

class GoVarDeclarationStub(parent: Parent, type: Type) : GoStubBase<GoVarDeclaration>(parent, type)

/** [values]: the text of each initializer expression. */
class GoVarSpecStub(parent: Parent, type: Type, val values: List<String>) : GoStubBase<GoVarSpec>(parent, type) {
    override fun toString(): String = "${javaClass.simpleName}(values=$values)"
}

class GoVarDefinitionStub(parent: Parent, type: Type, name: String?) : GoNamedStub<GoVarDefinition>(parent, type, name)

class GoConstDeclarationStub(parent: Parent, type: Type) : GoStubBase<GoConstDeclaration>(parent, type)

/**
 * [iota]: index of the spec within its declaration group (the value of `iota` in it); [values]: the
 * text of each expression, empty for an implicitly repeated spec.
 */
class GoConstSpecStub(parent: Parent, type: Type, val iota: Int, val values: List<String>) :
    GoStubBase<GoConstSpec>(parent, type) {
    override fun toString(): String = "${javaClass.simpleName}(iota=$iota, values=$values)"
}

class GoConstDefinitionStub(parent: Parent, type: Type, name: String?) : GoNamedStub<GoConstDefinition>(parent, type, name)

// --- Structs and interfaces -------------------------------------------------------------------------

class GoFieldDeclarationStub(parent: Parent, type: Type) : GoStubBase<GoFieldDeclaration>(parent, type)

class GoFieldDefinitionStub(parent: Parent, type: Type, name: String?) : GoNamedStub<GoFieldDefinition>(parent, type, name)

/** Embedded field; [name] is the last identifier of the embedded type. */
class GoAnonymousFieldDefinitionStub(parent: Parent, type: Type, name: String?, val isPointer: Boolean) :
    GoNamedStub<GoAnonymousFieldDefinition>(parent, type, name) {
    override fun details(): String = "pointer=$isPointer"
}

/** [text]: the raw tag literal including quotes. */
class GoTagStub(parent: Parent, type: Type, val text: String) : GoStubBase<GoTag>(parent, type) {
    override fun toString(): String = "${javaClass.simpleName}($text)"
}

class GoMethodSpecStub(parent: Parent, type: Type, name: String?, val arity: Int) :
    GoNamedStub<GoMethodSpec>(parent, type, name) {
    override fun details(): String = "arity=$arity"
}
