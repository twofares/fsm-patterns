package fsm.sample.flat

import fsm.sample.logging.RecordKind
import fsm.sample.effect.CrossCuttingEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive the shipped flat FSM APIs ([transition], [TrafficLight.handle])
 * and assert next states plus logged transitions — no re-implementation.
 */
class TrafficLightTest {

    @Test
    fun `pure transition cycles Red Green Yellow`() {
        assertEquals(LightState.Green, transition(LightState.Red, LightEvent.Tick))
        assertEquals(LightState.Yellow, transition(LightState.Green, LightEvent.Tick))
        assertEquals(LightState.Red, transition(LightState.Yellow, LightEvent.Tick))
    }

    @Test
    fun `pure emergency forces Red from any color`() {
        assertEquals(LightState.Red, transition(LightState.Green, LightEvent.Emergency))
        assertEquals(LightState.Red, transition(LightState.Yellow, LightEvent.Emergency))
        assertEquals(LightState.Red, transition(LightState.Red, LightEvent.Emergency))
    }

    @Test
    fun `TrafficLight handle applies pure transition and logs mode changes`() {
        val env = CrossCuttingEnv()
        val light = TrafficLight(initial = LightState.Red, crossCutting = env)

        val afterTick = light.handle(LightEvent.Tick)
        assertEquals(LightState.Green, afterTick)
        assertEquals(LightState.Green, light.current)

        light.handle(LightEvent.Tick)
        assertEquals(LightState.Yellow, light.current)

        light.handle(LightEvent.Emergency)
        assertEquals(LightState.Red, light.current)

        val transitions = env.log.snapshot().filter { it.kind == RecordKind.TRANSITION }
        assertEquals(3, transitions.size)
        assertEquals("Red", transitions[0].from)
        assertEquals("Tick", transitions[0].event)
        assertEquals("Green", transitions[0].to)
        assertEquals("Yellow", transitions[1].to)
        assertEquals("Emergency", transitions[2].event)
        assertEquals("Red", transitions[2].to)
        assertTrue(env.log.formatAll().contains("TRANSITION"))
    }
}
