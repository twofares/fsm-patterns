package fsm.sample.export

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.transition
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Transferring
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingEvent
import fsm.sample.orthogonal.SwingMachine
import fsm.sample.orthogonal.SwingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped product-evaluation APIs and assert rows match
 * evaluating the same pure transition/menu functions the generator calls.
 */
class ProductEvaluatorTest {

    @Test
    fun `traffic light product rows match shipped transition`() {
        val table = evaluateTrafficLight()
        val states = listOf(LightState.Red, LightState.Green, LightState.Yellow)
        val events = listOf(LightEvent.Tick, LightEvent.Emergency)
        assertEquals(states.size * events.size, table.rows.size)

        for (s in states) {
            for (e in events) {
                val expectedTo = transition(s, e).label()
                val row = table.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(expectedTo, row.to)
                assertEquals(TransitionKind.ACCEPTED, row.kind)
            }
        }
    }

    @Test
    fun `file protocol non-menu events are refused self-successors`() {
        val table = evaluateFileProtocol()
        val protocol = FileProtocol()

        for (s in listOf(FileState.Closed, FileState.Opened)) {
            val menu = protocol.menu(s)
            for (e in listOf(FileEvent.Open, FileEvent.Read, FileEvent.Close)) {
                val row = table.rows.single { it.from == s.label() && it.event == e.label() }
                if (e in menu) {
                    assertEquals(TransitionKind.ACCEPTED, row.kind)
                    assertEquals(protocol.pureWhenAccepted(s, e).label(), row.to)
                } else {
                    assertEquals(TransitionKind.REFUSED, row.kind)
                    assertEquals(s.label(), row.to) // self-successor
                    assertTrue(row.note?.contains("menu") == true)
                }
            }
        }
    }

    @Test
    fun `latch and swing region products match pureTransition`() {
        val latch = LatchMachine()
        val swing = SwingMachine()
        val latchTable = evaluateLatchRegion()
        val swingTable = evaluateSwingRegion()

        for (s in listOf(LatchState.Locked, LatchState.Unlocked)) {
            for (e in listOf(LatchEvent.Lock, LatchEvent.Unlock)) {
                val row = latchTable.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(latch.pureTransition(s, e).label(), row.to)
            }
        }
        for (s in listOf(SwingState.Closed, SwingState.Open)) {
            for (e in listOf(SwingEvent.OpenDoor, SwingEvent.CloseDoor)) {
                val row = swingTable.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(swing.pureTransition(s, e).label(), row.to)
            }
        }
    }

    @Test
    fun `connection probe matches hierarchical handle outcomes for key edges`() {
        val table = evaluateConnectionHierarchy()
        // Connect from Offline → Idle (shipped Offline handler)
        val connect = table.rows.single { it.from == Offline.name && it.event == ConnEvent.Connect.label() }
        assertEquals(Idle.name, connect.to)
        assertEquals(TransitionKind.ACCEPTED, connect.kind)

        // StartTransfer from Idle → Transferring
        val start = table.rows.single { it.from == Idle.name && it.event == ConnEvent.StartTransfer.label() }
        assertEquals(Transferring.name, start.to)

        // Disconnect from Transferring via Online superstate → Offline
        val disc = table.rows.single {
            it.from == Transferring.name && it.event == ConnEvent.Disconnect.label()
        }
        assertEquals(Offline.name, disc.to)

        // Illegal at Offline
        val refuse = table.rows.single {
            it.from == Offline.name && it.event == ConnEvent.StartTransfer.label()
        }
        assertEquals(TransitionKind.REFUSED, refuse.kind)
        assertEquals(Offline.name, refuse.to)
    }

    @Test
    fun `markdown table includes header and every product row`() {
        val table = evaluateTrafficLight()
        val md = formatMarkdownTable(table)
        assertTrue(md.contains("| From | Event | To | Kind | Note |"))
        assertTrue(md.contains("TrafficLight"))
        for (row in table.rows) {
            assertTrue(md.contains(row.from) && md.contains(row.event) && md.contains(row.to))
        }
    }
}
