package dev.walcott.enforcement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "touch only what changes" reconciliation the [Enforcer] uses. Extracted as a pure
 * function so the diff — which decides the exact system calls that hold the child's blocks in
 * place — is covered without a device.
 */
class SuspensionPlanTest {

    private val a = "com.a"
    private val b = "com.b"
    private val c = "com.c"
    private val managed = setOf(a, b, c)

    private fun plan(blocked: Set<String>, suspended: Set<String>) =
        Enforcer.plan(managed, blocked, isSuspended = { it in suspended })

    @Test
    fun `suspends the newly blocked and unsuspends the newly allowed, leaving the rest alone`() {
        // a should block and isn't suspended -> suspend. b is suspended but no longer blocked
        // -> unsuspend. c is blocked and already suspended -> untouched.
        val plan = plan(blocked = setOf(a, c), suspended = setOf(b, c))
        assertEquals(listOf(a), plan.toSuspend)
        assertEquals(listOf(b), plan.toUnsuspend)
    }

    @Test
    fun `a fully reconciled state is a no-op`() {
        val plan = plan(blocked = setOf(a), suspended = setOf(a))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `blocking everything from a clean state suspends everything`() {
        val plan = plan(blocked = managed, suspended = emptySet())
        assertEquals(managed, plan.toSuspend.toSet())
        assertTrue(plan.toUnsuspend.isEmpty())
    }

    @Test
    fun `releasing everything unsuspends exactly the currently suspended`() {
        val plan = plan(blocked = emptySet(), suspended = setOf(a, b))
        assertEquals(setOf(a, b), plan.toUnsuspend.toSet())
        assertTrue(plan.toSuspend.isEmpty())
    }

    @Test
    fun `packages outside the managed set are never touched`() {
        // "com.other" is suspended and blocked but not managed -> the plan ignores it.
        val plan = Enforcer.plan(managed, blocked = setOf("com.other"), isSuspended = { it == "com.other" })
        assertTrue(plan.isEmpty)
    }
}
