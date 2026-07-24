package fsm.sample.csp

import fsm.sample.logging.RecordKind
import fsm.sample.logging.TransitionLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped [FileProtocol.offer] for CSP alphabet, menus, and refusal.
 */
class FileProtocolTest {

    @Test
    fun `alphabet contains Open Read Close`() {
        val p = FileProtocol()
        assertEquals(
            setOf(FileEvent.Open, FileEvent.Read, FileEvent.Close),
            p.alphabet,
        )
    }

    @Test
    fun `menu while Closed offers only Open`() {
        val p = FileProtocol()
        assertEquals(setOf(FileEvent.Open), p.menu())
    }

    @Test
    fun `menu while Opened offers Read and Close`() {
        val p = FileProtocol()
        val r = p.offer(FileEvent.Open)
        assertTrue(r is OfferResult.Accepted)
        assertEquals(setOf(FileEvent.Read, FileEvent.Close), p.menu())
    }

    @Test
    fun `Read while Closed is refused and state stays Closed`() {
        val log = TransitionLog()
        val p = FileProtocol(log = log)
        val r = p.offer(FileEvent.Read)
        assertTrue(r is OfferResult.Refused)
        assertEquals(FileState.Closed, p.current)
        assertTrue(log.snapshot().any { it.kind == RecordKind.REFUSAL })
    }

    @Test
    fun `happy path Open Read Close drives pure transitions and logs`() {
        val log = TransitionLog()
        val p = FileProtocol(log = log)

        assertTrue(p.offer(FileEvent.Open) is OfferResult.Accepted)
        assertEquals(FileState.Opened, p.current)

        assertTrue(p.offer(FileEvent.Read) is OfferResult.Accepted)
        assertEquals(FileState.Opened, p.current)

        assertTrue(p.offer(FileEvent.Close) is OfferResult.Accepted)
        assertEquals(FileState.Closed, p.current)

        val transitions = log.snapshot().filter { it.kind == RecordKind.TRANSITION }
        assertEquals(3, transitions.size)
        assertEquals(listOf("Open", "Read", "Close"), transitions.map { it.event })
        assertEquals("Closed", transitions.last().to)
    }

    @Test
    fun `Open while Opened is refused`() {
        val p = FileProtocol()
        p.offer(FileEvent.Open)
        val r = p.offer(FileEvent.Open)
        assertTrue(r is OfferResult.Refused)
        assertEquals(FileState.Opened, p.current)
    }
}
