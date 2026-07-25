package fsm.sample.machine

import fsm.sample.effect.CrossCuttingEffect

/**
 * PURPOSE: One ordered hierarchical effect unit (exit, enter, transition, or handler).
 *
 * Within a fragment, cross-cutting labels sit before domain labels so LCA pairing is
 * preserved in both export and [applyFragments] (e.g. `Audit.LogEntry(Online)`
 * immediately before `OpenSession`).
 *
 * Generic over domain [D] — Connection uses `D = ConnectionEffect`.
 */
data class EffectFragment<D>(
    val crossCutting: List<CrossCuttingEffect> = emptyList(),
    val domain: List<D> = emptyList(),
)

/**
 * PURPOSE: Ordered hierarchical effect assembly as a list of [EffectFragment]s.
 *
 * Apply via [applyFragments] — never flatten all CC then all domain, and never
 * route through [applyAccepted]. Connection uses this type end-to-end (Option C)
 * with a vignette-local parameterless [exportLabels] extension.
 */
data class FragmentedStepEffects<D>(
    val fragments: List<EffectFragment<D>> = emptyList(),
) {
    /** Flattened audit (tests / inspection); apply uses [fragments] in order. */
    val crossCutting: List<CrossCuttingEffect> get() = fragments.flatMap { it.crossCutting }

    /** Flattened domain (tests / inspection); apply uses [fragments] in order. */
    val domain: List<D> get() = fragments.flatMap { it.domain }

    /**
     * PURPOSE: Export labels walk fragments in LCA order; within each fragment
     * audit labels then domain labels.
     */
    fun exportLabels(domainLabel: (D) -> String): List<String> =
        fragments.flatMap { f ->
            f.crossCutting.map { it.exportLabel() } + f.domain.map(domainLabel)
        }

    operator fun plus(other: FragmentedStepEffects<D>): FragmentedStepEffects<D> =
        FragmentedStepEffects(fragments + other.fragments)

    companion object {
        /** Single-fragment assembly helper (empty lists yield no fragment). */
        fun <D> of(
            crossCutting: List<CrossCuttingEffect> = emptyList(),
            domain: List<D> = emptyList(),
        ): FragmentedStepEffects<D> =
            if (crossCutting.isEmpty() && domain.isEmpty()) {
                FragmentedStepEffects()
            } else {
                FragmentedStepEffects(listOf(EffectFragment(crossCutting, domain)))
            }
    }
}
