package fsm.sample.effect

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Shared sinks for **all** cross-cutting effect families.
 *
 * Not a domain port — machines pass this (or share one) at the boundary so
 * audit, metrics, and tracing stay process-wide while domain ports stay local.
 */
class CrossCuttingEnv(
    val log: TransitionLog = TransitionLog(),
    val metrics: MetricsPort = MetricsPort(),
    val traces: TracePort = TracePort(),
) {
    fun snapshot(): String =
        "metrics=${metrics.snapshot()}; traces=${traces.snapshot()}"
}

/** PURPOSE: In-memory counter bag for [CrossCuttingEffect.Metric]. */
class MetricsPort {
    private val counts = mutableMapOf<String, Int>()

    fun inc(name: String, delta: Int = 1) {
        counts[name] = (counts[name] ?: 0) + delta
    }

    fun get(name: String): Int = counts[name] ?: 0

    fun snapshot(): Map<String, Int> = counts.toMap()

    fun clear() {
        counts.clear()
    }
}

/** PURPOSE: In-memory trace stream for [CrossCuttingEffect.Trace]. */
class TracePort {
    private val _spans = mutableListOf<String>()
    val spans: List<String> get() = _spans.toList()

    fun note(name: String, detail: String? = null) {
        _spans += if (detail == null) name else "$name:$detail"
    }

    fun snapshot(): List<String> = spans

    fun clear() {
        _spans.clear()
    }
}
