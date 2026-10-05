package io.github.golangsupport.ide.inspections.gofix

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.SeveritiesProvider
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import io.github.golangsupport.lang.GoColors
import javax.swing.Icon

/**
 * The level of the "Go fix" (modernizer) inspections, GoLand's `SYNTAX_UPDATE`: just above information, coloured by
 * [GoColors.SYNTAX_UPDATE]. The name is GoLand's own (its inspection profiles store `level="SYNTAX_UPDATE"`), so a profile exported from
 * GoLand keeps its levels here, and an inspection declares it as `level="SYNTAX_UPDATE"` in its `localInspection` tag: the platform looks
 * the level up by the severity's name ([HighlightDisplayLevel.find]) once the [SeveritiesProvider]s are registered.
 */
object GoSyntaxUpdateSeverity {
    const val NAME = "SYNTAX_UPDATE"

    /** 20, as GoLand's (seen live): above INFORMATION (10) and TEXT ATTRIBUTES (11), below WEAK WARNING (200). */
    const val VALUE = 20

    val SEVERITY = HighlightSeverity(
        NAME, VALUE, { "Syntax update" }, { "Syntax Update" }, { "{0} {0,choice,0#syntax updates|1#syntax update|2#syntax updates}" },
    )

    /** The icon of the level in the inspections widget at the top of the editor and in the profile (GoLand shows a refresh arrow there). */
    val ICON: Icon get() = AllIcons.Actions.Refresh

    /** [HighlightInfoType.Iconable]: the severity registrar gives the level this icon instead of a coloured square. */
    val INFO_TYPE: HighlightInfoType = object : HighlightInfoType.HighlightInfoTypeImpl(SEVERITY, GoColors.SYNTAX_UPDATE), HighlightInfoType.Iconable {
        override fun getIcon(): Icon = ICON
    }

    fun level(): HighlightDisplayLevel? = HighlightDisplayLevel.find(SEVERITY)
}

/** Registers [GoSyntaxUpdateSeverity] with the severity registrar: Settings | Editor | Inspections lists it, and `level="SYNTAX_UPDATE"` resolves. */
class GoSyntaxUpdateSeveritiesProvider : SeveritiesProvider() {
    override fun getSeveritiesHighlightInfoTypes(): List<HighlightInfoType> = listOf(GoSyntaxUpdateSeverity.INFO_TYPE)
}
