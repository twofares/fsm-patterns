package fsm.sample

import fsm.sample.export.exportAllDocument

/**
 * PURPOSE: Dedicated launch path that prints product-evaluated transition tables
 * and structure-preserving Mermaid diagrams for multiple sample machines.
 *
 * Deterministic output (same inputs → same document) so two runs can be compared.
 * Run with: `gradle runExport` (or main class fsm.sample.ExportMainKt).
 */
fun main() {
    val doc = exportAllDocument()
    print(doc)
}
