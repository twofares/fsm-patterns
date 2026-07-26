package fsm.sample.machine

/**
 * PURPOSE: Tiny list-shaped domain recording sink — **not** an effect engine.
 *
 * Use only for in-memory "record what was applied" ports in tests and demos.
 * List-shaped vignette ports **are** this bag (subclass) — named accessors
 * (`commands` / `ops` / `sessions` / `actuators`) and domain `applyDomain*` stay local.
 *
 * Ports remain vignette-local by law: do not invent a shared DomainPort hierarchy
 * or generic apply engine. Heartbeat counters and multi-list ports stay specialized
 * on the same vignette port subclass (not a private bag + pass-through layer).
 */
open class RecordingPort<T> {
    private val _items = mutableListOf<T>()
    val items: List<T> get() = _items.toList()

    fun record(item: T) {
        _items += item
    }

    open fun clear() {
        _items.clear()
    }
}
