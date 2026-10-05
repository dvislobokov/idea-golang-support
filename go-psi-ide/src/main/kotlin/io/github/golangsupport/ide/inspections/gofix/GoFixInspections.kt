package io.github.golangsupport.ide.inspections.gofix

/** The registered Go fix inspections, in PLAN.md order: `GoFixProbeTest` runs all of them, so every new Go fix inspection is added here. */
object GoFixInspections {
    val ALL: List<Class<out GoFixInspectionBase>> = listOf(
        GoFixAnyInspection::class.java,
        GoFixMinMaxInspection::class.java,
        GoFixRangeIntInspection::class.java,
        GoFixForVarInspection::class.java,
        GoFixSlicesContainsInspection::class.java,
        GoFixSlicesSortInspection::class.java,
        GoFixSlicesBackwardInspection::class.java,
        GoFixStringsCutInspection::class.java,
        GoFixStringsCutPrefixInspection::class.java,
        GoFixMapsLoopInspection::class.java,
    )
}
