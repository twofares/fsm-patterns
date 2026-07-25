package fsm.sample.effect

/**
 * PURPOSE: Pure partial engagement outcome — peer of [StepResult] for refuse-capable machines.
 *
 * Dual-sink on **Accepted** (crossCutting + domain); **Refused** has no domain field so
 * I/O intents are unrepresentable on refuse paths.
 *
 * Pure steps only declare; boundaries call [fsm.sample.machine.applyOffer].
 * Export labels = cross-cutting labels (+ domain labels when Accepted).
 * Prefer this over [StepResult] for refuse-capable machines (README kind matrix).
 */
sealed class OfferOutcome<S, D> {
    abstract val next: S
    abstract val crossCutting: List<CrossCuttingEffect>

    data class Accepted<S, D>(
        override val next: S,
        override val crossCutting: List<CrossCuttingEffect>,
        val domain: List<D>,
    ) : OfferOutcome<S, D>()

    /**
     * Refusal: successor is typically the current state (self-successor).
     * No [domain] property — refuse cannot declare I/O.
     */
    data class Refused<S, D>(
        val reason: String,
        override val next: S,
        override val crossCutting: List<CrossCuttingEffect>,
    ) : OfferOutcome<S, D>()
}

/**
 * PURPOSE: Ordered export labels (cross-cutting first, then domain on accept).
 * Pure mapping only — not an interpreter.
 */
fun <D> OfferOutcome<*, D>.exportLabels(domainLabel: (D) -> String): List<String> = when (this) {
    is OfferOutcome.Accepted ->
        crossCutting.map { it.exportLabel() } + domain.map(domainLabel)
    is OfferOutcome.Refused ->
        crossCutting.map { it.exportLabel() }
}
