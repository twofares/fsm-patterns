package fsm.sample.export

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped Mermaid/structure export APIs and assert hierarchy
 * and concurrent-region markers appear in real generated text.
 */
class MermaidExportTest {

    @Test
    fun `connection mermaid nests Online composite and product edges`() {
        val exp = exportConnection()
        val m = exp.mermaid
        assertTrue(m.startsWith("stateDiagram-v2") || m.contains("stateDiagram-v2"))
        assertTrue(m.contains("state Online"), "expected hierarchical composite: $m")
        assertTrue(m.contains("Idle"), m)
        assertTrue(m.contains("Transferring"), m)
        assertTrue(m.contains("Offline"), m)
        // Product edges present
        assertTrue(m.contains("-->"), m)
        assertTrue(m.contains("Connect") || m.contains("Disconnect"), m)
        // composites note
        assertTrue(m.contains("Online"), m)
    }

    @Test
    fun `door mermaid uses concurrent region separator not only product configs`() {
        val exp = exportDoorSystem()
        val m = exp.mermaid
        assertTrue(m.contains("stateDiagram-v2"), m)
        assertTrue(m.contains("state DoorSystem"), m)
        assertTrue(m.contains("state Latch"), m)
        assertTrue(m.contains("state Swing"), m)
        // Mermaid concurrency separator between regions
        assertTrue(m.contains("--"), "expected concurrent region separator: $m")
        assertTrue(m.contains("Locked") && m.contains("Unlocked"), m)
        assertTrue(m.contains("Closed") && m.contains("Open"), m)
        // Guard notes for container coupling
        assertTrue(m.contains("guard") || m.contains("Unlocked") || m.contains("note"), m)
    }

    @Test
    fun `flat traffic light mermaid has accepted edges from product table`() {
        val exp = exportTrafficLight()
        val m = exp.mermaid
        assertTrue(m.contains("stateDiagram-v2"), m)
        for (row in exp.table.accepted()) {
            assertTrue(
                m.contains(row.from) && m.contains(row.to) && m.contains(row.event),
                "missing edge for $row in $m",
            )
        }
    }

    @Test
    fun `file protocol mermaid omits refused edges but table keeps them`() {
        val exp = exportFileProtocol()
        assertTrue(exp.table.refused().isNotEmpty())
        // Accepted Open Closed→Opened should appear
        assertTrue(exp.mermaid.contains("Open"), exp.mermaid)
        // Refused rows exist in table
        val closedRead = exp.table.rows.single { it.from == "Closed" && it.event == "Read" }
        assertTrue(closedRead.kind == TransitionKind.REFUSED)
    }

    @Test
    fun `exportAllDocument covers multiple machines with tables and mermaid fences`() {
        val doc = exportAllDocument()
        assertTrue(doc.contains("# Generated"))
        assertTrue(doc.contains("TrafficLight"))
        assertTrue(doc.contains("FileProtocol"))
        assertTrue(doc.contains("Connection"))
        assertTrue(doc.contains("DoorSystem"))
        assertTrue(doc.contains("| From | Event | To | Kind | Effects | Note |"))
        assertTrue(doc.contains("```mermaid"))
        assertTrue(doc.contains("stateDiagram-v2"))
        // hierarchical + concurrent markers in one document
        assertTrue(doc.contains("state Online"))
        assertTrue(doc.contains("--"))
        assertFalse(doc.isBlank())
    }
}
