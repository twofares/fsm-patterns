package fsm.sample.effect

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.step
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.HandleResult
import fsm.sample.gof.Offline
import fsm.sample.gof.declaredEffects
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorSystem
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: PR 2 gate — prove [acceptTransitionCrossCutting] / [refuseCrossCutting]
 * rebuild the multi-family lists already declared by pure steps/guards, including
 * machine-specific extras and Connection `traceDetail = event.label()`.
 *
 * FileProtocol now builds accept/refuse via these helpers; fixtures still prove list equality.
 */
class CrossCuttingBundlesTest {

    // --- TrafficLight accept (extras: light.ticks / emergency trace) ----------

    @Test
    fun `TrafficLight Tick accept list equals helper with light ticks extra`() {
        val from = LightState.Red
        val event = LightEvent.Tick
        val pure = step(from, event)
        val viaHelper = acceptTransitionCrossCutting(
            machine = "TrafficLight",
            from = from.label(),
            event = event.label(),
            to = pure.next.label(),
            extras = listOf(metricInc("light.ticks")),
        )
        assertEquals(pure.crossCutting, viaHelper)
        assertEquals(
            pure.crossCutting.map { it.exportLabel() },
            viaHelper.map { it.exportLabel() },
        )
        assertTrue(viaHelper.any { it is CrossCuttingEffect.Metric.Inc && it.name == "light.ticks" })
        assertTrue(viaHelper.any { it is CrossCuttingEffect.Metric.Inc && it.name == "fsm.transitions" })
    }

    @Test
    fun `TrafficLight Emergency accept list equals helper with emergency trace extra`() {
        val from = LightState.Green
        val event = LightEvent.Emergency
        val pure = step(from, event)
        val viaHelper = acceptTransitionCrossCutting(
            machine = "TrafficLight",
            from = from.label(),
            event = event.label(),
            to = pure.next.label(),
            extras = listOf(traceSpan("light.emergency", "forced ${pure.next.label()}")),
        )
        assertEquals(pure.crossCutting, viaHelper)
        assertEquals(
            pure.crossCutting.map { it.exportLabel() },
            viaHelper.map { it.exportLabel() },
        )
        assertTrue(
            viaHelper.any {
                it is CrossCuttingEffect.Trace.Span &&
                    it.name == "light.emergency" &&
                    it.detail == "forced Red"
            },
        )
        assertTrue(viaHelper.none { it is CrossCuttingEffect.Metric.Inc && it.name == "light.ticks" })
    }

    // --- FileProtocol accept / refuse (extras: csp.accept; refuse csp.refuse) -

    @Test
    fun `File accept list equals helper with csp accept extra`() {
        val protocol = FileProtocol()
        val pure = protocol.stepOffer(FileState.Closed, FileEvent.Open)
        assertTrue(pure is OfferOutcome.Accepted)
        val viaHelper = acceptTransitionCrossCutting(
            machine = "FileProtocol",
            from = FileState.Closed.label(),
            event = FileEvent.Open.label(),
            to = pure.next.label(),
            extras = listOf(metricInc("csp.accept")),
        )
        assertEquals(pure.crossCutting, viaHelper)
        assertEquals(
            pure.crossCutting.map { it.exportLabel() },
            viaHelper.map { it.exportLabel() },
        )
        assertTrue(viaHelper.any { it is CrossCuttingEffect.Metric.Inc && it.name == "csp.accept" })
    }

    @Test
    fun `File refuse list equals refuseCrossCutting with csp refuse defaults`() {
        val protocol = FileProtocol()
        val pure = protocol.stepOffer(FileState.Closed, FileEvent.Read)
        assertTrue(pure is OfferOutcome.Refused)
        val refused = pure as OfferOutcome.Refused
        val viaHelper = refuseCrossCutting(
            machine = "FileProtocol",
            state = FileState.Closed.label(),
            event = FileEvent.Read.label(),
            reason = refused.reason,
            metricName = "csp.refuse",
            // traceName defaults to metricName; traceDetail defaults to reason
        )
        assertEquals(refused.crossCutting, viaHelper)
        assertEquals(
            refused.crossCutting.map { it.exportLabel() },
            viaHelper.map { it.exportLabel() },
        )
        assertTrue(
            viaHelper.any {
                it is CrossCuttingEffect.Trace.Span &&
                    it.name == "csp.refuse" &&
                    it.detail == refused.reason
            },
        )
    }

    // --- Door guard refuse (door.refuse metric + door.policy trace) -----------

    @Test
    fun `Door Open refuse list equals refuseCrossCutting with door policy trace`() {
        val door = DoorSystem()
        val latch = LatchState.Locked
        val swing = SwingState.Closed
        val config = "latch=${latch.label()}, swing=${swing.label()}"
        val pure = door.guardRefusalEffects(
            DoorEvent.Open,
            latchState = latch,
            swingState = swing,
            config = config,
        )
        assertTrue(pure.isNotEmpty())
        val reason = "cannot open while locked"
        val viaHelper = refuseCrossCutting(
            machine = "DoorSystem",
            state = config,
            event = DoorEvent.Open.label(),
            reason = reason,
            metricName = "door.refuse",
            traceName = "door.policy",
            // traceDetail defaults to reason
        )
        assertEquals(pure, viaHelper)
        assertEquals(pure.map { it.exportLabel() }, viaHelper.map { it.exportLabel() })
        assertTrue(
            viaHelper.any {
                it is CrossCuttingEffect.Trace.Span &&
                    it.name == "door.policy" &&
                    it.detail == reason
            },
        )
        assertTrue(viaHelper.any { it is CrossCuttingEffect.Metric.Inc && it.name == "door.refuse" })
    }

    @Test
    fun `Door Lock refuse list equals refuseCrossCutting with door policy trace`() {
        val door = DoorSystem()
        val latch = LatchState.Unlocked
        val swing = SwingState.Open
        val config = "latch=${latch.label()}, swing=${swing.label()}"
        val pure = door.guardRefusalEffects(
            DoorEvent.Lock,
            latchState = latch,
            swingState = swing,
            config = config,
        )
        assertTrue(pure.isNotEmpty())
        val reason = "cannot lock while door is open"
        val viaHelper = refuseCrossCutting(
            machine = "DoorSystem",
            state = config,
            event = DoorEvent.Lock.label(),
            reason = reason,
            metricName = "door.refuse",
            traceName = "door.policy",
        )
        assertEquals(pure, viaHelper)
        assertEquals(pure.map { it.exportLabel() }, viaHelper.map { it.exportLabel() })
    }

    // --- Connection Unhandled refuse (traceDetail = event.label()) ------------

    @Test
    fun `Connection Unhandled refuse list equals helper with event label as traceDetail`() {
        val from = Offline
        val event = ConnEvent.Heartbeat
        val pure = declaredEffects(from, event, HandleResult.Unhandled)
        val reason = "no state in hierarchy accepted event"
        val viaHelper = refuseCrossCutting(
            machine = "Connection",
            state = from.name,
            event = event.label(),
            reason = reason,
            metricName = "connection.refuse",
            // Connection uses event label as span detail — not reason
            traceDetail = event.label(),
        )
        assertEquals(pure.crossCutting, viaHelper)
        assertEquals(
            pure.crossCutting.map { it.exportLabel() },
            viaHelper.map { it.exportLabel() },
        )
        val span = viaHelper.filterIsInstance<CrossCuttingEffect.Trace.Span>().single()
        assertEquals("connection.refuse", span.name)
        assertEquals(event.label(), span.detail)
        assertEquals("Trace.Span(connection.refuse,${event.label()})", span.exportLabel())
        // Prove default traceDetail=reason would diverge (must not use bare defaults here)
        val wrongDetail = refuseCrossCutting(
            machine = "Connection",
            state = from.name,
            event = event.label(),
            reason = reason,
            metricName = "connection.refuse",
            // intentional default traceDetail = reason
        )
        assertTrue(wrongDetail != pure.crossCutting)
        assertTrue(
            wrongDetail.any {
                it is CrossCuttingEffect.Trace.Span && it.detail == reason
            },
        )
    }
}
