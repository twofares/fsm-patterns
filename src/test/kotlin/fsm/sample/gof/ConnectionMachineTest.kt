package fsm.sample.gof

import fsm.sample.logging.RecordKind
import fsm.sample.effect.CrossCuttingEnv
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
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        // Initial Offline was entered in init
        assertEquals(Offline, ctx.current)
        assertTrue(ctx.handle(ConnEvent.Connect))
        assertEquals(Idle, ctx.current)

        val entries = env.log.snapshot().filter { it.kind == RecordKind.ENTRY }
        // Offline (init) + Online + Idle on connect
        assertTrue(entries.any { it.to == "Offline" })
        assertTrue(entries.any { it.to == "Online" })
        assertTrue(entries.any { it.to == "Idle" })
    }

    @Test
    fun `heartbeat is handled by Online superstate without leaving Idle`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        ctx.handle(ConnEvent.Connect)
        assertEquals(Idle, ctx.current)
        assertEquals(0, ctx.port.heartbeats)

        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(Idle, ctx.current)
        assertEquals(1, ctx.port.heartbeats)

        val transitions = env.log.snapshot().filter {
            it.kind == RecordKind.TRANSITION && it.event == "Heartbeat"
        }
        assertEquals(1, transitions.size)
        assertEquals("Idle", transitions[0].from)
        assertEquals("Idle", transitions[0].to)
    }

    @Test
    fun `disconnect from Transferring uses hierarchical Online handler`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        ctx.handle(ConnEvent.Connect)
        ctx.handle(ConnEvent.StartTransfer)
        assertEquals(Transferring, ctx.current)

        assertTrue(ctx.handle(ConnEvent.Disconnect))
        assertEquals(Offline, ctx.current)

        val exits = env.log.snapshot().filter { it.kind == RecordKind.EXIT }.map { it.from }
        // Leave Transferring then Online before Offline entry path
        assertTrue(exits.contains("Transferring"))
        assertTrue(exits.contains("Online"))
    }

    @Test
    fun `entry exit order is outer to inner on enter and reverse on exit`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        ctx.handle(ConnEvent.Connect) // Offline exit, Online enter, Idle enter

        val afterConnect = env.log.snapshot()
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
        env.log.clear()
        // Re-seed: need a fresh context for clean exit order check on disconnect
        val env2 = CrossCuttingEnv()
        val ctx2 = ConnectionContext(crossCutting = env2)
        ctx2.handle(ConnEvent.Connect)
        ctx2.handle(ConnEvent.StartTransfer)
        env2.log.clear()
        ctx2.handle(ConnEvent.Disconnect)

        val snap = env2.log.snapshot()
        val exitTransferring = snap.indexOfFirst { it.kind == RecordKind.EXIT && it.from == "Transferring" }
        val exitOnline = snap.indexOfFirst { it.kind == RecordKind.EXIT && it.from == "Online" }
        val enterOffline = snap.indexOfFirst { it.kind == RecordKind.ENTRY && it.to == "Offline" }
        assertTrue(exitTransferring >= 0)
        assertTrue(exitOnline > exitTransferring) // leaf exits before superstate
        assertTrue(enterOffline > exitOnline)
    }

    @Test
    fun `illegal event at Offline is refused and state unchanged`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        assertFalse(ctx.handle(ConnEvent.StartTransfer))
        assertEquals(Offline, ctx.current)
        assertTrue(env.log.snapshot().any { it.kind == RecordKind.REFUSAL })
    }
}
