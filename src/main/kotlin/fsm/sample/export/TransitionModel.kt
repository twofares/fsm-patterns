package fsm.sample.export

/**
 * PURPOSE: Shared row model for product-evaluated transition tables.
 *
 * Separates "what transitions exist" (evaluation) from "how to render"
 * (Markdown / Mermaid). Not an FSM engine — just data for export.
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
 */
data class TransitionRow(
    val from: String,
    val event: String,
    val to: String,
    val kind: TransitionKind,
    val note: String? = null,
) {
    fun isSelf(): Boolean = from == to
}

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
