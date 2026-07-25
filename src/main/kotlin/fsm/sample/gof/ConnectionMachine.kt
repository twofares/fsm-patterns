package fsm.sample.gof

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.acceptTransitionCrossCutting
import fsm.sample.effect.auditEntry
import fsm.sample.effect.auditExit
import fsm.sample.effect.metricInc
import fsm.sample.effect.refuseCrossCutting
import fsm.sample.effect.traceSpan
import fsm.sample.machine.FragmentedStepEffects
import fsm.sample.machine.applyFragments
import fsm.sample.machine.commonPrefixLength as hierarchyCommonPrefixLength
import fsm.sample.machine.dispatchWithDelegation as hierarchyDispatch
import fsm.sample.machine.pathFromRoot as hierarchyPathFromRoot
import fsm.sample.machine.planTransition as hierarchyPlanTransition

/**
 * PURPOSE: GoF State pattern + hierarchical superstate delegation + entry/exit.
 *
 * Disciplines demonstrated (ordinary OO, no statechart engine):
 * 1. GoF State: context holds current state object; transitions replace it.
 * 2. Hierarchical FSMs: composite/leaf states; unhandled events bubble to parent
 *    (behavioral inheritance — program only differences from the superstate).
 * 3. Entry/exit / heartbeat as *declared* split cross-cutting + domain fragments:
 *    pure-ish handlers return next leaf + domain; pure [declaredEffects] assembles LCA
 *    fragments; the context boundary applies each fragment via [applyFragments]
 *    (cross-cutting then domain via [fsm.sample.effect.CrossCuttingInterpreter] +
 *    [applyDomainConnection]).
 *
 * Domain vignette: a network connection with hierarchy
 *   Offline (leaf)
 *   Online (superstate)
 *     └── Idle (leaf)
 *     └── Transferring (leaf)
 *
 * Events unhandled in Idle/Transferring may still be handled by Online.
 *
 * Structural helpers ([pathFromRoot], [planTransition], [dispatchWithDelegation],
 * [FragmentedStepEffects]) live in `fsm.sample.machine` and are used via thin
 * ConnState wrappers here. Vignette-local policy stays local: [declaredEffects],
 * [ConnState.onEvent], enter/exit domain payloads, Heartbeat/same_leaf metrics,
 * and [HandleResult].
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
 *
 * Stays vignette-local (no shared HierarchicalHandleResult until a second hierarchy).
 */
sealed class HandleResult {
    data class Handled(
        val next: ConnState,
        val domain: List<ConnectionEffect> = emptyList(),
    ) : HandleResult()

    data object Unhandled : HandleResult()
}

/**
 * PURPOSE: Parameterless export labels for Connection (Option C).
 *
 * Shared [FragmentedStepEffects.exportLabels] needs a domain-label lambda;
 * this extension hardcodes [ConnectionEffect.exportLabel] so call sites keep
 * `effects.exportLabels()` without a second wrapper type.
 */
fun FragmentedStepEffects<ConnectionEffect>.exportLabels(): List<String> =
    exportLabels(ConnectionEffect::exportLabel)

/**
 * PURPOSE: State hierarchy nodes for GoF + hierarchical delegation.
 *
 * [parent] links form the superstate chain. Leaf states override [onEvent];
 * composite Online also handles events that leaves leave unhandled.
 * Entry/exit are pure effect *declarations* ([enterEffects]/[exitEffects]);
 * the context boundary runs them via [applyFragments].
 *
 * [onEvent] takes [currentLeaf] instead of a full [ConnectionContext] so
 * hierarchical probes for export stay pure (no context construction, no
 * initial-entry side effects).
 */
sealed class ConnState {
    abstract val name: String
    open val parent: ConnState? get() = null

    /** PURPOSE: Pure entry effect declaration (audit ± domain; no I/O). */
    open fun enterEffects(machineName: String): FragmentedStepEffects<ConnectionEffect> =
        FragmentedStepEffects.of(crossCutting = listOf(auditEntry(machineName, name)))

    /** PURPOSE: Pure exit effect declaration (audit ± domain; no I/O). */
    open fun exitEffects(machineName: String): FragmentedStepEffects<ConnectionEffect> =
        FragmentedStepEffects.of(crossCutting = listOf(auditExit(machineName, name)))

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
    override fun enterEffects(machineName: String): FragmentedStepEffects<ConnectionEffect> =
        FragmentedStepEffects.of(
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
    override fun exitEffects(machineName: String): FragmentedStepEffects<ConnectionEffect> =
        FragmentedStepEffects.of(
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

// --- Thin ConnState wrappers over shared hierarchy helpers --------------------

/**
 * PURPOSE: Build root → leaf path for hierarchical entry/exit ordering.
 * Pure; no context or interpreter. Delegates to shared [hierarchyPathFromRoot].
 */
fun pathFromRoot(leaf: ConnState): List<ConnState> =
    hierarchyPathFromRoot(leaf) { it.parent }

/** PURPOSE: Length of shared superstate prefix (LCA index into root→leaf paths). */
fun commonPrefixLength(a: List<ConnState>, b: List<ConnState>): Int =
    hierarchyCommonPrefixLength(a, b)

/**
 * PURPOSE: Pure assembly of hierarchical transition effects (LCA discipline).
 *
 * Thin wrapper over shared [hierarchyPlanTransition]; enter/exit domain payloads
 * remain on [ConnState] objects. No Heartbeat / same-leaf policy here.
 */
fun planTransition(
    from: ConnState,
    to: ConnState,
    event: ConnEvent,
    handlerDomain: List<ConnectionEffect> = emptyList(),
    machineName: String = "Connection",
): FragmentedStepEffects<ConnectionEffect> =
    hierarchyPlanTransition(
        from = from,
        to = to,
        eventLabel = event.label(),
        handlerDomain = handlerDomain,
        machineName = machineName,
        nameOf = { it.name },
        parentOf = { it.parent },
        enterEffects = { s, m -> s.enterEffects(m) },
        exitEffects = { s, m -> s.exitEffects(m) },
    )

/**
 * PURPOSE: Pure effect assembly for a hierarchical (from, event, HandleResult) triple.
 *
 * Shared by [ConnectionContext.handle] and export product evaluation so assembly
 * cannot drift:
 * - real transition → [planTransition]
 * - same-leaf handled → one fragment (accept audit + connection-specific metric + handler domain)
 * - unhandled → [refuseCrossCutting] with `traceDetail = event.label()`
 *
 * Does not assign state or apply sinks. Stays vignette-local (not in machine/).
 */
fun declaredEffects(
    from: ConnState,
    event: ConnEvent,
    result: HandleResult,
    machineName: String = "Connection",
): FragmentedStepEffects<ConnectionEffect> = when (result) {
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
            FragmentedStepEffects.of(
                crossCutting = acceptTransitionCrossCutting(
                    machine = machineName,
                    from = from.name,
                    event = event.label(),
                    to = next.name,
                    extras = listOf(metric),
                ),
                domain = result.domain,
            )
        }
    }
    HandleResult.Unhandled -> FragmentedStepEffects.of(
        crossCutting = refuseCrossCutting(
            machine = machineName,
            state = from.name,
            event = event.label(),
            reason = "no state in hierarchy accepted event",
            metricName = "connection.refuse",
            traceName = "connection.refuse",
            traceDetail = event.label(),
        ),
    )
}

/**
 * PURPOSE: Hierarchical event dispatch — walk leaf → parent until handled.
 * Pure control flow over shipped [ConnState.onEvent]; no effect application,
 * no [ConnectionContext] required (export-safe).
 *
 * Thin wrapper over shared [hierarchyDispatch] with Connection's local [HandleResult].
 *
 * [currentLeaf] is the leaf active at dispatch start (defaults to [start]).
 */
fun dispatchWithDelegation(
    start: ConnState,
    event: ConnEvent,
    currentLeaf: ConnState = start,
): HandleResult =
    hierarchyDispatch(
        start = start,
        event = event,
        currentLeaf = currentLeaf,
        parentOf = { it.parent },
        onEvent = { node, e, leaf -> node.onEvent(e, leaf) },
        isUnhandled = { it is HandleResult.Unhandled },
        unhandled = HandleResult.Unhandled,
    )

/**
 * PURPOSE: GoF context — owns the current state object and performs transitions
 * by replacing it, with proper exit/enter chain for hierarchy.
 *
 * Transition algorithm (ordinary code, not an engine):
 * 1. Try current leaf; if Unhandled, walk parent chain until Handled or root.
 * 2. Assemble pure [declaredEffects] from the probe result.
 * 3. [applyFragments] while [current] is still the old leaf ("exit while still in
 *    old state"), then assign [current] if the leaf changed.
 *
 * **Must not** use [fsm.sample.machine.applyAccepted] — wrong assign order and
 * loss of per-fragment CC↔domain pairing.
 *
 * Initial entry always runs on construction (outermost → innermost enter fragments)
 * so production paths get offline entry audit. Export never constructs this class —
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
        val effects = pathFromRoot(initial).fold(FragmentedStepEffects<ConnectionEffect>()) { acc, s ->
            acc + s.enterEffects(machineName)
        }
        applyFragments(effects, crossCutting, port, ::applyDomainConnection)
    }

    fun handle(event: ConnEvent): Boolean {
        val fromLeaf = current
        val result = dispatchWithDelegation(fromLeaf, event, fromLeaf)
        val effects = declaredEffects(fromLeaf, event, result, machineName)
        // Hierarchical recipe: apply fragments first, then assign if leaf changes.
        applyFragments(effects, crossCutting, port, ::applyDomainConnection)
        return when (result) {
            is HandleResult.Unhandled -> false
            is HandleResult.Handled -> {
                if (result.next !== fromLeaf) {
                    current = result.next
                }
                true
            }
        }
    }
}
