package fsm.sample.interproc

/**
 * PURPOSE: Stand-in for **external session persistence** in inter-proc vignettes.
 *
 * In-process machines hold `var current` on a live context. A stateless server brain
 * instead loads pure state [S], runs pure step, and stores [next].
 * This holder is teaching-only (not durable storage, not multi-node consistency).
 *
 * One engagement owns one store ([StatelessPlayerEngagement] is one session).
 * Multi-session demos construct **multiple** engagements (each with its own store).
 *
 * Missing state means "no session yet" — callers seed with an initial state on first
 * engagement. Store runs on **every** engagement, including refuse (self-successor)
 * and first-contact refuse.
 */
class SessionStore<S> {
    private var held: S? = null

    fun load(): S? = held

    fun store(state: S) {
        held = state
    }

    fun isPresent(): Boolean = held != null

    fun clear() {
        held = null
    }
}
