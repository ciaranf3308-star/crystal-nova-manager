package io.crystalnova.manager.pegasus

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the v18 Pegasus reload path. The flag logic is pure
 * ([PegasusRestartLaunch]) so the flags are asserted here without
 * Robolectric — android.content.Intent is a stub on the JVM, but the
 * FLAG_* constants are compile-time ints.
 */
class PegasusIntentsTest {

    @Test
    fun restartLaunch_targetsExplicitPegasusMainActivity() {
        val launch = PegasusIntents.restartLaunch()
        assertEquals("org.pegasus_frontend.android", launch.packageName)
        assertEquals("org.pegasus_frontend.android.MainActivity", launch.className)
    }

    @Test
    fun restartLaunch_carriesNewTaskAndClearTask() {
        val flags = PegasusIntents.restartLaunch().flags
        assertNotEquals("NEW_TASK must be set", 0, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotEquals("CLEAR_TASK must be set", 0, flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    @Test
    fun restartFlagsConstant_matchesDescriptor() {
        assertEquals(PegasusIntents.RESTART_FLAGS, PegasusIntents.restartLaunch().flags)
    }

    @Test
    fun gate_consumeRestart_trueOncePerBuild() {
        val gate = PegasusRestartGate()
        assertFalse(gate.consumeRestart()) // no pending build: plain resume
        gate.pendingBuild = true
        assertTrue(gate.consumeRestart()) // OPEN PEGASUS restarts, clears the flag
        assertFalse(gate.consumeRestart()) // next tap is a plain resume again
    }

    @Test
    fun gate_pendingBuild_survivesUntilConsumed() {
        val gate = PegasusRestartGate()
        gate.pendingBuild = true
        assertTrue(gate.pendingBuild)
        assertTrue(gate.consumeRestart())
        assertFalse(gate.pendingBuild)
    }
}
