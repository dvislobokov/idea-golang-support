package io.github.golangsupport.lang.stubs

import com.intellij.psi.StubBuilder
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.tree.IStubFileElementType
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoBuildConstraint
import io.github.golangsupport.lang.stubs.index.GoPackagesIndex
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class GoFileElementType private constructor() : IStubFileElementType<GoFileStub>("GO_FILE", GoLanguage) {

    override fun getStubVersion(): Int = STUB_VERSION

    override fun getExternalId(): String = "go.FILE"

    override fun getBuilder(): StubBuilder = GoStubBuilder()

    override fun serialize(stub: GoFileStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.packageName)
        dataStream.writeNullableUTF(stub.buildConstraint.goBuild)
        dataStream.writeStrings(stub.buildConstraint.plusBuild)
        dataStream.writeBoolean(stub.isTestFile)
        dataStream.writeBoolean(stub.isCgo)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): GoFileStub {
        val packageName = dataStream.readNameString()
        val constraint = GoBuildConstraint(dataStream.readNullableUTF(), dataStream.readStrings())
        return GoFileStub(null, packageName, constraint, dataStream.readBoolean(), dataStream.readBoolean())
    }

    override fun indexStub(stub: PsiFileStub<*>, sink: IndexSink) {
        val packageName = (stub as? GoFileStub)?.packageName ?: return
        sink.occurrence(GoPackagesIndex.KEY, packageName)
    }

    companion object {
        /**
         * Bump whenever the serialized stub form changes, or the parser builds a different stub tree
         * for some text. 2: Phase 3 stubs. 4: lazy function bodies (an unclosed or broken body no
         * longer leaks its remaining tokens to the top level, where they could form declarations).
         */
        const val STUB_VERSION: Int = 4

        @JvmField
        val INSTANCE: GoFileElementType = GoFileElementType()
    }
}
