package fsm.sample.flat

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.TimerIntent
import fsm.sample.logging.RecordKind
import fsm.sample.machine.VirtualClock
import fsm.sample.testsupport.assertDualSinksApplied
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive the shipped flat FSM APIs ([transition], [step], [automaticStep],
 * [TrafficLight.handle]) and assert next states, logs, dwell timer arm/cancel/fire,
 * and Flash → ε → Red automatic drain.
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
    fun `pure emergency forces Flash from any color including self-loop on Flash`() {
        assertEquals(LightState.Flash, transition(LightState.Green, LightEvent.Emergency))
        assertEquals(LightState.Flash, transition(LightState.Yellow, LightEvent.Emergency))
        assertEquals(LightState.Flash, transition(LightState.Red, LightEvent.Emergency))
        assertEquals(LightState.Flash, transition(LightState.Flash, LightEvent.Emergency))
    }

    @Test
    fun `pure Flash self-loops on Tick and DwellElapsed`() {
        assertEquals(LightState.Flash, transition(LightState.Flash, LightEvent.Tick))
        assertEquals(LightState.Flash, transition(LightState.Flash, LightEvent.DwellElapsed))
    }

    @Test
    fun `automaticStep Flash to Red with Cancel Arm DriveLamp and completion metric`() {
        val pure = automaticStep(LightState.Flash)!!
        assertEquals(LightState.Red, pure.next)
        assertEquals(domainEnterDwellColor(LightState.Red), pure.domain)
        assertTrue(
            pure.crossCutting.any {
                it is fsm.sample.effect.CrossCuttingEffect.Metric.Inc && it.name == "light.completion"
            },
        )
        assertTrue(pure.crossCutting.any { it is fsm.sample.effect.CrossCuttingEffect.Audit.LogTransition })
    }

    @Test
    fun `automaticStep null on Red Green Yellow`() {
        assertNull(automaticStep(LightState.Red))
        assertNull(automaticStep(LightState.Green))
        assertNull(automaticStep(LightState.Yellow))
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
        // External Emergency → Flash, then drainAutomatic applies ε → Red
        assertEquals(LightState.Red, light.current)

        val transitions = env.log.snapshot().filter { it.kind == RecordKind.TRANSITION }
        assertEquals(4, transitions.size)
        assertEquals("Red", transitions[0].from)
        assertEquals("Tick", transitions[0].event)
        assertEquals("Green", transitions[0].to)
        assertEquals("Yellow", transitions[1].to)
        assertEquals("Emergency", transitions[2].event)
        assertEquals("Flash", transitions[2].to)
        assertEquals("ε", transitions[3].event)
        assertEquals("Flash", transitions[3].from)
        assertEquals("Red", transitions[3].to)
        assertTrue(env.log.formatAll().contains("TRANSITION"))
    }

    @Test
    fun `handle Emergency dual audit — Flash then epsilon Red`() {
        val env = CrossCuttingEnv()
        val light = TrafficLight(initial = LightState.Green, crossCutting = env)
        light.handle(LightEvent.Emergency)
        assertEquals(LightState.Red, light.current)

        val transitions = env.log.snapshot().filter { it.kind == RecordKind.TRANSITION }
        assertEquals(2, transitions.size)
        assertEquals("Green", transitions[0].from)
        assertEquals("Emergency", transitions[0].event)
        assertEquals("Flash", transitions[0].to)
        assertEquals("Flash", transitions[1].from)
        assertEquals("ε", transitions[1].event)
        assertEquals("Red", transitions[1].to)

        assertDualSinksApplied(env, domainPortNonEmpty = light.port.commands.isNotEmpty())
        assertEquals(listOf("Flash", "Red"), light.port.commands)
        assertTrue(env.traces.spans.any { it.contains("light.emergency") })
        assertTrue(env.metrics.get("light.completion") >= 1)
        assertTrue(env.metrics.get("fsm.transitions") >= 2)
    }

    @Test
    fun `arm Green then Emergency leaves only Red dwell pending after drain`() {
        val clock = VirtualClock()
        val light = TrafficLight(clock = clock)
        light.handle(LightEvent.Tick) // → Green, arm 25s
        assertEquals(LightState.Green, light.current)
        assertEquals(1, light.timers.pending().size)
        assertEquals(clock.nowMs() + DWELL_GREEN_MS, light.timers.pending()[0].dueAtMs)

        light.handle(LightEvent.Emergency) // → Flash (cancel) then ε → Red (arm red)
        assertEquals(LightState.Red, light.current)
        val pending = light.timers.pending()
        assertEquals(1, pending.size)
        assertEquals(LIGHT_DWELL_ID, pending[0].id)
        assertEquals(clock.nowMs() + DWELL_RED_MS, pending[0].dueAtMs)
        assertEquals(LightEvent.DwellElapsed.label(), pending[0].eventKey)
    }

    @Test
    fun `pending empty after construct — no init arm for Red`() {
        val light = TrafficLight()
        assertTrue(light.timers.pending().isEmpty())
        assertEquals(LightState.Red, light.current)
    }

    @Test
    fun `pure step domain declares Cancel then Arm then DriveLamp`() {
        val pure = step(LightState.Red, LightEvent.Tick)
        assertEquals(domainEnterDwellColor(LightState.Green), pure.domain)
        assertEquals(
            listOf(
                LightEffect.Timer(TimerIntent.Cancel(LIGHT_DWELL_ID)),
                LightEffect.Timer(
                    TimerIntent.Arm(LIGHT_DWELL_ID, DWELL_GREEN_MS, LightEvent.DwellElapsed.label()),
                ),
                LightEffect.DriveLamp("Green"),
            ),
            pure.domain,
        )
    }

    @Test
    fun `pure Emergency step domain is Cancel and DriveLamp Flash only`() {
        val pure = step(LightState.Green, LightEvent.Emergency)
        assertEquals(LightState.Flash, pure.next)
        assertEquals(domainEnterFlash(), pure.domain)
        assertEquals(
            listOf(
                LightEffect.Timer(TimerIntent.Cancel(LIGHT_DWELL_ID)),
                LightEffect.DriveLamp("Flash"),
            ),
            pure.domain,
        )
    }

    @Test
    fun `pure Flash external event domain is Cancel and DriveLamp Flash self-loop`() {
        for (event in listOf(LightEvent.Tick, LightEvent.DwellElapsed, LightEvent.Emergency)) {
            val pure = step(LightState.Flash, event)
            assertEquals(LightState.Flash, pure.next, "event=$event")
            assertEquals(domainEnterFlash(), pure.domain, "event=$event")
        }
    }

    @Test
    fun `Tick arms Green dwell then advance pollDue handle advances color`() {
        val clock = VirtualClock()
        val light = TrafficLight(clock = clock)

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
        val light = TrafficLight(clock = clock)
        light.handle(LightEvent.Tick) // Red → Green, arm green dwell
        clock.advance(DWELL_GREEN_MS)
        val fired = light.fireDueTimeouts()
        assertEquals(listOf(LightEvent.DwellElapsed), fired)
        assertEquals(LightState.Yellow, light.current)
    }

    @Test
    fun `lightEventFromTimerKey maps DwellElapsed label only`() {
        assertEquals(LightEvent.DwellElapsed, lightEventFromTimerKey(LightEvent.DwellElapsed.label()))
        assertEquals(null, lightEventFromTimerKey("Tick"))
        assertEquals(null, lightEventFromTimerKey("unknown"))
    }
}
