package fsm.sample.interproc

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.OfferOutcome
import fsm.sample.machine.TimerPort
import fsm.sample.machine.VirtualClock
import fsm.sample.machine.applyOffer

/**
 * PURPOSE: Stateless **engagement boundary** for inter-proc player control.
 *
 * **One session per instance** — multi-session demos create **N engagement instances**
 * (optionally sharing a [VirtualClock]). Isolation comes from **not sharing**
 * [store] / [player] / [timers]; [sessionId] is instance identity for results and
 * logs only (not a store key). Prefer distinct ids when logging multi-instance demos;
 * the default `"default"` collides only if callers reuse it carelessly.
 *
 * **Injectables:** [store], [player], and [crossCutting] are ctor parameters mainly
 * for tests. Prefer defaults so each instance owns fresh sinks. [timers] is always
 * constructed from [clock] (never ctor-injected) so bag and clock cannot desync.
 * Injecting a **shared** store/player/CC across instances couples pure state and
 * domain/CC sinks — do not do that for multi-session demos.
 *
 * Recipe (every request / timer fire):
 * 1. **Load** pure [PlayerState] from [store] (seed [initial] if missing)
 * 2. Pure [stepOffer] — no ports, clocks, or mutable machine fields
 * 3. [applyOffer]: **assign** (persist [OfferOutcome.next]) → CC → domain if accept
 *    - assign closes over [store].store
 *    - domain lambda closes over [player] + [timers] (same multi-port pattern as
 *      TrafficLight / Connection — different bindings, same helper)
 *
 * Store always runs (including refuse self-successor and first-contact refuse).
 * On **Accepted** only: deliver ordered domain → [ThinPlayerPort] + [TimerPort].
 * On **Refused**: no domain delivery (refuse hygiene).
 *
 * Teaching contrast with in-process: **what assign and applyDomain close over**
 * (external persist + remote deliver + boundary-owned timers), not a forked apply
 * ladder. Pure surfaces stay the same (`stepOffer` → [OfferOutcome]).
 *
 * Timers: Arm/Cancel update [timers]; [fireDueTimeouts] polls due entries and
 * re-engages with sealed [PlayerEvent] — not same-stack drain on a live context field.
 *
 * **Clock:** [clock] is [VirtualClock] (parity with TrafficLight / Connection).
 * Demos call `engagement.clock.advance(...)` then [fireDueTimeouts].
 *
 * Teaching analogy: server brain + thin player. Not a multi-process runtime.
 */
data class EngagementResult(
    val sessionId: String,
    val accepted: Boolean,
    val reason: String?,
    val previous: PlayerState,
    val next: PlayerState,
    /** Domain commands delivered this engagement (empty on refuse). */
    val delivered: List<ThinPlayerCommand>,
)

/**
 * One due-timer re-engagement outcome.
 *
 * [event] is the sealed event that was offered; [result.accepted] is whether pure
 * stepOffer accepted it. A due schedule entry is always removed by [pollDue] before
 * engage — “fired” here means **re-engaged**, not “accepted”. If state and schedule
 * desync, [result] may be refused while the timer still left the bag.
 */
data class DueFireResult(
    val event: PlayerEvent,
    val result: EngagementResult,
)

/**
 * Client-facing engagement boundary: one fixed [sessionId] per instance.
 *
 * [sessionId] labels this instance (results / logs). It does **not** key [store];
 * sharing one [store] across two instances silently shares pure state regardless
 * of distinct ids. Default construction gives each instance its own store, player,
 * timers, and CC env. See class PURPOSE for injectable ownership rules.
 */
class StatelessPlayerEngagement(
    val sessionId: String = "default",
    val clock: VirtualClock = VirtualClock(),
    val store: SessionStore<PlayerState> = SessionStore(),
    val player: ThinPlayerPort = ThinPlayerPort(),
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    private val machineName: String = "PlayerControl",
    private val initial: PlayerState = PlayerState.Idle,
) {
    /** Boundary-owned timer bag on [clock] (inter-proc counterpart of co-located TimerPort). */
    val timers: TimerPort = TimerPort(clock)

    /**
     * PURPOSE: One stateless engagement — load → pure step → [applyOffer]
     * (assign → CC → domain if accept).
     *
     * Assign always runs (including refuse / first-contact refuse seeding Idle).
     */
    fun engage(event: PlayerEvent): EngagementResult {
        val previous = store.load() ?: initial
        val outcome = stepOffer(previous, event, machineName)
        val before = player.commands.size
        applyOffer(
            outcome,
            crossCutting,
            Unit,
            applyDomain = { domain, _ ->
                applyDomainPlayerRemote(domain, player, timers)
            },
            assign = { store.store(it) },
        )
        return EngagementResult(
            sessionId = sessionId,
            accepted = outcome is OfferOutcome.Accepted,
            reason = (outcome as? OfferOutcome.Refused)?.reason,
            previous = previous,
            next = outcome.next,
            delivered = if (outcome is OfferOutcome.Accepted) {
                player.commands.drop(before)
            } else {
                emptyList()
            },
        )
    }

    /**
     * PURPOSE: External timer due drain for this engagement’s session.
     *
     * Polls **all** currently due entries first ([TimerPort.pollDue]), then [engage]
     * each in order — same recipe as in-process TrafficLight / Connection
     * `fireDueTimeouts`. If a mid-loop engage fails, remaining dues are already
     * removed from the bag (intentional parity with live contexts).
     *
     * Returns [DueFireResult] per due entry: [DueFireResult.event] was re-engaged;
     * check [DueFireResult.result.accepted] for accept vs refuse (desync case).
     */
    fun fireDueTimeouts(): List<DueFireResult> {
        val fired = mutableListOf<DueFireResult>()
        for (due in timers.pollDue()) {
            val event = playerEventFromTimerKey(due.eventKey)
                ?: error("unknown timer eventKey=${due.eventKey}")
            val result = engage(event)
            fired += DueFireResult(event = event, result = result)
        }
        return fired
    }

    /** Current pure state, or null if never engaged. */
    fun state(): PlayerState? = store.load()
}
