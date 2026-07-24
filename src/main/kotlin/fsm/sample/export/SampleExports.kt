package fsm.sample.export

/**
 * PURPOSE: High-level export API for all sample machines in this project.
 *
 * Combines Method 1 (product evaluation of pure APIs) with Method 2
 * (structure-preserving hierarchy and orthogonality) into Markdown + Mermaid.
 * Call sites: tests and [fsm.sample.ExportMain].
 */

/**
 * PURPOSE: TrafficLight flat export — complete table + flat Mermaid.
 */
fun exportTrafficLight(): MachineExport {
    val table = evaluateTrafficLight()
    return MachineExport(
        machine = table.machine,
        table = table,
        mermaid = formatMermaidFlat(table),
    )
}

/**
 * PURPOSE: FileProtocol CSP export — table includes REFUSED non-menu rows.
 */
fun exportFileProtocol(): MachineExport {
    val table = evaluateFileProtocol()
    // Mermaid: only accepted edges (refusals stay in the table)
    return MachineExport(
        machine = table.machine,
        table = table,
        mermaid = formatMermaidFlat(table, "FileProtocol (accepted edges; refusals in table)"),
    )
}

/**
 * PURPOSE: Connection hierarchical export — nested Online + product-probed edges.
 */
fun exportConnection(): MachineExport {
    val table = evaluateConnectionHierarchy()
    val mermaid = formatMermaidHierarchical(
        machine = "Connection",
        tree = connectionHierarchyTree(),
        table = table,
        compositeNames = connectionCompositeNames(),
    )
    return MachineExport(
        machine = table.machine,
        table = table,
        mermaid = mermaid,
    )
}

/**
 * PURPOSE: DoorSystem orthogonal export — concurrent Latch ∥ Swing, not product graph.
 */
fun exportDoorSystem(): MachineExport {
    val latch = evaluateLatchRegion()
    val swing = evaluateSwingRegion()
    val mermaid = formatMermaidOrthogonal(
        containerName = "DoorSystem",
        regions = listOf(
            ConcurrentRegion("Latch", latch),
            ConcurrentRegion("Swing", swing),
        ),
        guardNotes = listOf(
            "Container guards (not product states): Open only if Unlocked; Lock only if Closed",
        ),
    )
    // Combined table is region tables side-by-side via extraTables
    val combined = TransitionTable(
        machine = "DoorSystem",
        rows = latch.rows.map { it.copy(note = it.note ?: "region=Latch") } +
            swing.rows.map { it.copy(note = it.note ?: "region=Swing") },
    )
    return MachineExport(
        machine = "DoorSystem",
        table = combined,
        mermaid = mermaid,
        extraTables = listOf(latch, swing),
    )
}

/**
 * PURPOSE: Full multi-machine export document (tables + Mermaid fences).
 * Deterministic string for demo consistency checks.
 */
fun exportAllDocument(): String {
    val parts = listOf(
        exportTrafficLight(),
        exportFileProtocol(),
        exportConnection(),
        exportDoorSystem(),
    )
    val sb = StringBuilder()
    sb.appendLine("# Generated state transition tables and Mermaid diagrams")
    sb.appendLine()
    sb.appendLine("Product evaluation of shipped pure APIs + structure-preserving hierarchy/orthogonality.")
    sb.appendLine("Stdlib only — no FSM/statechart engines.")
    sb.appendLine()

    val tables = parts.flatMap { listOf(it.table) + it.extraTables }
    // Prefer per-machine sections with both table and diagram
    for (part in parts) {
        sb.appendLine("## ${part.machine}")
        sb.appendLine()
        sb.appendLine(formatMarkdownTable(part.table))
        sb.appendLine()
        for (extra in part.extraTables) {
            sb.appendLine(formatMarkdownTable(extra))
            sb.appendLine()
        }
        sb.appendLine("```mermaid")
        sb.appendLine(part.mermaid)
        sb.appendLine("```")
        sb.appendLine()
    }

    // Ensure we referenced tables list for potential future TOC (keep complete)
    sb.appendLine("<!-- machines: ${parts.map { it.machine }.joinToString()} tables=${tables.size} -->")
    return sb.toString()
}
