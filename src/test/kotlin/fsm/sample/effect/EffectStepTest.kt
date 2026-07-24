package fsm.sample.effect

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileEffect
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.OfferResult
import fsm.sample.csp.OfferStep
import fsm.sample.csp.interpretFile
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightEffect
import fsm.sample.flat.LightState
import fsm.sample.flat.TrafficLight
import fsm.sample.flat.interpretLight
import fsm.sample.flat.step
import fsm.sample.flat.transition
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnectionContext
import fsm.sample.gof.ConnectionEffect
import fsm.sample.gof.HandleResult
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Online
import fsm.sample.logging.RecordKind
import fsm.sample.logging.TransitionLog
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorEffect
import fsm.sample.orthogonal.DoorSystem
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchEffect
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped pure steps and per-machine interpreters without
 * re-implementing transition rules. Asserts domain vs cross-cutting effects:
 * Connection declares [ConnectionEffect.IncHeartbeat]; TrafficLight only [LightEffect.Audit].
 */
class EffectStepTest {

    @Test
    fun `TrafficLight pure step declares LightEffect audit matching transition`() {
        val result = step(LightState.Red, LightEvent.Tick)
        assertEquals(LightState.Green, result.next)
        assertEquals(transition(LightState.Red, LightEvent.Tick), result.next)
        assertEquals(1, result.effects.size)
        val e = result.effects.single() as LightEffect.Audit
        val t = e.audit as AuditEffect.LogTransition
        assertEquals("TrafficLight", t.machine)
        assertEquals("Red", t.from)
        assertEquals("Tick", t.event)
        assertEquals("Green", t.to)
    }

    @Test
    fun `TrafficLight pure emergency step declares effect without mutating log`() {
        val log = TransitionLog()
        val result = step(LightState.Yellow, LightEvent.Emergency)
        assertEquals(LightState.Red, result.next)
        assertTrue(log.snapshot().isEmpty()) // pure step: no boundary yet
        interpretLight(result.effects, log)
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
    fun `TrafficLight steps never involve Connection heartbeat types`() {
        // Domain separation: LightEffect is only Audit; IncHeartbeat is Connection-only.
        val all = listOf(
            step(LightState.Red, LightEvent.Tick),
            step(LightState.Green, LightEvent.Tick),
            step(LightState.Yellow, LightEvent.Emergency),
        )
        for (r in all) {
            assertTrue(r.effects.isNotEmpty())
            assertTrue(r.effects.all { it is LightEffect.Audit })
            // Compile-time: List<LightEffect> cannot hold ConnectionEffect.IncHeartbeat.
        }
    }

    @Test
    fun `FileProtocol stepOffer refuses with FileEffect audit and keeps state`() {
        val p = FileProtocol()
        val result = p.stepOffer(FileState.Closed, FileEvent.Read)
        assertTrue(result is OfferStep.Refused)
        assertEquals(FileState.Closed, result.next)
        val audit = result.effects.filterIsInstance<FileEffect.Audit>().single()
        val refusal = audit.audit as AuditEffect.LogRefusal
        assertEquals("Closed", refusal.state)
        assertEquals("Read", refusal.event)
        assertTrue(refusal.reason.contains("menu"))
        assertEquals((result as OfferStep.Refused).reason, refusal.reason)
    }

    @Test
    fun `FileProtocol stepOffer accepted declares transition audit via pureWhenAccepted`() {
        val p = FileProtocol()
        val result = p.stepOffer(FileState.Closed, FileEvent.Open)
        assertTrue(result is OfferStep.Accepted)
        assertEquals(FileState.Opened, result.next)
        assertEquals(p.pureWhenAccepted(FileState.Closed, FileEvent.Open), result.next)
        val audit = result.effects.filterIsInstance<FileEffect.Audit>().single()
        val t = audit.audit as AuditEffect.LogTransition
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

        // Pure-ish handler layer: declare domain effect only; counter stays 0 until boundary.
        val handled = Online.onEvent(ctx, ConnEvent.Heartbeat)
        assertTrue(handled is HandleResult.Handled)
        val h = handled as HandleResult.Handled
        assertEquals(Idle, h.next)
        assertEquals(listOf(ConnectionEffect.IncHeartbeat), h.effects)
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
        log.clear() // drop init offline entry so we only inspect plan list

        val planned = ctx.planTransition(Offline, Idle, ConnEvent.Connect)
        // Offline exit, offline→Idle transition, Online enter, Idle enter
        assertEquals(4, planned.size)
        val exit0 = (planned[0] as ConnectionEffect.Audit).audit as AuditEffect.LogExit
        assertEquals("Offline", exit0.state)
        val t = (planned[1] as ConnectionEffect.Audit).audit as AuditEffect.LogTransition
        assertEquals("Offline", t.from)
        assertEquals("Connect", t.event)
        assertEquals("Idle", t.to)
        val enterOnline = (planned[2] as ConnectionEffect.Audit).audit as AuditEffect.LogEntry
        assertEquals("Online", enterOnline.state)
        val enterIdle = (planned[3] as ConnectionEffect.Audit).audit as AuditEffect.LogEntry
        assertEquals("Idle", enterIdle.state)
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
    fun `cross-cutting audit produces consistent TransitionLog across machines`() {
        // Same log format from distinct effect ADTs + local interpreters.
        val shared = TransitionLog()
        interpretLight(
            step(LightState.Red, LightEvent.Tick).effects,
            shared,
        )
        interpretFile(
            FileProtocol().stepOffer(FileState.Closed, FileEvent.Open).effects,
            shared,
        )
        val kinds = shared.snapshot().map { it.kind }
        assertEquals(listOf(RecordKind.TRANSITION, RecordKind.TRANSITION), kinds)
        assertEquals("TrafficLight", shared.snapshot()[0].machine)
        assertEquals("FileProtocol", shared.snapshot()[1].machine)
    }

    @Test
    fun `Latch pure step declares LatchEffect transition for orthogonal region`() {
        val latch = LatchMachine()
        val result = latch.step(LatchState.Locked, LatchEvent.Unlock)
        assertEquals(LatchState.Unlocked, result.next)
        val audit = result.effects.filterIsInstance<LatchEffect.Audit>().single()
        val t = audit.audit as AuditEffect.LogTransition
        assertEquals("Latch", t.machine)
        assertEquals("Unlock", t.event)
        assertEquals("Unlocked", t.to)
    }

    @Test
    fun `DoorSystem pure guard declares DoorEffect refusal without mutating log`() {
        val door = DoorSystem()
        val effects = door.guardRefusalEffects(
            DoorEvent.Open,
            latchState = LatchState.Locked,
            swingState = SwingState.Closed,
        )
        assertEquals(1, effects.size)
        val audit = effects.single() as DoorEffect.Audit
        val r = audit.audit as AuditEffect.LogRefusal
        assertTrue(r.reason.contains("locked"))
        // Pure guard: container log still empty
        assertTrue(door.log.snapshot().isEmpty())
    }

    @Test
    fun `DoorSystem guard refusal goes through interpretDoor`() {
        val door = DoorSystem()
        assertFalse(door.handle(DoorEvent.Open))
        assertTrue(
            door.log.snapshot().any {
                it.kind == RecordKind.REFUSAL && it.detail?.contains("locked") == true
            },
        )
    }
}
