package fsm.sample.gof

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: GoF State pattern + hierarchical superstate delegation + entry/exit.
 *
 * Disciplines demonstrated (ordinary OO, no statechart engine):
 * 1. GoF State: context holds current state object; transitions replace it.
 * 2. Hierarchical FSMs: composite/leaf states; unhandled events bubble to parent
 *    (behavioral inheritance — program only differences from the superstate).
 * 3. Entry/exit actions: run on every enter/leave regardless of which transition
 *    was taken (outermost→innermost on entry, reverse on exit for hierarchy).
 *
 * Domain vignette: a network connection with hierarchy
 *   Offline (leaf)
 *   Online (superstate)
 *     └── Idle (leaf)
 *     └── Transferring (leaf)
 *
 * Events unhandled in Idle/Transferring may still be handled by Online.
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
 * - [Handled] carries the next leaf state (and optionally records heartbeat side effects).
 * - [Unhandled] means "delegate to parent" (hierarchical discipline).
 */
sealed class HandleResult {
    data class Handled(val next: ConnState) : HandleResult()
    data object Unhandled : HandleResult()
}

/**
 * PURPOSE: State hierarchy nodes for GoF + hierarchical delegation.
 *
 * [parent] links form the superstate chain. Leaf states override [onEvent];
 * composite Online also handles events that leaves leave unhandled.
 * [onEnter]/[onExit] are the guaranteed setup/teardown hooks.
 */
sealed class ConnState {
    abstract val name: String
    open val parent: ConnState? get() = null

    /** Entry action: guaranteed init when this state is entered. */
    open fun onEnter(ctx: ConnectionContext) {
        ctx.log?.recordEntry(ctx.machineName, name)
    }

    /** Exit action: guaranteed cleanup when this state is left. */
    open fun onExit(ctx: ConnectionContext) {
        ctx.log?.recordExit(ctx.machineName, name)
    }

    /**
     * PURPOSE: Per-state behavior. Default is Unhandled so events bubble up.
     * Leaves and composites override only the events they care about.
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
            // Superstate-only reaction: stay in the current leaf, but count heartbeat.
            ctx.heartbeatCount += 1
            HandleResult.Handled(ctx.current)
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
 * 2. If still Unhandled, refuse (log) and keep state.
 * 3. If Handled with a different next state, exit path from leaf up to LCA,
 *    then enter path down to the new leaf.
 */
class ConnectionContext(
    initial: ConnState = Offline,
    val log: TransitionLog? = null,
    val machineName: String = "Connection",
) {
    var current: ConnState = initial
        private set

    /** Side-effect counter used by Online.Heartbeat (superstate-only reaction). */
    var heartbeatCount: Int = 0

    init {
        // Enter the full ancestry of the initial state outermost → innermost.
        enterPath(pathFromRoot(initial))
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
                log?.recordRefusal(
                    machine = machineName,
                    state = fromLeaf.name,
                    event = event.label(),
                    reason = "no state in hierarchy accepted event",
                )
                false
            }
            is HandleResult.Handled -> {
                val next = result.next
                if (next !== fromLeaf) {
                    transitionTo(fromLeaf, next, event)
                } else {
                    // Same leaf (e.g. Heartbeat): still record a logical transition for observability.
                    log?.recordTransition(
                        machine = machineName,
                        from = fromLeaf.name,
                        event = event.label(),
                        to = next.name,
                    )
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
     * PURPOSE: Exit current path, enter new path with LCA discipline.
     * Exit: innermost → outermost (leaf first). Enter: outermost → innermost.
     */
    private fun transitionTo(from: ConnState, to: ConnState, event: ConnEvent) {
        val fromPath = pathFromRoot(from)
        val toPath = pathFromRoot(to)
        val lcaIndex = commonPrefixLength(fromPath, toPath)

        // Exit states exclusive of LCA, from leaf toward root.
        for (i in fromPath.lastIndex downTo lcaIndex) {
            fromPath[i].onExit(this)
        }

        log?.recordTransition(
            machine = machineName,
            from = from.name,
            event = event.label(),
            to = to.name,
        )

        // Enter states exclusive of LCA, from root toward leaf.
        for (i in lcaIndex until toPath.size) {
            toPath[i].onEnter(this)
        }

        current = to
    }

    private fun enterPath(path: List<ConnState>) {
        for (s in path) {
            s.onEnter(this)
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
