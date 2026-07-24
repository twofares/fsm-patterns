package fsm.sample.gof

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: GoF State pattern + hierarchical superstate delegation + entry/exit.
 *
 * Disciplines demonstrated (ordinary OO, no statechart engine):
 * 1. GoF State: context holds current state object; transitions replace it.
 * 2. Hierarchical FSMs: composite/leaf states; unhandled events bubble to parent
 *    (behavioral inheritance — program only differences from the superstate).
 * 3. Entry/exit / heartbeat as *declared* [ConnectionEffect]s: pure-ish handlers
 *    return next leaf + effects; the context boundary interprets them
 *    ([interpretConnection]: cross-cutting audit + domain IncHeartbeat).
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
 * probe contexts with [applyInitialEntry]=false so [interpretConnection] is never
 * invoked during documentation generation.
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
 * - [Handled] carries the next leaf state and any effects declared by the handler
 *   (e.g. [ConnectionEffect.IncHeartbeat]); entry/exit/transition effects are assembled
 *   via pure [declaredEffects] / [planTransition] before the boundary applies them.
 * - [Unhandled] means "delegate to parent" (hierarchical discipline).
 */
sealed class HandleResult {
    data class Handled(
        val next: ConnState,
        val effects: List<ConnectionEffect> = emptyList(),
    ) : HandleResult()

    data object Unhandled : HandleResult()
}

/**
 * PURPOSE: State hierarchy nodes for GoF + hierarchical delegation.
 *
 * [parent] links form the superstate chain. Leaf states override [onEvent];
 * composite Online also handles events that leaves leave unhandled.
 * Entry/exit are pure effect *declarations* ([enterEffects]/[exitEffects]);
 * the context boundary runs them via [interpretConnection].
 */
sealed class ConnState {
    abstract val name: String
    open val parent: ConnState? get() = null

    /** PURPOSE: Pure entry effect declaration (cross-cutting audit; no I/O). */
    open fun enterEffects(machineName: String): List<ConnectionEffect> =
        listOf(connEntry(machineName, name))

    /** PURPOSE: Pure exit effect declaration (cross-cutting audit; no I/O). */
    open fun exitEffects(machineName: String): List<ConnectionEffect> =
        listOf(connExit(machineName, name))

    /**
     * PURPOSE: Per-state behavior. Default is Unhandled so events bubble up.
     * Leaves and composites override only the events they care about.
     * Must not mutate log/counters — declare effects on [HandleResult.Handled].
     */
    open fun onEvent(ctx: ConnectionContext, event: ConnEvent): HandleResult =
        HandleResult.Unhandled
}

/** PURPOSE: Leaf offline mode — only Connect is accepted here. */
data object Offline : ConnState() {
    override val name: String = "Offline"
    override val parent: ConnState? get() = null

    override fun onEvent(ctx: ConnectionContext, event: ConnEvent): HandleResult = when (event) {
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

    override fun onEvent(ctx: ConnectionContext, event: ConnEvent): HandleResult = when (event) {
        ConnEvent.Disconnect -> HandleResult.Handled(Offline)
        ConnEvent.Heartbeat -> {
            // Superstate-only reaction: stay in current leaf; declare **domain** heartbeat.
            HandleResult.Handled(ctx.current, listOf(ConnectionEffect.IncHeartbeat))
        }
        else -> HandleResult.Unhandled
    }
}

/** PURPOSE: Online leaf — waiting; can start transfer or rely on Online for disconnect/heartbeat. */
data object Idle : ConnState() {
    override val name: String = "Idle"
    override val parent: ConnState? get() = Online

    override fun onEvent(ctx: ConnectionContext, event: ConnEvent): HandleResult = when (event) {
        ConnEvent.StartTransfer -> HandleResult.Handled(Transferring)
        else -> HandleResult.Unhandled // bubbles to Online
    }
}

/** PURPOSE: Online leaf — active transfer; FinishTransfer returns to Idle. */
data object Transferring : ConnState() {
    override val name: String = "Transferring"
    override val parent: ConnState? get() = Online

    override fun onEvent(ctx: ConnectionContext, event: ConnEvent): HandleResult = when (event) {
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
 * Order (UML-style): exit (innermost→outermost, exclusive of LCA) →
 * transition audit → [handlerEffects] (transition actions, e.g. IncHeartbeat) →
 * enter (outermost→innermost, exclusive of LCA).
 *
 * No [ConnectionContext], assign, or [interpretConnection] — safe for export and tests.
 */
fun planTransition(
    from: ConnState,
    to: ConnState,
    event: ConnEvent,
    handlerEffects: List<ConnectionEffect> = emptyList(),
    machineName: String = "Connection",
): List<ConnectionEffect> {
    val fromPath = pathFromRoot(from)
    val toPath = pathFromRoot(to)
    val lcaIndex = commonPrefixLength(fromPath, toPath)
    val effects = mutableListOf<ConnectionEffect>()

    // Exit states exclusive of LCA, from leaf toward root.
    for (i in fromPath.lastIndex downTo lcaIndex) {
        effects += fromPath[i].exitEffects(machineName)
    }

    effects += connTransition(
        machine = machineName,
        from = from.name,
        event = event.label(),
        to = to.name,
    )

    // Handler / transition-action effects between exit and enter.
    effects += handlerEffects

    // Enter states exclusive of LCA, from root toward leaf.
    for (i in lcaIndex until toPath.size) {
        effects += toPath[i].enterEffects(machineName)
    }

    return effects
}

/**
 * PURPOSE: Pure effect list for a hierarchical (from, event, HandleResult) triple.
 *
 * Shared by [ConnectionContext.handle] and export product evaluation so assembly
 * cannot drift:
 * - real transition → [planTransition]
 * - same-leaf handled → handler effects + transition audit
 * - unhandled → refusal audit
 *
 * Does not assign state or call [interpretConnection].
 */
fun declaredEffects(
    from: ConnState,
    event: ConnEvent,
    result: HandleResult,
    machineName: String = "Connection",
): List<ConnectionEffect> = when (result) {
    is HandleResult.Handled -> {
        val next = result.next
        if (next !== from) {
            planTransition(from, next, event, result.effects, machineName)
        } else {
            result.effects + connTransition(
                machine = machineName,
                from = from.name,
                event = event.label(),
                to = next.name,
            )
        }
    }
    HandleResult.Unhandled -> listOf(
        connRefusal(
            machine = machineName,
            state = from.name,
            event = event.label(),
            reason = "no state in hierarchy accepted event",
        ),
    )
}

/**
 * PURPOSE: Hierarchical event dispatch — walk leaf → parent until handled.
 * Pure control flow over shipped [ConnState.onEvent]; no effect application.
 */
fun dispatchWithDelegation(
    ctx: ConnectionContext,
    start: ConnState,
    event: ConnEvent,
): HandleResult {
    var node: ConnState? = start
    while (node != null) {
        when (val r = node.onEvent(ctx, event)) {
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
 * 3. [interpretConnection] while [current] is still the old leaf when needed
 *    ("exit while still in old state"), then assign [current] if the leaf changed.
 *
 * [applyInitialEntry]: when true (default), init enters the initial ancestry via
 * the interpreter. Export/product probes pass false so construction never calls
 * [interpretConnection].
 */
class ConnectionContext(
    initial: ConnState = Offline,
    val log: TransitionLog? = null,
    val machineName: String = "Connection",
    applyInitialEntry: Boolean = true,
) {
    var current: ConnState = initial
        private set

    /**
     * Heartbeat counter applied only at the effect boundary (not in onEvent).
     * Private setter so handlers cannot bypass [interpretConnection] via mutation.
     * Domain-specific: only ConnectionEffect.IncHeartbeat bumps this.
     */
    var heartbeatCount: Int = 0
        private set

    init {
        if (applyInitialEntry) {
            // Enter the full ancestry of the initial state outermost → innermost.
            val effects = pathFromRoot(initial).flatMap { it.enterEffects(machineName) }
            applyEffects(effects)
        }
    }

    /**
     * Dispatch one event using hierarchical delegation, then replace state if needed.
     * @return true if some state in the chain handled the event
     */
    fun handle(event: ConnEvent): Boolean {
        val fromLeaf = current
        val result = dispatchWithDelegation(this, fromLeaf, event)
        val effects = declaredEffects(fromLeaf, event, result, machineName)
        return when (result) {
            is HandleResult.Unhandled -> {
                applyEffects(effects)
                false
            }
            is HandleResult.Handled -> {
                // Apply while still in fromLeaf; assign only after for real transitions.
                applyEffects(effects)
                if (result.next !== fromLeaf) {
                    current = result.next
                }
                true
            }
        }
    }

    /**
     * PURPOSE: Instance facade over pure top-level [planTransition] (machineName bound).
     * Kept so tests can call `ctx.planTransition(...)` without re-stating the name.
     */
    fun planTransition(
        from: ConnState,
        to: ConnState,
        event: ConnEvent,
        handlerEffects: List<ConnectionEffect> = emptyList(),
    ): List<ConnectionEffect> =
        fsm.sample.gof.planTransition(from, to, event, handlerEffects, machineName)

    private fun applyEffects(effects: List<ConnectionEffect>) {
        interpretConnection(effects, log) {
            heartbeatCount += 1
        }
    }
}
