package fsm.sample.gof

import fsm.sample.effect.CrossCuttingEffect
import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.CrossCuttingInterpreter
import fsm.sample.effect.auditEntry
import fsm.sample.effect.auditExit
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.metricInc
import fsm.sample.effect.traceSpan

/**
 * PURPOSE: GoF State pattern + hierarchical superstate delegation + entry/exit.
 *
 * Disciplines demonstrated (ordinary OO, no statechart engine):
 * 1. GoF State: context holds current state object; transitions replace it.
 * 2. Hierarchical FSMs: composite/leaf states; unhandled events bubble to parent
 *    (behavioral inheritance — program only differences from the superstate).
 * 3. Entry/exit / heartbeat as *declared* split cross-cutting + domain fragments:
 *    pure-ish handlers return next leaf + domain; pure [declaredEffects] assembles LCA
 *    fragments; the context boundary applies each fragment
 *    (cross-cutting then domain via [CrossCuttingInterpreter] + [applyDomainConnection]).
 *
 * Domain vignette: a network connection with hierarchy
 *   Offline (leaf)
 *   Online (superstate)
 *     └── Idle (leaf)
 *     └── Transferring (leaf)
 *
 * Events unhandled in Idle/Transferring may still be handled by Online.
 *
 * Export product evaluation uses pure [planTransition] / [declaredEffects] and
 * pure [dispatchWithDelegation] (no [ConnectionContext], no interpreters).
 */

/** Closed event alphabet for the connection machine. */
sealed class ConnEvent {
    data object Connect : ConnEvent()
    data object Disconnect : ConnEvent()
    data object StartTransfer : ConnEvent()
    data object FinishTransfer : ConnEvent()
    /** Handled only at Online superstate level (heartbeat while connected). */
    data object Heartbeat : ConnEvent()

    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: Result of handling an event in a state object.
 * - [Handled] carries the next leaf and any **domain** effects declared by the
 *   handler (e.g. [ConnectionEffect.IncHeartbeat]); entry/exit/transition audit
 *   and domain session effects are assembled via pure [declaredEffects].
 * - [Unhandled] means "delegate to parent" (hierarchical discipline).
 */
sealed class HandleResult {
    data class Handled(
        val next: ConnState,
        val domain: List<ConnectionEffect> = emptyList(),
    ) : HandleResult()

    data object Unhandled : HandleResult()
}

/**
 * PURPOSE: One LCA assembly fragment — paired audit + domain for a single
 * exit/enter/transition/handler step.
 *
 * Boundary applies [crossCutting] then [domain] of this fragment before the next,
 * so Online enter keeps audit/metric/trace paired with `OpenSession` before Idle.
 *
 * Prefer the generic [fsm.sample.machine.EffectFragment] after hierarchy migration (PR 6);
 * this Connection-local type is the same shape with `D = ConnectionEffect`.
 */
data class EffectFragment(
    val crossCutting: List<CrossCuttingEffect> = emptyList(),
    val domain: List<ConnectionEffect> = emptyList(),
)

/**
 * PURPOSE: Pure hierarchical effect assembly as an ordered list of [EffectFragment]s.
 *
 * Sinks stay typed (audit vs domain) while fragment order preserves LCA interleaving
 * for both export labels and runtime apply. Safe for export — never mutates ports/logs.
 *
 * Prefer [fsm.sample.machine.FragmentedStepEffects] after hierarchy migration (PR 6).
 */
data class ConnectionStepEffects(
    val fragments: List<EffectFragment> = emptyList(),
) {
    /** Flattened audit (tests / inspection); apply uses [fragments] in order. */
    val crossCutting: List<CrossCuttingEffect> get() = fragments.flatMap { it.crossCutting }

    /** Flattened domain (tests / inspection); apply uses [fragments] in order. */
    val domain: List<ConnectionEffect> get() = fragments.flatMap { it.domain }

    /**
     * PURPOSE: Export labels walk fragments in LCA order; within each fragment
     * audit labels then domain labels (e.g. LogEntry(Online), OpenSession, LogEntry(Idle)).
     */
    fun exportLabels(): List<String> =
        fragments.flatMap { f ->
            f.crossCutting.map { it.exportLabel() } + f.domain.map { it.exportLabel() }
        }

    operator fun plus(other: ConnectionStepEffects): ConnectionStepEffects =
        ConnectionStepEffects(fragments + other.fragments)

    companion object {
        /** Single-fragment assembly helper (empty lists yield no fragment). */
        fun of(
            crossCutting: List<CrossCuttingEffect> = emptyList(),
            domain: List<ConnectionEffect> = emptyList(),
        ): ConnectionStepEffects =
            if (crossCutting.isEmpty() && domain.isEmpty()) {
                ConnectionStepEffects()
            } else {
                ConnectionStepEffects(listOf(EffectFragment(crossCutting, domain)))
            }
    }
}

/**
 * PURPOSE: State hierarchy nodes for GoF + hierarchical delegation.
 *
 * [parent] links form the superstate chain. Leaf states override [onEvent];
 * composite Online also handles events that leaves leave unhandled.
 * Entry/exit are pure effect *declarations* ([enterEffects]/[exitEffects]);
 * the context boundary runs them via apply sinks.
 *
 * [onEvent] takes [currentLeaf] instead of a full [ConnectionContext] so
 * hierarchical probes for export stay pure (no context construction, no
 * initial-entry side effects).
 */
sealed class ConnState {
    abstract val name: String
    open val parent: ConnState? get() = null

    /** PURPOSE: Pure entry effect declaration (audit ± domain; no I/O). */
    open fun enterEffects(machineName: String): ConnectionStepEffects =
        ConnectionStepEffects.of(crossCutting = listOf(auditEntry(machineName, name)))

    /** PURPOSE: Pure exit effect declaration (audit ± domain; no I/O). */
    open fun exitEffects(machineName: String): ConnectionStepEffects =
        ConnectionStepEffects.of(crossCutting = listOf(auditExit(machineName, name)))

    /**
     * PURPOSE: Per-state behavior. Default is Unhandled so events bubble up.
     * Leaves and composites override only the events they care about.
     * Must not mutate log/ports — declare domain on [HandleResult.Handled].
     *
     * [currentLeaf] is the leaf active when dispatch started (for same-leaf
     * reactions such as Heartbeat without needing a full context).
     */
    open fun onEvent(event: ConnEvent, currentLeaf: ConnState): HandleResult =
        HandleResult.Unhandled
}

/** PURPOSE: Leaf offline mode — only Connect is accepted here. */
data object Offline : ConnState() {
    override val name: String = "Offline"
    override val parent: ConnState? get() = null

    override fun onEvent(event: ConnEvent, currentLeaf: ConnState): HandleResult = when (event) {
        ConnEvent.Connect -> HandleResult.Handled(Idle)
        else -> HandleResult.Unhandled
    }
}

/**
 * PURPOSE: Composite superstate "Online".
 * Provides shared online behavior (Disconnect, Heartbeat) via hierarchical
 * delegation: leaves that do not handle these events fall through to here.
 */
data object Online : ConnState() {
    override val name: String = "Online"
    override val parent: ConnState? get() = null

    /**
     * PURPOSE: Enter Online = cross-cutting entry audit + **domain** OpenSession.
     * Fires only when the LCA path includes Online (not on Idle↔Transferring).
     */
    override fun enterEffects(machineName: String): ConnectionStepEffects =
        ConnectionStepEffects.of(
            crossCutting = listOf(
                auditEntry(machineName, name),
                metricInc("connection.session.open"),
                traceSpan("connection.session", "open"),
            ),
            domain = listOf(ConnectionEffect.OpenSession),
        )

    /**
     * PURPOSE: Exit Online = cross-cutting exit audit + **domain** CloseSession.
     */
    override fun exitEffects(machineName: String): ConnectionStepEffects =
        ConnectionStepEffects.of(
            crossCutting = listOf(
                auditExit(machineName, name),
                metricInc("connection.session.close"),
                traceSpan("connection.session", "close"),
            ),
            domain = listOf(ConnectionEffect.CloseSession),
        )

    override fun onEvent(event: ConnEvent, currentLeaf: ConnState): HandleResult = when (event) {
        ConnEvent.Disconnect -> HandleResult.Handled(Offline)
        ConnEvent.Heartbeat -> {
            // Superstate-only reaction: stay in current leaf; declare **domain** heartbeat.
            HandleResult.Handled(currentLeaf, listOf(ConnectionEffect.IncHeartbeat))
        }
        else -> HandleResult.Unhandled
    }
}

/** PURPOSE: Online leaf — waiting; can start transfer or rely on Online for disconnect/heartbeat. */
data object Idle : ConnState() {
    override val name: String = "Idle"
    override val parent: ConnState? get() = Online

    override fun onEvent(event: ConnEvent, currentLeaf: ConnState): HandleResult = when (event) {
        ConnEvent.StartTransfer -> HandleResult.Handled(Transferring)
        else -> HandleResult.Unhandled // bubbles to Online
    }
}

/** PURPOSE: Online leaf — active transfer; FinishTransfer returns to Idle. */
data object Transferring : ConnState() {
    override val name: String = "Transferring"
    override val parent: ConnState? get() = Online

    override fun onEvent(event: ConnEvent, currentLeaf: ConnState): HandleResult = when (event) {
        ConnEvent.FinishTransfer -> HandleResult.Handled(Idle)
        else -> HandleResult.Unhandled // bubbles to Online
    }
}

// --- Pure hierarchy / effect assembly (shared by handle + export) ------------

/**
 * PURPOSE: Build root → leaf path for hierarchical entry/exit ordering.
 * Pure; no context or interpreter.
 */
fun pathFromRoot(leaf: ConnState): List<ConnState> {
    val chain = mutableListOf<ConnState>()
    var n: ConnState? = leaf
    while (n != null) {
        chain.add(0, n)
        n = n.parent
    }
    return chain
}

/** PURPOSE: Length of shared superstate prefix (LCA index into root→leaf paths). */
fun commonPrefixLength(a: List<ConnState>, b: List<ConnState>): Int {
    val n = minOf(a.size, b.size)
    var i = 0
    while (i < n && a[i] === b[i]) i++
    return i
}

/**
 * PURPOSE: Pure assembly of hierarchical transition effects (LCA discipline).
 *
 * Fragment order: exit (innermost→outermost, exclusive of LCA) →
 * transition audit → [handlerDomain] → enter (outermost→innermost, exclusive of LCA).
 * Each exit/enter is one [EffectFragment] so domain (e.g. OpenSession) stays paired
 * with its state audit in both export labels and boundary apply.
 *
 * No [ConnectionContext], assign, or domain/audit apply — safe for export and tests.
 */
fun planTransition(
    from: ConnState,
    to: ConnState,
    event: ConnEvent,
    handlerDomain: List<ConnectionEffect> = emptyList(),
    machineName: String = "Connection",
): ConnectionStepEffects {
    val fromPath = pathFromRoot(from)
    val toPath = pathFromRoot(to)
    val lcaIndex = commonPrefixLength(fromPath, toPath)
    var assembled = ConnectionStepEffects()

    // Exit states exclusive of LCA, from leaf toward root.
    for (i in fromPath.lastIndex downTo lcaIndex) {
        assembled += fromPath[i].exitEffects(machineName)
    }

    assembled += ConnectionStepEffects.of(
        crossCutting = listOf(
            auditTransition(
                machine = machineName,
                from = from.name,
                event = event.label(),
                to = to.name,
            ),
            metricInc("fsm.transitions"),
        ),
    )

    // Handler / transition-action domain effects between exit and enter.
    assembled += ConnectionStepEffects.of(domain = handlerDomain)

    // Enter states exclusive of LCA, from root toward leaf.
    for (i in lcaIndex until toPath.size) {
        assembled += toPath[i].enterEffects(machineName)
    }

    return assembled
}

/**
 * PURPOSE: Pure effect assembly for a hierarchical (from, event, HandleResult) triple.
 *
 * Shared by [ConnectionContext.handle] and export product evaluation so assembly
 * cannot drift:
 * - real transition → [planTransition]
 * - same-leaf handled → one fragment (transition audit + handler domain)
 * - unhandled → refusal audit fragment
 *
 * Does not assign state or apply sinks.
 */
fun declaredEffects(
    from: ConnState,
    event: ConnEvent,
    result: HandleResult,
    machineName: String = "Connection",
): ConnectionStepEffects = when (result) {
    is HandleResult.Handled -> {
        val next = result.next
        if (next !== from) {
            planTransition(from, next, event, result.domain, machineName)
        } else {
            val metric = if (event is ConnEvent.Heartbeat) {
                metricInc("connection.heartbeat")
            } else {
                metricInc("connection.same_leaf")
            }
            ConnectionStepEffects.of(
                crossCutting = listOf(
                    auditTransition(
                        machine = machineName,
                        from = from.name,
                        event = event.label(),
                        to = next.name,
                    ),
                    metricInc("fsm.transitions"),
                    metric,
                ),
                domain = result.domain,
            )
        }
    }
    HandleResult.Unhandled -> ConnectionStepEffects.of(
        crossCutting = listOf(
            auditRefusal(
                machine = machineName,
                state = from.name,
                event = event.label(),
                reason = "no state in hierarchy accepted event",
            ),
            metricInc("connection.refuse"),
            traceSpan("connection.refuse", event.label()),
        ),
    )
}

/**
 * PURPOSE: Hierarchical event dispatch — walk leaf → parent until handled.
 * Pure control flow over shipped [ConnState.onEvent]; no effect application,
 * no [ConnectionContext] required (export-safe).
 *
 * [currentLeaf] is the leaf active at dispatch start (defaults to [start]).
 */
fun dispatchWithDelegation(
    start: ConnState,
    event: ConnEvent,
    currentLeaf: ConnState = start,
): HandleResult {
    var node: ConnState? = start
    while (node != null) {
        when (val r = node.onEvent(event, currentLeaf)) {
            is HandleResult.Handled -> return r
            HandleResult.Unhandled -> node = node.parent
        }
    }
    return HandleResult.Unhandled
}

/**
 * PURPOSE: GoF context — owns the current state object and performs transitions
 * by replacing it, with proper exit/enter chain for hierarchy.
 *
 * Transition algorithm (ordinary code, not an engine):
 * 1. Try current leaf; if Unhandled, walk parent chain until Handled or root.
 * 2. Assemble pure [declaredEffects] from the probe result.
 * 3. Apply [ConnectionStepEffects] fragment-wise while [current] is still the old
 *    leaf when needed ("exit while still in old state"), then assign [current]
 *    if the leaf changed.
 *
 * Initial entry always runs on construction (outermost → innermost enter fragments)
 * so production paths get Offline entry audit. Export never constructs this class —
 * it uses pure [dispatchWithDelegation] + [declaredEffects] only.
 *
 * Dual sinks: [crossCutting] env (audit/metrics/trace) + [port] (domain, including heartbeats).
 */
class ConnectionContext(
    initial: ConnState = Offline,
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: ConnectionPort = ConnectionPort(),
    val machineName: String = "Connection",
) {
    var current: ConnState = initial
        private set

    init {
        val effects = pathFromRoot(initial).fold(ConnectionStepEffects()) { acc, s ->
            acc + s.enterEffects(machineName)
        }
        applyEffects(effects)
    }

    fun handle(event: ConnEvent): Boolean {
        val fromLeaf = current
        val result = dispatchWithDelegation(fromLeaf, event, fromLeaf)
        val effects = declaredEffects(fromLeaf, event, result, machineName)
        return when (result) {
            is HandleResult.Unhandled -> {
                applyEffects(effects)
                false
            }
            is HandleResult.Handled -> {
                applyEffects(effects)
                if (result.next !== fromLeaf) {
                    current = result.next
                }
                true
            }
        }
    }

    /**
     * PURPOSE: Apply each LCA fragment as cross-cutting-then-domain so paired
     * enter/exit domain stays next to its audit/metric/trace labels.
     */
    private fun applyEffects(effects: ConnectionStepEffects) {
        for (fragment in effects.fragments) {
            CrossCuttingInterpreter.apply(fragment.crossCutting, crossCutting)
            applyDomainConnection(fragment.domain, port)
        }
    }
}
