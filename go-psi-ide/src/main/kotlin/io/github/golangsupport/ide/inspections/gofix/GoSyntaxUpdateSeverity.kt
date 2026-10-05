package io.github.golangsupport.ide.inspections.gofix

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.SeveritiesProvider
import com.intellij.lang.annotation.HighlightSeverity
import io.github.golangsupport.lang.GoColors

/**
 * The level of the "Go fix" (modernizer) inspections, GoLand's `SYNTAX_UPDATE`: below a weak warning, above information, coloured by
 * [GoColors.SYNTAX_UPDATE]. The name is GoLand's own (its inspection profiles store `level="SYNTAX_UPDATE"`), so a profile exported from
 * GoLand keeps its levels here, and an inspection declares it as `level="SYNTAX_UPDATE"` in its `localInspection` tag: the platform looks
 * the level up by the severity's name ([HighlightDisplayLevel.find]) once the [SeveritiesProvider]s are registered.
 */
object GoSyntaxUpdateSeverity {
    const val NAME = "SYNTAX_UPDATE"

    /** Between SERVER PROBLEM (100) and WEAK WARNING (200): sorted under the weak warnings in the problems views and the inspection settings. */
    val SEVERITY = HighlightSeverity(
        NAME, (HighlightSeverity.GENERIC_SERVER_ERROR_OR_WARNING.myVal + HighlightSeverity.WEAK_WARNING.myVal) / 2,
        { "Syntax update" }, { "Syntax Update" }, { "{0} {0,choice,0#syntax updates|1#syntax update|2#syntax updates}" },
    )

    val INFO_TYPE: HighlightInfoType = HighlightInfoType.HighlightInfoTypeImpl(SEVERITY, GoColors.SYNTAX_UPDATE)

    fun level(): HighlightDisplayLevel? = HighlightDisplayLevel.find(SEVERITY)
}

/** Registers [GoSyntaxUpdateSeverity] with the severity registrar: Settings | Editor | Inspections lists it, and `level="SYNTAX_UPDATE"` resolves. */
class GoSyntaxUpdateSeveritiesProvider : SeveritiesProvider() {
    override fun getSeveritiesHighlightInfoTypes(): List<HighlightInfoType> = listOf(GoSyntaxUpdateSeverity.INFO_TYPE)
}
