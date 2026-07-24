package fsm.sample.export

/**
 * PURPOSE: Method 2 from research — structure-preserving Mermaid state diagrams.
 *
 * Emits `stateDiagram-v2` text using:
 * - Nested `state Super { ... }` for hierarchical composites
 * - Concurrent regions separated by `--` for orthogonal components
 * - Edges from product-evaluated [TransitionRow] (accepted only by default)
 *
 * Stdlib string building only — no Mermaid library dependency.
 */

/**
 * PURPOSE: Flat Mermaid from a transition table (all ACCEPTED edges as labeled arrows).
 * REFUSED rows omitted from the graph (listed only in tables).
 */
fun formatMermaidFlat(table: TransitionTable, diagramTitle: String = table.machine): String {
    val sb = StringBuilder()
    sb.appendLine("stateDiagram-v2")
    sb.appendLine("  %% $diagramTitle (flat product edges)")
    val states = linkedSetOf<String>()
    for (row in table.accepted()) {
        states += row.from
        states += row.to
    }
    for (s in states) {
        // Declare states so isolated nodes still appear if needed
        sb.appendLine("  $s")
    }
    for (row in table.accepted()) {
        if (row.from == row.to) {
            sb.appendLine("  ${row.from} --> ${row.to} : ${row.event}")
        } else {
            sb.appendLine("  ${row.from} --> ${row.to} : ${row.event}")
        }
    }
    return sb.toString().trimEnd()
}

/**
 * PURPOSE: Hierarchical Mermaid for Connection-style machines.
 *
 * [tree] supplies nesting (superstates); [table] supplies product-evaluated edges.
 * Edges whose endpoints are both under the same composite are emitted inside that
 * composite where possible; cross-boundary and superstate-handled edges stay outer.
 *
 * Composite [Online] nests Idle/Transferring; Offline is a sibling leaf.
 */
fun formatMermaidHierarchical(
    machine: String,
    tree: StateNode,
    table: TransitionTable,
    compositeNames: Set<String>,
): String {
    val sb = StringBuilder()
    sb.appendLine("stateDiagram-v2")
    sb.appendLine("  %% $machine (hierarchical composites + product edges)")

    // Emit structure from tree children under an implicit root.
    for (child in tree.children) {
        emitStateStructure(sb, child, indent = 1)
    }

    // Edges: accepted rows only
    for (row in table.accepted()) {
        // Mermaid allows edges between nested states by name at top level
        sb.appendLine("  ${row.from} --> ${row.to} : ${row.event}")
    }

    // Note which names are composites (for human readers / tests)
    if (compositeNames.isNotEmpty()) {
        sb.appendLine("  %% composites: ${compositeNames.sorted().joinToString(", ")}")
    }
    return sb.toString().trimEnd()
}

/**
 * PURPOSE: Emit nested `state Name { children }` structure recursively.
 */
private fun emitStateStructure(sb: StringBuilder, node: StateNode, indent: Int) {
    val pad = "  ".repeat(indent)
    if (node.isComposite) {
        sb.appendLine("${pad}state ${node.name} {")
        for (child in node.children) {
            emitStateStructure(sb, child, indent + 1)
        }
        sb.appendLine("$pad}")
    } else {
        sb.appendLine("${pad}${node.name}")
    }
}

/**
 * PURPOSE: Orthogonal concurrent-region Mermaid (DoorSystem style).
 *
 * Keeps region automata separate (no flat product of latch×swing configs).
 * Regions listed under a composite container with `--` separators.
 * Optional [guardNotes] become Mermaid notes for container-level coupling.
 */
fun formatMermaidOrthogonal(
    containerName: String,
    regions: List<ConcurrentRegion>,
    guardNotes: List<String> = emptyList(),
): String {
    val sb = StringBuilder()
    sb.appendLine("stateDiagram-v2")
    sb.appendLine("  %% $containerName (orthogonal concurrent regions)")
    sb.appendLine("  state $containerName {")
    regions.forEachIndexed { index, region ->
        if (index > 0) {
            sb.appendLine("    --")
        }
        sb.appendLine("    state ${region.name} {")
        // declare states
        val states = linkedSetOf<String>()
        for (row in region.table.accepted()) {
            states += row.from
            states += row.to
        }
        for (s in states) {
            sb.appendLine("      $s")
        }
        for (row in region.table.accepted()) {
            sb.appendLine("      ${row.from} --> ${row.to} : ${row.event}")
        }
        sb.appendLine("    }")
    }
    sb.appendLine("  }")
    for (note in guardNotes) {
        sb.appendLine("  note right of $containerName")
        sb.appendLine("    $note")
        sb.appendLine("  end note")
    }
    return sb.toString().trimEnd()
}
