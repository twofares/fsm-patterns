package fsm.sample.effect

/**
 * PURPOSE: Standard multi-family **accept / refuse** cross-cutting list builders.
 *
 * Pure steps currently hand-roll `auditTransition` + `metricInc("fsm.transitions")` +
 * machine extras on accept, and `auditRefusal` + refuse metric + `traceSpan` on refuse.
 * These helpers encode that teaching-standard combination so agents do not invent
 * incomplete packages (e.g. audit-only refuse without metric/trace).
 *
 * **Additive only** in this PR — production call sites still build lists by hand;
 * fixture tests prove helpers reproduce those lists (including extras and
 * Connection `traceDetail = event.label()`).
 *
 * [extras] carries **machine-specific** metrics/traces — required for non-toy machines
 * (e.g. `csp.accept`, `light.ticks`, `door.latch.unlock`). Never drop extras when
 * migrating call sites.
 */

/**
 * Standard accepted transition observability.
 *
 * Base: [auditTransition] + [metricInc] `"fsm.transitions"`.
 * [extras] appends machine-specific metrics/traces after the standard preamble.
 */
fun acceptTransitionCrossCutting(
    machine: String,
    from: String,
    event: String,
    to: String,
    extras: List<CrossCuttingEffect> = emptyList(),
): List<CrossCuttingEffect> = buildList {
    add(auditTransition(machine, from, event, to))
    add(metricInc("fsm.transitions"))
    addAll(extras)
}

/**
 * Standard refusal package (audit + named refuse metric + trace).
 *
 * [traceDetail] defaults to [reason] (File / Door). Connection currently uses the
 * **event label** as trace detail — callers must pass that explicitly; do not silently
 * unify to reason-only or export labels will change
 * (e.g. `Trace.Span(connection.refuse,Heartbeat)` vs reason text).
 *
 * [traceName] defaults to [metricName] (File `csp.refuse`, Connection `connection.refuse`).
 * Door uses a distinct policy span: `metricName = "door.refuse"`, `traceName = "door.policy"`.
 */
fun refuseCrossCutting(
    machine: String,
    state: String,
    event: String,
    reason: String,
    metricName: String,
    traceName: String = metricName,
    traceDetail: String = reason,
): List<CrossCuttingEffect> = listOf(
    auditRefusal(machine, state, event, reason),
    metricInc(metricName),
    traceSpan(traceName, traceDetail),
)
