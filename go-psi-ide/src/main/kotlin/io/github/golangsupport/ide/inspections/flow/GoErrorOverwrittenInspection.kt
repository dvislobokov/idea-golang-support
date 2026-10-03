package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoLiveness

/**
 * An `error` variable gets the result of a call that nothing ever reads, and the result of another call replaces it: the first
 * error is lost (`_, err = w.Write(a); _, err = w.Write(b)`). An error read on some path is not reported (checking another result
 * first, `if n == 0 { return err }`, is the io.Reader idiom). See [GoFlowChecks.errorOverwrites] for the writes that count;
 * escaping variables (captured, address taken) are skipped.
 */
class GoErrorOverwrittenInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val liveness = GoLiveness.of(flow) ?: return
        for (access in flow.accesses) {
            if (GoFlowChecks.errorOverwrites(flow, liveness, access).isEmpty()) continue
            val name = access.variable.name ?: continue
            holder.registerProblem(access.element, "$name is overwritten before being checked")
        }
    }
}
