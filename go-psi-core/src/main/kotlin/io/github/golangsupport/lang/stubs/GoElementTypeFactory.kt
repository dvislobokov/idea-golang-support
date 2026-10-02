package io.github.golangsupport.lang.stubs

import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.parser.GoLazyBlockElementType
import io.github.golangsupport.lang.psi.GoElementType
import io.github.golangsupport.lang.psi.impl.*
import org.jetbrains.annotations.ApiStatus

/**
 * Grammar-Kit `elementTypeFactory` for every composite rule: returns the stub element type for the
 * stubbed rules (docs/GRAMMAR.md section M) and a plain [GoElementType] for the rest. Called once
 * per rule from the static initializer of the generated `GoTypes`; must not touch `GoTypes` itself.
 */
@ApiStatus.Internal
object GoElementTypeFactory {

    @JvmStatic
    fun stubFactory(name: String): IElementType = when (name) {
        // File level
        "PACKAGE_CLAUSE" -> GoNamedStubElementType(name, ::GoPackageClauseStub) { s, t -> GoPackageClauseImpl(s, t) }
        "IMPORT_SPEC" -> GoImportSpecElementType(name)

        // Functions and methods
        "FUNCTION_DECLARATION" -> GoFunctionDeclarationElementType(name)
        "METHOD_DECLARATION" -> GoMethodDeclarationElementType(name)
        "RECEIVER" -> GoNamedStubElementType(name, ::GoReceiverStub) { s, t -> GoReceiverImpl(s, t) }
        "SIGNATURE" -> GoSimpleStubElementType(name, ::GoSignatureStub) { s, t -> GoSignatureImpl(s, t) }
        "PARAMETERS" -> GoSimpleStubElementType(name, ::GoParametersStub) { s, t -> GoParametersImpl(s, t) }
        "RESULT" -> GoSimpleStubElementType(name, ::GoResultStub) { s, t -> GoResultImpl(s, t) }
        "PARAMETER_DECLARATION" -> GoParameterDeclarationElementType(name)
        "PARAM_DEFINITION" -> GoNamedStubElementType(name, ::GoParamDefinitionStub) { s, t -> GoParamDefinitionImpl(s, t) }

        // Type specs and type parameters
        "TYPE_SPEC" -> GoTypeSpecElementType(name)
        "TYPE_PARAMETERS" -> GoSimpleStubElementType(name, ::GoTypeParametersStub) { s, t -> GoTypeParametersImpl(s, t) }
        "TYPE_PARAMETER_DECLARATION" ->
            GoSimpleStubElementType(name, ::GoTypeParameterDeclarationStub) { s, t -> GoTypeParameterDeclarationImpl(s, t) }
        "TYPE_PARAM_DEFINITION" ->
            GoNamedStubElementType(name, ::GoTypeParamDefinitionStub) { s, t -> GoTypeParamDefinitionImpl(s, t) }
        "CONSTRAINT_ELEM" -> GoSimpleStubElementType(name, ::GoConstraintElemStub) { s, t -> GoConstraintElemImpl(s, t) }
        "CONSTRAINT_TERM" -> GoConstraintTermElementType(name)

        // Vars and consts
        "VAR_DECLARATION" -> GoSimpleStubElementType(name, ::GoVarDeclarationStub) { s, t -> GoVarDeclarationImpl(s, t) }
        "VAR_SPEC" -> GoVarSpecElementType(name)
        "VAR_DEFINITION" ->
            GoNamedStubElementType(name, ::GoVarDefinitionStub, ::indexPackageLevelName) { s, t -> GoVarDefinitionImpl(s, t) }
        "CONST_DECLARATION" -> GoSimpleStubElementType(name, ::GoConstDeclarationStub) { s, t -> GoConstDeclarationImpl(s, t) }
        "CONST_SPEC" -> GoConstSpecElementType(name)
        "CONST_DEFINITION" ->
            GoNamedStubElementType(name, ::GoConstDefinitionStub, ::indexPackageLevelName) { s, t -> GoConstDefinitionImpl(s, t) }

        // Structs and interfaces
        "FIELD_DECLARATION" -> GoSimpleStubElementType(name, ::GoFieldDeclarationStub) { s, t -> GoFieldDeclarationImpl(s, t) }
        "FIELD_DEFINITION" -> GoNamedStubElementType(name, ::GoFieldDefinitionStub) { s, t -> GoFieldDefinitionImpl(s, t) }
        "ANONYMOUS_FIELD_DEFINITION" -> GoAnonymousFieldDefinitionElementType(name)
        "TAG" -> GoTagElementType(name)
        "METHOD_SPEC" -> GoMethodSpecElementType(name)

        // Type nodes
        "TYPE" -> GoTypeElementType(name) { s, t -> GoTypeImpl(s, t) }
        "PAR_TYPE" -> GoTypeElementType(name) { s, t -> GoParTypeImpl(s, t) }
        "ARRAY_OR_SLICE_TYPE" -> GoTypeElementType(name) { s, t -> GoArrayOrSliceTypeImpl(s, t) }
        "POINTER_TYPE" -> GoTypeElementType(name) { s, t -> GoPointerTypeImpl(s, t) }
        "FUNCTION_TYPE" -> GoTypeElementType(name) { s, t -> GoFunctionTypeImpl(s, t) }
        "MAP_TYPE" -> GoTypeElementType(name) { s, t -> GoMapTypeImpl(s, t) }
        "CHANNEL_TYPE" -> GoTypeElementType(name) { s, t -> GoChannelTypeImpl(s, t) }
        "STRUCT_TYPE" -> GoTypeElementType(name) { s, t -> GoStructTypeImpl(s, t) }
        "INTERFACE_TYPE" -> GoTypeElementType(name) { s, t -> GoInterfaceTypeImpl(s, t) }
        "TYPE_LIST" -> GoTypeElementType(name) { s, t -> GoTypeListImpl(s, t) }
        "TYPE_REFERENCE_EXPRESSION" -> GoTypeReferenceExpressionElementType(name)
        "TYPE_ARGUMENTS" -> GoSimpleStubElementType(name, ::GoTypeArgumentsStub) { s, t -> GoTypeArgumentsImpl(s, t) }

        // Lazy, reparseable function bodies (docs/GRAMMAR.md section N); nested blocks share the type.
        "BLOCK" -> GoLazyBlockElementType(name)

        else -> GoElementType(name)
    }
}
