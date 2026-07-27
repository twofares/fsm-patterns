package fsm.sample.flat

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.TimerIntent
import fsm.sample.logging.RecordKind
import fsm.sample.machine.TimerPort
import fsm.sample.machine.VirtualClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive the shipped flat FSM APIs ([transition], [TrafficLight.handle])
 * and assert next states, logs, and dwell timer arm/cancel/fire recipes.
 */
class TrafficLightTest {

    @Test
    fun `pure transition cycles Red Green Yellow`() {
        assertEquals(LightState.Green, transition(LightState.Red, LightEvent.Tick))
        assertEquals(LightState.Yellow, transition(LightState.Green, LightEvent.Tick))
        assertEquals(LightState.Red, transition(LightState.Yellow, LightEvent.Tick))
    }

    @Test
    fun `pure DwellElapsed advances like Tick`() {
        assertEquals(LightState.Green, transition(LightState.Red, LightEvent.DwellElapsed))
        assertEquals(LightState.Yellow, transition(LightState.Green, LightEvent.DwellElapsed))
        assertEquals(LightState.Red, transition(LightState.Yellow, LightEvent.DwellElapsed))
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

    @Test
    fun `pending empty after construct — no init arm for Red`() {
        val light = TrafficLight()
        assertTrue(light.timers.pending().isEmpty())
        assertEquals(LightState.Red, light.current)
    }

    @Test
    fun `pure step does not mutate TimerPort`() {
        val timers = TimerPort(VirtualClock())
        val pure = step(LightState.Red, LightEvent.Tick)
        assertTrue(timers.pending().isEmpty(), "pure step must not touch TimerPort")
        assertEquals(3, pure.domain.size)
        assertTrue(pure.domain[0] is LightEffect.Timer)
        assertTrue((pure.domain[0] as LightEffect.Timer).intent is TimerIntent.Cancel)
        assertTrue(pure.domain[1] is LightEffect.Timer)
        assertTrue((pure.domain[1] as LightEffect.Timer).intent is TimerIntent.Arm)
        assertEquals(LightEffect.DriveLamp("Green"), pure.domain[2])
    }

    @Test
    fun `Tick arms Green dwell then advance pollDue handle advances color`() {
        val clock = VirtualClock()
        val light = TrafficLight(clock = clock, timers = TimerPort(clock))

        light.handle(LightEvent.Tick)
        assertEquals(LightState.Green, light.current)
        val pending = light.timers.pending()
        assertEquals(1, pending.size)
        assertEquals(LIGHT_DWELL_ID, pending[0].id)
        assertEquals(DWELL_GREEN_MS, pending[0].dueAtMs - clock.nowMs())
        assertEquals(LightEvent.DwellElapsed.label(), pending[0].eventKey)

        clock.advance(DWELL_GREEN_MS)
        val due = light.timers.pollDue()
        assertEquals(1, due.size)
        assertEquals(LIGHT_DWELL_ID, due[0].id)
        assertEquals(LightEvent.DwellElapsed, lightEventFromTimerKey(due[0].eventKey))

        light.handle(LightEvent.DwellElapsed)
        assertEquals(LightState.Yellow, light.current)
        val after = light.timers.pending()
        assertEquals(1, after.size)
        assertEquals(DWELL_YELLOW_MS, after[0].dueAtMs - clock.nowMs())
    }

    @Test
    fun `fireDueTimeouts maps DwellElapsed and advances color`() {
        val clock = VirtualClock()
        val light = TrafficLight(clock = clock, timers = TimerPort(clock))
        light.handle(LightEvent.Tick) // Red → Green, arm green dwell
        clock.advance(DWELL_GREEN_MS)
        val fired = light.fireDueTimeouts()
        assertEquals(listOf(LightEvent.DwellElapsed), fired)
        assertEquals(LightState.Yellow, light.current)
    }

    @Test
    fun `cancel then re-arm on transition — Green dwell replaced by Red on Emergency`() {
        val clock = VirtualClock()
        val light = TrafficLight(clock = clock, timers = TimerPort(clock))
        light.handle(LightEvent.Tick) // → Green, arm 25s
        assertEquals(1, light.timers.pending().size)
        assertEquals(clock.nowMs() + DWELL_GREEN_MS, light.timers.pending()[0].dueAtMs)

        light.handle(LightEvent.Emergency) // → Red; Cancel green + Arm red
        assertEquals(LightState.Red, light.current)
        val pending = light.timers.pending()
        assertEquals(1, pending.size)
        assertEquals(LIGHT_DWELL_ID, pending[0].id)
        assertEquals(clock.nowMs() + DWELL_RED_MS, pending[0].dueAtMs)
        assertEquals(LightEvent.DwellElapsed.label(), pending[0].eventKey)
    }

    @Test
    fun `lightEventFromTimerKey maps DwellElapsed label only`() {
        assertEquals(LightEvent.DwellElapsed, lightEventFromTimerKey(LightEvent.DwellElapsed.label()))
        assertEquals(null, lightEventFromTimerKey("Tick"))
        assertEquals(null, lightEventFromTimerKey("unknown"))
    }
}
