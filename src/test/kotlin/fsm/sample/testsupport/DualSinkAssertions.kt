package fsm.sample.testsupport

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.OfferOutcome
import fsm.sample.effect.StepResult
import fsm.sample.machine.applyAccepted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * PURPOSE: Small shared dual-sink / purity assertions for machine tests.
 *
 * Prefer these over one-off order probes when checking refuse-without-domain
 * or assign→CC→domain ordering for [applyAccepted]. Not a full test base class —
 * keep helpers tiny and call from sample tests.
 *
 * See README "Machine kinds" / "Two boundary families" and
 * docs/authoring-guide.md.
 */

/**
 * PURPOSE: Refuse pure outcome must not declare domain I/O.
 *
 * [OfferOutcome.Refused] has no domain field; this also covers empty domain lists
 * when callers only have a [StepResult]-shaped snapshot or port probe.
 */
fun assertRefuseHasNoDomain(domain: Collection<*>, message: String = "refuse must not declare domain") {
    assertTrue(domain.isEmpty(), message)
}

/**
 * PURPOSE: [OfferOutcome.Refused] structural purity — no domain field to read;
 * assert successor/crossCutting present and document the contract for agents.
 */
fun <S, D> assertRefusedOutcomeHasNoDomain(outcome: OfferOutcome<S, D>) {
    assertTrue(
        outcome is OfferOutcome.Refused,
        "expected OfferOutcome.Refused, got ${outcome::class.simpleName}",
    )
    // Domain is unrepresentable on Refused; only Accepted exposes domain.
    when (outcome) {
        is OfferOutcome.Accepted -> error("unreachable: Accepted cannot be Refused")
        is OfferOutcome.Refused -> {
            // Port / domain apply must not run — callers still check ports separately.
            assertTrue(outcome.crossCutting.isNotEmpty() || outcome.reason.isNotEmpty())
        }
    }
}

/**
 * PURPOSE: Prove [applyAccepted] order is assign → cross-cutting → domain.
 *
 * [applyDomain] is wrapped so probes can observe env after CC has run.
 * Returns the ordered phase labels: `"assign"`, `"cc"`, `"domain"`.
 */
fun <S, D, P> captureApplyAcceptedOrder(
    result: StepResult<S, D>,
    env: CrossCuttingEnv,
    port: P,
    applyDomain: (List<D>, P) -> Unit,
    assign: (S) -> Unit,
    /** Probe after assign, before/alongside CC — returns true if CC already applied. */
    crossCuttingApplied: (CrossCuttingEnv) -> Boolean,
): List<String> {
    val order = mutableListOf<String>()
    applyAccepted(
        result = result,
        env = env,
        port = port,
        applyDomain = { domain, p ->
            order += if (crossCuttingApplied(env)) "cc" else "cc-missing"
            order += "domain"
            applyDomain(domain, p)
        },
        assign = {
            order += "assign"
            assign(it)
        },
    )
    return order
}

/**
 * PURPOSE: Assert total-family dual-sink order for [applyAccepted].
 */
fun assertApplyAcceptedDualSinkOrder(order: List<String>) {
    assertEquals(
        listOf("assign", "cc", "domain"),
        order,
        "applyAccepted must be assign → CC → domain; got $order",
    )
}

/**
 * PURPOSE: After a dual-sink boundary apply with domain, both sinks show work.
 */
fun assertDualSinksApplied(
    env: CrossCuttingEnv,
    domainPortNonEmpty: Boolean,
    expectCrossCuttingActivity: Boolean = true,
) {
    if (expectCrossCuttingActivity) {
        val logBusy = env.log.snapshot().isNotEmpty()
        val metricsBusy = env.metrics.snapshot().values.any { it > 0 }
        val tracesBusy = env.traces.spans.isNotEmpty()
        assertTrue(
            logBusy || metricsBusy || tracesBusy,
            "expected cross-cutting activity on env after dual-sink apply",
        )
    }
    assertTrue(domainPortNonEmpty, "expected domain port non-empty after accepted apply")
}
