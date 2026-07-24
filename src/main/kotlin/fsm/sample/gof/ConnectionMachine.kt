package fsm.sample.gof

import fsm.sample.effect.Effect
import fsm.sample.effect.EffectInterpreter
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: GoF State pattern + hierarchical superstate delegation + entry/exit.
 *
 * Disciplines demonstrated (ordinary OO, no statechart engine):
 * 1. GoF State: context holds current state object; transitions replace it.
 * 2. Hierarchical FSMs: composite/leaf states; unhandled events bubble to parent
 *    (behavioral inheritance — program only differences from the superstate).
 * 3. Entry/exit / heartbeat as *declared* [Effect]s: pure-ish handlers return
 *    next leaf + effects; the context boundary interprets them (log, counters).
 *
 * Domain vignette: a network connection with hierarchy
 *   Offline (leaf)
 *   Online (superstate)
 *     └── Idle (leaf)
 *     └── Transferring (leaf)
 *
 * Events unhandled in Idle/Transferring may still be handled by Online.
 *
 * Export product evaluation probes [onEvent] for next-state only; effects are
 * a runtime-boundary concern (not added to export tables).
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
 *   (e.g. [Effect.IncHeartbeat]); entry/exit/transition effects are assembled
 *   by the context boundary when replacing state.
 * - [Unhandled] means "delegate to parent" (hierarchical discipline).
 */
sealed class HandleResult {
    data class Handled(
        val next: ConnState,
        val effects: List<Effect> = emptyList(),
    ) : HandleResult()

    data object Unhandled : HandleResult()
}

/**
 * PURPOSE: State hierarchy nodes for GoF + hierarchical delegation.
 *
 * [parent] links form the superstate chain. Leaf states override [onEvent];
 * composite Online also handles events that leaves leave unhandled.
 * Entry/exit are pure effect *declarations* ([enterEffects]/[exitEffects]);
 * the context boundary runs them via [EffectInterpreter].
 */
sealed class ConnState {
    abstract val name: String
    open val parent: ConnState? get() = null

    /** PURPOSE: Pure entry effect declaration (no I/O). */
    open fun enterEffects(machineName: String): List<Effect> =
        listOf(Effect.LogEntry(machineName, name))

    /** PURPOSE: Pure exit effect declaration (no I/O). */
    open fun exitEffects(machineName: String): List<Effect> =
        listOf(Effect.LogExit(machineName, name))

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
            // Superstate-only reaction: stay in current leaf; declare heartbeat effect.
            HandleResult.Handled(ctx.current, listOf(Effect.IncHeartbeat))
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

/**
 * PURPOSE: GoF context — owns the current state object and performs transitions
 * by replacing it, with proper exit/enter chain for hierarchy.
 *
 * Transition algorithm (ordinary code, not an engine):
 * 1. Try current leaf; if Unhandled, walk parent chain until Handled or root.
 * 2. If still Unhandled, refuse (declare LogRefusal effect) and keep state.
 * 3. If Handled with a different next state:
 *    - Assemble effects via [planTransition]: exit (while still in old leaf) →
 *      LogTransition → handler effects → enter (UML-style placement).
 *    - [EffectInterpreter.apply] while [current] is still the old leaf
 *      ("exit while still in old state").
 *    - Assign [current] = next only after effects are applied.
 * 4. Same-leaf Handled (e.g. Heartbeat): handler effects + LogTransition, then apply
 *    (no state assignment). Same-leaf is the intended home for handler-only effects
 *    such as [Effect.IncHeartbeat]; state-changing handlers use [planTransition].
 */
class ConnectionContext(
    initial: ConnState = Offline,
    val log: TransitionLog? = null,
    val machineName: String = "Connection",
) {
    var current: ConnState = initial
        private set

    /**
     * Heartbeat counter applied only at the effect boundary (not in onEvent).
     * Private setter so handlers cannot bypass [EffectInterpreter] via mutation.
     */
    var heartbeatCount: Int = 0
        private set

    init {
        // Enter the full ancestry of the initial state outermost → innermost.
        val effects = pathFromRoot(initial).flatMap { it.enterEffects(machineName) }
        applyEffects(effects)
    }

    /**
     * Dispatch one event using hierarchical delegation, then replace state if needed.
     * @return true if some state in the chain handled the event
     */
    fun handle(event: ConnEvent): Boolean {
        val fromLeaf = current
        val result = dispatchWithDelegation(fromLeaf, event)
        return when (result) {
            is HandleResult.Unhandled -> {
                applyEffects(
                    listOf(
                        Effect.LogRefusal(
                            machine = machineName,
                            state = fromLeaf.name,
                            event = event.label(),
                            reason = "no state in hierarchy accepted event",
                        ),
                    ),
                )
                false
            }
            is HandleResult.Handled -> {
                val next = result.next
                if (next !== fromLeaf) {
                    // Real transition: apply exit/transition/enter while still in fromLeaf.
                    val effects = planTransition(fromLeaf, next, event, result.effects)
                    applyEffects(effects)
                    current = next
                } else {
                    // Same leaf (e.g. Heartbeat): handler effects + observability.
                    val effects = result.effects + Effect.LogTransition(
                        machine = machineName,
                        from = fromLeaf.name,
                        event = event.label(),
                        to = next.name,
                    )
                    applyEffects(effects)
                }
                true
            }
        }
    }

    /**
     * PURPOSE: Hierarchical event dispatch — walk leaf → parent until handled.
     * This is the "behavioral inheritance" core without a statechart runtime.
     */
    private fun dispatchWithDelegation(start: ConnState, event: ConnEvent): HandleResult {
        var node: ConnState? = start
        while (node != null) {
            when (val r = node.onEvent(this, event)) {
                is HandleResult.Handled -> return r
                HandleResult.Unhandled -> node = node.parent
            }
        }
        return HandleResult.Unhandled
    }

    /**
     * PURPOSE: Pure assembly of hierarchical transition effects (LCA discipline).
     *
     * Order (UML-style): exit (innermost→outermost, exclusive of LCA) →
     * [Effect.LogTransition] → [handlerEffects] (transition actions) →
     * enter (outermost→innermost, exclusive of LCA).
     *
     * Does not assign [current] or apply effects — safe for tests to inspect
     * full Offline→Idle lists without going through the applying boundary.
     */
    fun planTransition(
        from: ConnState,
        to: ConnState,
        event: ConnEvent,
        handlerEffects: List<Effect> = emptyList(),
    ): List<Effect> {
        val fromPath = pathFromRoot(from)
        val toPath = pathFromRoot(to)
        val lcaIndex = commonPrefixLength(fromPath, toPath)
        val effects = mutableListOf<Effect>()

        // Exit states exclusive of LCA, from leaf toward root.
        for (i in fromPath.lastIndex downTo lcaIndex) {
            effects += fromPath[i].exitEffects(machineName)
        }

        effects += Effect.LogTransition(
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

    private fun applyEffects(effects: List<Effect>) {
        EffectInterpreter.apply(effects, log) {
            heartbeatCount += 1
        }
    }

    /** Build root → leaf path for hierarchical entry/exit ordering. */
    private fun pathFromRoot(leaf: ConnState): List<ConnState> {
        val chain = mutableListOf<ConnState>()
        var n: ConnState? = leaf
        while (n != null) {
            chain.add(0, n)
            n = n.parent
        }
        return chain
    }

    private fun commonPrefixLength(a: List<ConnState>, b: List<ConnState>): Int {
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && a[i] === b[i]) i++
        return i
    }
}
