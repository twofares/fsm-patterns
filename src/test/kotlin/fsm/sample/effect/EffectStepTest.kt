package fsm.sample.effect

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.OfferResult
import fsm.sample.csp.OfferStep
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.TrafficLight
import fsm.sample.flat.step
import fsm.sample.flat.transition
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnectionContext
import fsm.sample.gof.HandleResult
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Online
import fsm.sample.logging.RecordKind
import fsm.sample.logging.TransitionLog
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorSystem
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped pure steps and [EffectInterpreter] without re-implementing
 * transition rules. Asserts effect lists from pure APIs and boundary application.
 */
class EffectStepTest {

    @Test
    fun `TrafficLight pure step declares LogTransition matching transition`() {
        val result = step(LightState.Red, LightEvent.Tick)
        assertEquals(LightState.Green, result.next)
        assertEquals(transition(LightState.Red, LightEvent.Tick), result.next)
        assertEquals(1, result.effects.size)
        val e = result.effects.single() as Effect.LogTransition
        assertEquals("TrafficLight", e.machine)
        assertEquals("Red", e.from)
        assertEquals("Tick", e.event)
        assertEquals("Green", e.to)
    }

    @Test
    fun `TrafficLight pure emergency step declares effect without mutating log`() {
        val log = TransitionLog()
        val result = step(LightState.Yellow, LightEvent.Emergency)
        assertEquals(LightState.Red, result.next)
        assertTrue(log.snapshot().isEmpty()) // pure step: no boundary yet
        EffectInterpreter.apply(result.effects, log)
        val snap = log.snapshot()
        assertEquals(1, snap.size)
        assertEquals(RecordKind.TRANSITION, snap[0].kind)
        assertEquals("Emergency", snap[0].event)
        assertEquals("Red", snap[0].to)
    }

    @Test
    fun `TrafficLight handle boundary applies step effects to log`() {
        val log = TransitionLog()
        val light = TrafficLight(log = log)
        light.handle(LightEvent.Tick)
        assertEquals(LightState.Green, light.current)
        assertEquals(1, log.snapshot().filter { it.kind == RecordKind.TRANSITION }.size)
    }

    @Test
    fun `FileProtocol stepOffer refuses with LogRefusal and keeps state`() {
        val p = FileProtocol()
        val result = p.stepOffer(FileState.Closed, FileEvent.Read)
        assertTrue(result is OfferStep.Refused)
        assertEquals(FileState.Closed, result.next)
        val refusal = result.effects.filterIsInstance<Effect.LogRefusal>().single()
        assertEquals("Closed", refusal.state)
        assertEquals("Read", refusal.event)
        assertTrue(refusal.reason.contains("menu"))
        assertEquals((result as OfferStep.Refused).reason, refusal.reason)
    }

    @Test
    fun `FileProtocol stepOffer accepted declares LogTransition via pureWhenAccepted`() {
        val p = FileProtocol()
        val result = p.stepOffer(FileState.Closed, FileEvent.Open)
        assertTrue(result is OfferStep.Accepted)
        assertEquals(FileState.Opened, result.next)
        assertEquals(p.pureWhenAccepted(FileState.Closed, FileEvent.Open), result.next)
        val t = result.effects.filterIsInstance<Effect.LogTransition>().single()
        assertEquals("Open", t.event)
        assertEquals("Opened", t.to)
    }

    @Test
    fun `FileProtocol offer boundary applies refusal and transition effects`() {
        val log = TransitionLog()
        val p = FileProtocol(log = log)
        assertTrue(p.offer(FileEvent.Read) is OfferResult.Refused)
        assertTrue(p.offer(FileEvent.Open) is OfferResult.Accepted)
        val kinds = log.snapshot().map { it.kind }
        assertTrue(kinds.contains(RecordKind.REFUSAL))
        assertTrue(kinds.contains(RecordKind.TRANSITION))
    }

    @Test
    fun `Online onEvent declares IncHeartbeat without mutating heartbeatCount`() {
        val ctx = ConnectionContext(log = null)
        ctx.handle(ConnEvent.Connect)
        assertEquals(Idle, ctx.current)
        assertEquals(0, ctx.heartbeatCount)

        // Pure-ish handler layer: declare effect only; counter stays 0 until boundary.
        val handled = Online.onEvent(ctx, ConnEvent.Heartbeat)
        assertTrue(handled is HandleResult.Handled)
        val h = handled as HandleResult.Handled
        assertEquals(Idle, h.next)
        assertEquals(listOf(Effect.IncHeartbeat), h.effects)
        assertEquals(0, ctx.heartbeatCount)

        // Boundary applies IncHeartbeat.
        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(1, ctx.heartbeatCount)
    }

    @Test
    fun `Connection heartbeat handle applies IncHeartbeat at boundary`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        ctx.handle(ConnEvent.Connect)
        assertEquals(Idle, ctx.current)
        assertEquals(0, ctx.heartbeatCount)

        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(Idle, ctx.current)
        assertEquals(1, ctx.heartbeatCount)
        assertTrue(
            log.snapshot().any {
                it.kind == RecordKind.TRANSITION && it.event == "Heartbeat"
            },
        )
    }

    @Test
    fun `planTransition assembles exit transition enter without applying`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        log.clear() // drop init Offline entry so we only inspect plan list

        val planned = ctx.planTransition(Offline, Idle, ConnEvent.Connect)
        // Offline exit, Offline→Idle transition, Online enter, Idle enter
        assertEquals(4, planned.size)
        assertTrue(planned[0] is Effect.LogExit && (planned[0] as Effect.LogExit).state == "Offline")
        assertTrue(planned[1] is Effect.LogTransition)
        val t = planned[1] as Effect.LogTransition
        assertEquals("Offline", t.from)
        assertEquals("Connect", t.event)
        assertEquals("Idle", t.to)
        assertTrue(planned[2] is Effect.LogEntry && (planned[2] as Effect.LogEntry).state == "Online")
        assertTrue(planned[3] is Effect.LogEntry && (planned[3] as Effect.LogEntry).state == "Idle")
        // Pure plan: log still empty after clear
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `Connection entry exit still appear via effect boundary`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        assertEquals(Offline, ctx.current)
        assertTrue(log.snapshot().any { it.kind == RecordKind.ENTRY && it.to == "Offline" })
        ctx.handle(ConnEvent.Connect)
        val entries = log.snapshot().filter { it.kind == RecordKind.ENTRY }.map { it.to }
        assertTrue(entries.contains("Online"))
        assertTrue(entries.contains("Idle"))
    }

    @Test
    fun `Latch pure step declares LogTransition for orthogonal region`() {
        val latch = LatchMachine()
        val result = latch.step(LatchState.Locked, LatchEvent.Unlock)
        assertEquals(LatchState.Unlocked, result.next)
        val t = result.effects.filterIsInstance<Effect.LogTransition>().single()
        assertEquals("Latch", t.machine)
        assertEquals("Unlock", t.event)
        assertEquals("Unlocked", t.to)
    }

    @Test
    fun `DoorSystem pure guard declares LogRefusal without mutating log`() {
        val door = DoorSystem()
        val effects = door.guardRefusalEffects(
            DoorEvent.Open,
            latchState = LatchState.Locked,
            swingState = SwingState.Closed,
        )
        assertEquals(1, effects.size)
        val r = effects.single() as Effect.LogRefusal
        assertTrue(r.reason.contains("locked"))
        // Pure guard: container log still empty
        assertTrue(door.log.snapshot().isEmpty())
    }

    @Test
    fun `DoorSystem guard refusal goes through EffectInterpreter`() {
        val door = DoorSystem()
        assertFalse(door.handle(DoorEvent.Open))
        assertTrue(
            door.log.snapshot().any {
                it.kind == RecordKind.REFUSAL && it.detail?.contains("locked") == true
            },
        )
    }
}
