package fsm.sample.effect

import fsm.sample.machine.FragmentedStepEffects

/**
 * PURPOSE: Hierarchical pure product/runtime fuse shape — peer of [StepResult] / [OfferOutcome].
 *
 * Single sealed result for leaf × event after vignette dispatch + declared-effect assembly:
 * - [Handled] carries next leaf + fragmented effects (cross-cutting + domain)
 * - [Unhandled] carries refuse-package fragments only (no next; export treats as self-successor)
 *
 * Pure steps only declare; hierarchical boundaries [applyFragments] then assign if the leaf
 * changed. Export [fsm.sample.export.productEvaluateHierarchy] consumes this shape directly.
 * Kind matrix: project README.
 */
sealed class HierarchyStep<S, D> {
    abstract val effects: FragmentedStepEffects<D>

    data class Handled<S, D>(
        val next: S,
        override val effects: FragmentedStepEffects<D>,
    ) : HierarchyStep<S, D>()

    data class Unhandled<S, D>(
        override val effects: FragmentedStepEffects<D>,
    ) : HierarchyStep<S, D>()
}
