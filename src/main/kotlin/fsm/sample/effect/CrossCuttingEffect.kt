package fsm.sample.effect

/**
 * PURPOSE: **Generalized cross-cutting** effect vocabulary — not audit-only.
 *
 * Cross-cutting effects are shared concerns any machine may declare without
 * owning them as domain behavior. Disparate families live under one sealed
 * root so [StepResult.crossCutting] is a single typed list, while each family
 * still routes to its own sink via [CrossCuttingInterpreter]:
 *
 * - [Audit] → [TransitionLog] (mode-change observability)
 * - [Metric] → [MetricsPort] (shared counters / rates)
 * - [Trace] → [TracePort] (structured diagnostic notes)
 *
 * Domain ADTs stay machine-local and never nest these. Pure steps *declare*;
 * boundaries *apply* via [CrossCuttingInterpreter] + domain apply helpers.
 */
sealed class CrossCuttingEffect {
    abstract fun exportLabel(): String

    // --- Audit family (shared mode-change log) --------------------------------

    /** Observability of transitions / entry / exit / refusal. */
    sealed class Audit : CrossCuttingEffect() {
        data class LogTransition(
            val machine: String,
            val from: String,
            val event: String,
            val to: String,
        ) : Audit() {
            override fun exportLabel(): String = "Audit.LogTransition"
        }

        data class LogEntry(
            val machine: String,
            val state: String,
            val detail: String? = null,
        ) : Audit() {
            override fun exportLabel(): String = "Audit.LogEntry($state)"
        }

        data class LogExit(
            val machine: String,
            val state: String,
            val detail: String? = null,
        ) : Audit() {
            override fun exportLabel(): String = "Audit.LogExit($state)"
        }

        data class LogRefusal(
            val machine: String,
            val state: String,
            val event: String,
            val reason: String,
        ) : Audit() {
            override fun exportLabel(): String = "Audit.LogRefusal"
        }
    }

    // --- Metrics family (shared counters) -------------------------------------

    /** Process-wide metrics, not owned by any single machine's domain port. */
    sealed class Metric : CrossCuttingEffect() {
        data class Inc(val name: String, val delta: Int = 1) : Metric() {
            override fun exportLabel(): String =
                if (delta == 1) "Metric.Inc($name)" else "Metric.Inc($name,$delta)"
        }
    }

    // --- Trace family (shared diagnostic stream) ------------------------------

    /** Lightweight tracing notes shared across machines. */
    sealed class Trace : CrossCuttingEffect() {
        data class Span(val name: String, val detail: String? = null) : Trace() {
            override fun exportLabel(): String =
                if (detail == null) "Trace.Span($name)" else "Trace.Span($name,$detail)"
        }
    }
}

// --- Factories (readable pure-step call sites) --------------------------------

fun auditTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): CrossCuttingEffect.Audit.LogTransition =
    CrossCuttingEffect.Audit.LogTransition(machine, from, event, to)

fun auditEntry(
    machine: String,
    state: String,
    detail: String? = null,
): CrossCuttingEffect.Audit.LogEntry =
    CrossCuttingEffect.Audit.LogEntry(machine, state, detail)

fun auditExit(
    machine: String,
    state: String,
    detail: String? = null,
): CrossCuttingEffect.Audit.LogExit =
    CrossCuttingEffect.Audit.LogExit(machine, state, detail)

fun auditRefusal(
    machine: String,
    state: String,
    event: String,
    reason: String,
): CrossCuttingEffect.Audit.LogRefusal =
    CrossCuttingEffect.Audit.LogRefusal(machine, state, event, reason)

fun metricInc(name: String, delta: Int = 1): CrossCuttingEffect.Metric.Inc =
    CrossCuttingEffect.Metric.Inc(name, delta)

fun traceSpan(name: String, detail: String? = null): CrossCuttingEffect.Trace.Span =
    CrossCuttingEffect.Trace.Span(name, detail)
