package fsm.sample.gof

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.HierarchyStep
import fsm.sample.effect.acceptTransitionCrossCutting
import fsm.sample.effect.auditEntry
import fsm.sample.effect.auditExit
import fsm.sample.effect.metricInc
import fsm.sample.effect.refuseCrossCutting
import fsm.sample.effect.traceSpan
import fsm.sample.machine.FragmentedStepEffects
import fsm.sample.machine.applyFragments
import fsm.sample.machine.dispatchWithDelegation
import fsm.sample.machine.pathFromRoot
import fsm.sample.machine.planTransition

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
 * **One hierarchy API:** call [pathFromRoot] / [planTransition] / [dispatchWithDelegation]
 * from `fsm.sample.machine` directly (no public gof free-function wrappers named like
 * those helpers). Conn binds dispatch once via internal [dispatchConnection].
 * Vignette-local policy stays here: [declaredEffects], [ConnState.onEvent], enter/exit
 * domain payloads, Heartbeat/same_leaf metrics, and [HandleResult].
 *
 * Export product evaluation and [ConnectionContext.handle] share pure [connectionStep]
 * ([dispatchConnection] + [declaredEffects] → [HierarchyStep]; no interpreters).
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

/**
 * PURPOSE: Conn vignette binding for parent-bubble dispatch — **not** a second public
 * hierarchy API. Binds ConnState/ConnEvent/HandleResult lambdas once for
 * [ConnectionContext.handle], export probe, and tests.
 *
 * Agents still call structural [dispatchWithDelegation] for new hierarchies;
 * Connection uses this single adapter instead of triplicating the lambda set.
 */
internal fun dispatchConnection(
    start: ConnState,
    event: ConnEvent,
    currentLeaf: ConnState = start,
): HandleResult =
    dispatchWithDelegation(
        start = start,
        event = event,
        currentLeaf = currentLeaf,
        parentOf = { it.parent },
        onEvent = { node, e, leaf -> node.onEvent(e, leaf) },
        isUnhandled = { it is HandleResult.Unhandled },
        unhandled = HandleResult.Unhandled,
    )

/**
 * PURPOSE: Pure effect assembly for a hierarchical (from, event, HandleResult) triple.
 *
 * Shared by [connectionStep] (and thus handle + export) so assembly cannot drift:
 * - real transition → shared [planTransition] with ConnState lambdas
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
            planTransition(
                from = from,
                to = next,
                eventLabel = event.label(),
                handlerDomain = result.domain,
                machineName = machineName,
                nameOf = { it.name },
                parentOf = { it.parent },
                enterEffects = { s, m -> s.enterEffects(m) },
                exitEffects = { s, m -> s.exitEffects(m) },
            )
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
 * PURPOSE: Pure Connection hierarchy step — fuse [dispatchConnection] + [declaredEffects].
 *
 * Single pure product for leaf × event used by both [ConnectionContext.handle] and
 * export [fsm.sample.export.evaluateConnectionHierarchy]. No context, no interpreters,
 * no ports. Same-leaf / refuse policy remains inside [declaredEffects].
 */
fun connectionStep(
    leaf: ConnState,
    event: ConnEvent,
    machineName: String = "Connection",
): HierarchyStep<ConnState, ConnectionEffect> {
    val result = dispatchConnection(leaf, event)
    val effects = declaredEffects(leaf, event, result, machineName)
    return when (result) {
        is HandleResult.Handled -> HierarchyStep.Handled(next = result.next, effects = effects)
        is HandleResult.Unhandled -> HierarchyStep.Unhandled(effects = effects)
    }
}

/**
 * PURPOSE: GoF context — owns the current state object and performs transitions
 * by replacing it, with proper exit/enter chain for hierarchy.
 *
 * Transition algorithm (ordinary code, not an engine):
 * 1. Pure [connectionStep] (dispatch + declaredEffects → [HierarchyStep]).
 * 2. [applyFragments] while [current] is still the old leaf ("exit while still in
 *    old state"), then assign [current] if the leaf changed.
 * 3. Refuse packages still apply CC fragments; return false without assigning.
 *
 * **Must not** use [fsm.sample.machine.applyAccepted] — wrong assign order and
 * loss of per-fragment CC↔domain pairing.
 *
 * Initial entry always runs on construction (outermost → innermost enter fragments)
 * so production paths get offline entry audit. Export never constructs this class —
 * it uses pure [connectionStep] only.
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
        val effects = pathFromRoot(initial) { it.parent }
            .fold(FragmentedStepEffects<ConnectionEffect>()) { acc, s ->
                acc + s.enterEffects(machineName)
            }
        applyFragments(effects, crossCutting, port, ::applyDomainConnection)
    }

    fun handle(event: ConnEvent): Boolean {
        val fromLeaf = current
        // Hierarchical recipe: apply fragments first, then assign if leaf changes.
        // Refuse packages still apply CC (same pure step as export).
        return when (val step = connectionStep(fromLeaf, event, machineName)) {
            is HierarchyStep.Handled -> {
                applyFragments(step.effects, crossCutting, port, ::applyDomainConnection)
                if (step.next !== fromLeaf) {
                    current = step.next
                }
                true
            }
            is HierarchyStep.Unhandled -> {
                applyFragments(step.effects, crossCutting, port, ::applyDomainConnection)
                false
            }
        }
    }
}
