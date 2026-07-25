package fsm.sample.machine

import fsm.sample.effect.CrossCuttingEffect
import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.CrossCuttingInterpreter
import fsm.sample.effect.StepResult

/**
 * PURPOSE: Shared dual-sink boundary apply helpers for total, offer, and hierarchical machines.
 *
 * Pure steps only declare outcomes; boundaries call these helpers so assign +
 * [CrossCuttingInterpreter] + domain apply stay consistent and refuse cannot
 * apply domain by accident.
 *
 * ## Two ordering contracts (critical)
 *
 * | Family | Helpers | State assign relative to apply |
 * |--------|---------|--------------------------------|
 * | Total / offer | [applyAccepted], [applyOffer] | **Assign first**, then CC, then domain (if any) |
 * | Hierarchical | [applyFragments] only | **Apply fragments first**, then assign leaf in the caller |
 *
 * Hierarchical machines must **not** use [applyAccepted]: that would assign
 * before exit effects run and would flatten per-fragment CC↔domain pairing.
 * Connection-style boundaries call [applyFragments], then assign when
 * Handled && next !== from.
 */

/**
 * PURPOSE: One ordered hierarchical effect unit (exit, enter, or transition fragment).
 *
 * Within a fragment, cross-cutting applies before domain so LCA pairing is preserved
 * (e.g. LogEntry(Online) immediately before OpenSession).
 *
 * Generic over domain [D]; Connection's non-generic fragment is the same shape
 * and will migrate here in a later PR.
 */
data class EffectFragment<D>(
    val crossCutting: List<CrossCuttingEffect> = emptyList(),
    val domain: List<D> = emptyList(),
)

/**
 * PURPOSE: Ordered hierarchical effect assembly as a list of [EffectFragment]s.
 *
 * Apply via [applyFragments] — never flatten all CC then all domain, and never
 * route through [applyAccepted].
 */
data class FragmentedStepEffects<D>(
    val fragments: List<EffectFragment<D>> = emptyList(),
)

/**
 * PURPOSE: Temporary pure offer outcome for [applyOffer] until shared
 * `OfferOutcome` lands in `fsm.sample.effect` (later PR).
 *
 * - [Accepted]: full dual-sink (crossCutting + domain)
 * - [Refused]: cross-cutting only — domain is structurally absent
 *
 * Field shapes match the intended OfferOutcome so call sites can switch later.
 */
sealed class OfferBoundaryResult<out S, out D> {
    abstract val next: S
    abstract val crossCutting: List<CrossCuttingEffect>

    data class Accepted<S, D>(
        override val next: S,
        override val crossCutting: List<CrossCuttingEffect> = emptyList(),
        val domain: List<D> = emptyList(),
    ) : OfferBoundaryResult<S, D>()

    data class Refused<S>(
        override val next: S,
        override val crossCutting: List<CrossCuttingEffect> = emptyList(),
        val reason: String = "",
    ) : OfferBoundaryResult<S, Nothing>()
}

/**
 * PURPOSE: Total / accepted step boundary — assign next, then CC, then domain.
 *
 * Used by flat total machines and orthogonal region handles (TrafficLight, Latch, Swing).
 * Order is fixed: assign → [CrossCuttingInterpreter.apply] → applyDomain.
 */
fun <S, D, P> applyAccepted(
    result: StepResult<S, D>,
    env: CrossCuttingEnv,
    port: P,
    applyDomain: (List<D>, P) -> Unit,
    assign: (S) -> Unit,
) {
    assign(result.next)
    CrossCuttingInterpreter.apply(result.crossCutting, env)
    applyDomain(result.domain, port)
}

/**
 * PURPOSE: Partial offer boundary — domain only on [OfferBoundaryResult.Accepted].
 *
 * Assign and cross-cutting always run; refuse never reaches [applyDomain]
 * (domain is unrepresentable on [OfferBoundaryResult.Refused]).
 */
fun <S, D, P> applyOffer(
    result: OfferBoundaryResult<S, D>,
    env: CrossCuttingEnv,
    port: P,
    applyDomain: (List<D>, P) -> Unit,
    assign: (S) -> Unit,
) {
    assign(result.next)
    CrossCuttingInterpreter.apply(result.crossCutting, env)
    when (result) {
        is OfferBoundaryResult.Accepted -> applyDomain(result.domain, port)
        is OfferBoundaryResult.Refused -> { /* domain forbidden */ }
    }
}

/**
 * PURPOSE: Hierarchical fragment apply — per-fragment CC then domain (LCA pairing).
 *
 * Does **not** assign state; the Connection-style caller assigns the leaf after
 * fragments apply when Handled && next !== from.
 */
fun <D, P> applyFragments(
    effects: FragmentedStepEffects<D>,
    env: CrossCuttingEnv,
    port: P,
    applyDomain: (List<D>, P) -> Unit,
) {
    applyFragments(effects.fragments, env, port, applyDomain)
}

/**
 * PURPOSE: Hierarchical fragment apply over an ordered fragment list.
 *
 * For each fragment: cross-cutting, then domain. Preserves exit-while-still-in-old-state
 * semantics when the caller assigns only after this returns.
 */
fun <D, P> applyFragments(
    fragments: List<EffectFragment<D>>,
    env: CrossCuttingEnv,
    port: P,
    applyDomain: (List<D>, P) -> Unit,
) {
    for (fragment in fragments) {
        CrossCuttingInterpreter.apply(fragment.crossCutting, env)
        applyDomain(fragment.domain, port)
    }
}
