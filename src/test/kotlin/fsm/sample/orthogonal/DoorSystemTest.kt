package fsm.sample.orthogonal

import fsm.sample.logging.RecordKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped [DoorSystem] orthogonal composition — independent
 * component updates, guarded cross-region refusal, and shared logging.
 */
class DoorSystemTest {

    @Test
    fun `unlock then open updates both independent regions`() {
        val door = DoorSystem()
        assertEquals(LatchState.Locked, door.latch.current)
        assertEquals(SwingState.Closed, door.swing.current)

        assertTrue(door.handle(DoorEvent.Unlock))
        assertEquals(LatchState.Unlocked, door.latch.current)
        assertEquals(SwingState.Closed, door.swing.current)

        assertTrue(door.handle(DoorEvent.Open))
        assertEquals(LatchState.Unlocked, door.latch.current)
        assertEquals(SwingState.Open, door.swing.current)

        val transitions = door.crossCutting.log.snapshot().filter { it.kind == RecordKind.TRANSITION }
        assertTrue(transitions.any { it.machine == "Latch" && it.to == "Unlocked" })
        assertTrue(transitions.any { it.machine == "Swing" && it.to == "Open" })
    }

    @Test
    fun `open while locked is refused and swing unchanged`() {
        val door = DoorSystem()
        assertFalse(door.handle(DoorEvent.Open))
        assertEquals(SwingState.Closed, door.swing.current)
        assertEquals(LatchState.Locked, door.latch.current)
        assertTrue(
            door.crossCutting.log.snapshot().any {
                it.kind == RecordKind.REFUSAL && it.detail?.contains("locked") == true
            },
        )
    }

    @Test
    fun `lock while open is refused and latch stays unlocked`() {
        val door = DoorSystem()
        door.handle(DoorEvent.Unlock)
        door.handle(DoorEvent.Open)
        assertFalse(door.handle(DoorEvent.Lock))
        assertEquals(LatchState.Unlocked, door.latch.current)
        assertEquals(SwingState.Open, door.swing.current)
    }

    @Test
    fun `close then lock succeeds via component dispatch`() {
        val door = DoorSystem()
        door.handle(DoorEvent.Unlock)
        door.handle(DoorEvent.Open)
        assertTrue(door.handle(DoorEvent.Close))
        assertEquals(SwingState.Closed, door.swing.current)
        assertTrue(door.handle(DoorEvent.Lock))
        assertEquals(LatchState.Locked, door.latch.current)
        assertEquals("latch=Locked, swing=Closed", door.configuration())
    }
}
