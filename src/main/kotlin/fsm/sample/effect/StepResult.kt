package fsm.sample.effect

/**
 * PURPOSE: Generic pure step outcome — successor state plus **split** sinks.
 *
 * Cross-cutting observability is always [audit] ([AuditEffect] → [TransitionLog]).
 * Domain work is always [domain] (machine-local sealed ADT → machine port).
 *
 * No nested `Audit` wrappers on domain ADTs: dual sinks stay separate lists.
 * Pure steps only declare; boundaries call [AuditInterpreter.apply] + applyDomain.
 * Export labels = audit labels + domain labels (no interpreter, no interleaving).
 *
 * [next] is always defined (refusal steps keep the prior state as next).
 * Empty lists mean nothing for that sink at the boundary.
 */
data class StepResult<S, D>(
    val next: S,
    val audit: List<AuditEffect> = emptyList(),
    val domain: List<D> = emptyList(),
)

/**
 * PURPOSE: Ordered export labels for a pure step (audit first, then domain).
 * Pure mapping only — not an interpreter.
 */
fun <D> StepResult<*, D>.exportLabels(domainLabel: (D) -> String): List<String> =
    audit.map { it.exportLabel() } + domain.map(domainLabel)
