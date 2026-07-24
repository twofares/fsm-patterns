package fsm.sample.effect

/**
 * PURPOSE: Generic pure step outcome — successor + **split** sinks.
 *
 * - [crossCutting]: shared multi-family effects ([CrossCuttingEffect] → [CrossCuttingEnv])
 * - [domain]: machine-local sealed ADT → machine port
 *
 * Cross-cutting is **not** audit-only: audit, metrics, and tracing all share
 * [crossCutting]. Domain ADTs never nest cross-cutting cases.
 *
 * Pure steps only declare; boundaries call [CrossCuttingInterpreter.apply] + applyDomain.
 * Export labels = cross-cutting labels + domain labels.
 */
data class StepResult<S, D>(
    val next: S,
    val crossCutting: List<CrossCuttingEffect> = emptyList(),
    val domain: List<D> = emptyList(),
)

/**
 * PURPOSE: Ordered export labels (cross-cutting first, then domain).
 * Pure mapping only — not an interpreter.
 */
fun <D> StepResult<*, D>.exportLabels(domainLabel: (D) -> String): List<String> =
    crossCutting.map { it.exportLabel() } + domain.map(domainLabel)
