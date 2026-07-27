package fsm.sample.machine

import fsm.sample.effect.StepResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Prove pure [drainAutomatic] — empty/single/multi hop, cycle and bound throws.
 *
 * Fixtures are [StepResult]-shaped only (no TrafficLight / Connection coupling).
 * Domain type is a stand-in unit type; sinks are never applied here.
 */
class AutomaticDrainTest {

    /** Stand-in domain ADT — unused at the boundary in these pure-drain tests. */
    private object FixtureDomain

    private fun stepTo(next: String): StepResult<String, FixtureDomain> = StepResult(next = next)

    private fun chain(edges: Map<String, String>): (String) -> StepResult<String, FixtureDomain>? = { s ->
        edges[s]?.let { stepTo(it) }
    }

    private fun nextOf(result: StepResult<String, FixtureDomain>): String = result.next

    // -------------------------------------------------------------------------
    // Empty / hop drains
    // -------------------------------------------------------------------------

    @Test
    fun `empty drain when automaticStep returns null immediately`() {
        val result = drainAutomatic(
            start = "A",
            automaticStep = { null },
            nextOf = ::nextOf,
        )
        assertEquals("A", result.finalState)
        assertTrue(result.microsteps.isEmpty())
    }

    @Test
    fun `single hop drain`() {
        val result = drainAutomatic(
            start = "A",
            automaticStep = chain(mapOf("A" to "B")),
            nextOf = ::nextOf,
        )
        assertEquals("B", result.finalState)
        assertEquals(1, result.microsteps.size)
        assertEquals("B", result.microsteps.single().next)
    }

    @Test
    fun `multi hop drain follows chain until null`() {
        val result = drainAutomatic(
            start = "A",
            automaticStep = chain(mapOf("A" to "B", "B" to "C", "C" to "D")),
            nextOf = ::nextOf,
        )
        assertEquals("D", result.finalState)
        assertEquals(listOf("B", "C", "D"), result.microsteps.map { it.next })
    }

    // -------------------------------------------------------------------------
    // Cycle and bound
    // -------------------------------------------------------------------------

    @Test
    fun `cycle throws CYCLE when next state already visited`() {
        val ex = assertThrows(AutomaticBoundExceededException::class.java) {
            drainAutomatic(
                start = "A",
                automaticStep = chain(mapOf("A" to "B", "B" to "C", "C" to "A")),
                nextOf = ::nextOf,
            )
        }
        assertEquals(AutomaticBoundExceededException.Reason.CYCLE, ex.reason)
        assertEquals(3, ex.stepsTaken)
    }

    @Test
    fun `self loop is a cycle on first microstep`() {
        val ex = assertThrows(AutomaticBoundExceededException::class.java) {
            drainAutomatic(
                start = "A",
                automaticStep = chain(mapOf("A" to "A")),
                nextOf = ::nextOf,
            )
        }
        assertEquals(AutomaticBoundExceededException.Reason.CYCLE, ex.reason)
        assertEquals(1, ex.stepsTaken)
    }

    @Test
    fun `detectCycles false still fails closed with BOUND on a cycle`() {
        // Without cycle detection a self-loop would spin; bound must still throw BOUND (not CYCLE).
        val max = 4
        val ex = assertThrows(AutomaticBoundExceededException::class.java) {
            drainAutomatic(
                start = "A",
                automaticStep = chain(mapOf("A" to "A")),
                nextOf = ::nextOf,
                detectCycles = false,
                maxMicrosteps = max,
            )
        }
        assertEquals(AutomaticBoundExceededException.Reason.BOUND, ex.reason)
        assertEquals(max, ex.stepsTaken)
    }

    @Test
    fun `40-hop acyclic chain exceeds default bound and throws BOUND`() {
        // 40 distinct hops with DEFAULT_MAX_MICROSTEPS = 32 → BOUND (not CYCLE).
        val edges = (0 until 40).associate { i -> "S$i" to "S${i + 1}" }
        assertEquals(32, DEFAULT_MAX_MICROSTEPS)

        val ex = assertThrows(AutomaticBoundExceededException::class.java) {
            drainAutomatic(
                start = "S0",
                automaticStep = chain(edges),
                nextOf = ::nextOf,
            )
        }
        assertEquals(AutomaticBoundExceededException.Reason.BOUND, ex.reason)
        assertEquals(DEFAULT_MAX_MICROSTEPS, ex.stepsTaken)
    }

    @Test
    fun `custom maxMicrosteps allows short chains that would exceed a lower bound`() {
        val edges = mapOf("A" to "B", "B" to "C", "C" to "D")
        val ok = drainAutomatic(
            start = "A",
            automaticStep = chain(edges),
            nextOf = ::nextOf,
            maxMicrosteps = 3,
        )
        assertEquals("D", ok.finalState)
        assertEquals(3, ok.microsteps.size)

        val ex = assertThrows(AutomaticBoundExceededException::class.java) {
            drainAutomatic(
                start = "A",
                automaticStep = chain(edges),
                nextOf = ::nextOf,
                maxMicrosteps = 2,
            )
        }
        assertEquals(AutomaticBoundExceededException.Reason.BOUND, ex.reason)
        assertEquals(2, ex.stepsTaken)
    }

    @Test
    fun `exact maxMicrosteps then null is success not BOUND`() {
        val edges = mapOf("A" to "B", "B" to "C")
        val result = drainAutomatic(
            start = "A",
            automaticStep = chain(edges),
            nextOf = ::nextOf,
            maxMicrosteps = 2,
        )
        assertEquals("C", result.finalState)
        assertEquals(2, result.microsteps.size)
    }

    @Test
    fun `maxMicrosteps zero is empty success when quiescent else BOUND`() {
        val quiescent = drainAutomatic(
            start = "A",
            automaticStep = { null },
            nextOf = ::nextOf,
            maxMicrosteps = 0,
        )
        assertEquals("A", quiescent.finalState)
        assertTrue(quiescent.microsteps.isEmpty())

        val ex = assertThrows(AutomaticBoundExceededException::class.java) {
            drainAutomatic(
                start = "A",
                automaticStep = chain(mapOf("A" to "B")),
                nextOf = ::nextOf,
                maxMicrosteps = 0,
            )
        }
        assertEquals(AutomaticBoundExceededException.Reason.BOUND, ex.reason)
        assertEquals(0, ex.stepsTaken)
    }

    @Test
    fun `negative maxMicrosteps is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            drainAutomatic(
                start = "A",
                automaticStep = { null },
                nextOf = ::nextOf,
                maxMicrosteps = -1,
            )
        }
    }
}
