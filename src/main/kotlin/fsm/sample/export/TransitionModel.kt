package fsm.sample.export

/**
 * PURPOSE: Shared row model for product-evaluated transition tables.
 *
 * Separates "what transitions exist" (evaluation) from "how to render"
 * (Markdown / Mermaid). Not an FSM engine — just data for export.
 *
 * Each [TransitionRow] carries next-state outcome plus short labels of declared
 * effects from pure step APIs (never from per-machine interpreters).
 * Labels come from audit `exportLabel()` + domain `exportLabel()` — no global Effect ADT.
 */

enum class TransitionKind {
    /** Accepted engagement: state may change (or self-loop). */
    ACCEPTED,

    /** CSP-style or hierarchical refusal: event not offered; successor = from. */
    REFUSED,
}

/**
 * PURPOSE: One product-evaluated (from, event, to) observation.
 * [kind] distinguishes accepted transitions from refusals/self-successors.
 * [effects] are short labels of pure-declared effects for the same pair
 * (e.g. LogTransition, LogRefusal, IncHeartbeat, LogEntry(Idle)).
 */
data class TransitionRow(
    val from: String,
    val event: String,
    val to: String,
    val kind: TransitionKind,
    val effects: List<String> = emptyList(),
    val note: String? = null,
) {
    fun isSelf(): Boolean = from == to
}

/**
 * PURPOSE: Map a typed effect list to ordered short names for table cells.
 * [label] is the machine-local pure exportLabel (or equivalent).
 */
fun <E> effectShortNames(effects: List<E>, label: (E) -> String): List<String> =
    effects.map(label)

/**
 * PURPOSE: Named table of rows for one machine or region.
 */
data class TransitionTable(
    val machine: String,
    val rows: List<TransitionRow>,
) {
    fun accepted(): List<TransitionRow> = rows.filter { it.kind == TransitionKind.ACCEPTED }
    fun refused(): List<TransitionRow> = rows.filter { it.kind == TransitionKind.REFUSED }
}

/**
 * PURPOSE: Structure for hierarchical Mermaid composites (superstate nesting).
 * [children] empty means leaf; non-empty means composite superstate.
 */
data class StateNode(
    val name: String,
    val children: List<StateNode> = emptyList(),
) {
    val isComposite: Boolean get() = children.isNotEmpty()
}

/**
 * PURPOSE: One concurrent region (Orthogonal Component) with its own table edges.
 */
data class ConcurrentRegion(
    val name: String,
    val table: TransitionTable,
)

/**
 * PURPOSE: Full export artifact — tables + optional structural Mermaid text.
 */
data class MachineExport(
    val machine: String,
    val table: TransitionTable,
    val mermaid: String,
    val extraTables: List<TransitionTable> = emptyList(),
)
