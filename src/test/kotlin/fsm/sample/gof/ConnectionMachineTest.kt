package fsm.sample.gof

import fsm.sample.logging.RecordKind
import fsm.sample.logging.TransitionLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped [ConnectionContext] for GoF state replacement,
 * hierarchical parent delegation, entry/exit ordering, and refusal.
 */
class ConnectionMachineTest {

    @Test
    fun `connect enters Online then Idle with entry records`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        // Initial Offline was entered in init
        assertEquals(Offline, ctx.current)
        assertTrue(ctx.handle(ConnEvent.Connect))
        assertEquals(Idle, ctx.current)

        val entries = log.snapshot().filter { it.kind == RecordKind.ENTRY }
        // Offline (init) + Online + Idle on connect
        assertTrue(entries.any { it.to == "Offline" })
        assertTrue(entries.any { it.to == "Online" })
        assertTrue(entries.any { it.to == "Idle" })
    }

    @Test
    fun `heartbeat is handled by Online superstate without leaving Idle`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        ctx.handle(ConnEvent.Connect)
        assertEquals(Idle, ctx.current)
        assertEquals(0, ctx.heartbeatCount)

        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(Idle, ctx.current)
        assertEquals(1, ctx.heartbeatCount)

        val transitions = log.snapshot().filter {
            it.kind == RecordKind.TRANSITION && it.event == "Heartbeat"
        }
        assertEquals(1, transitions.size)
        assertEquals("Idle", transitions[0].from)
        assertEquals("Idle", transitions[0].to)
    }

    @Test
    fun `disconnect from Transferring uses hierarchical Online handler`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        ctx.handle(ConnEvent.Connect)
        ctx.handle(ConnEvent.StartTransfer)
        assertEquals(Transferring, ctx.current)

        assertTrue(ctx.handle(ConnEvent.Disconnect))
        assertEquals(Offline, ctx.current)

        val exits = log.snapshot().filter { it.kind == RecordKind.EXIT }.map { it.from }
        // Leave Transferring then Online before Offline entry path
        assertTrue(exits.contains("Transferring"))
        assertTrue(exits.contains("Online"))
    }

    @Test
    fun `entry exit order is outer to inner on enter and reverse on exit`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        ctx.handle(ConnEvent.Connect) // Offline exit, Online enter, Idle enter

        val afterConnect = log.snapshot()
        val offlineExitIdx = afterConnect.indexOfFirst {
            it.kind == RecordKind.EXIT && it.from == "Offline"
        }
        val onlineEntryIdx = afterConnect.indexOfFirst {
            it.kind == RecordKind.ENTRY && it.to == "Online" &&
                afterConnect.indexOf(it) > offlineExitIdx
        }
        val idleEntryIdx = afterConnect.indexOfFirst {
            it.kind == RecordKind.ENTRY && it.to == "Idle" &&
                afterConnect.indexOf(it) > onlineEntryIdx
        }
        assertTrue(offlineExitIdx >= 0)
        assertTrue(onlineEntryIdx > offlineExitIdx)
        assertTrue(idleEntryIdx > onlineEntryIdx)

        // StartTransfer: stay under Online — only Idle exit, Transferring enter
        log.clear()
        // Re-seed: need a fresh context for clean exit order check on disconnect
        val log2 = TransitionLog()
        val ctx2 = ConnectionContext(log = log2)
        ctx2.handle(ConnEvent.Connect)
        ctx2.handle(ConnEvent.StartTransfer)
        log2.clear()
        ctx2.handle(ConnEvent.Disconnect)

        val snap = log2.snapshot()
        val exitTransferring = snap.indexOfFirst { it.kind == RecordKind.EXIT && it.from == "Transferring" }
        val exitOnline = snap.indexOfFirst { it.kind == RecordKind.EXIT && it.from == "Online" }
        val enterOffline = snap.indexOfFirst { it.kind == RecordKind.ENTRY && it.to == "Offline" }
        assertTrue(exitTransferring >= 0)
        assertTrue(exitOnline > exitTransferring) // leaf exits before superstate
        assertTrue(enterOffline > exitOnline)
    }

    @Test
    fun `illegal event at Offline is refused and state unchanged`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        assertFalse(ctx.handle(ConnEvent.StartTransfer))
        assertEquals(Offline, ctx.current)
        assertTrue(log.snapshot().any { it.kind == RecordKind.REFUSAL })
    }
}
