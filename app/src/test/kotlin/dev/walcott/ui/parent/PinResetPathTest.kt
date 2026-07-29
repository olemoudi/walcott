package dev.walcott.ui.parent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule that decides when a forgotten parent PIN can be replaced without knowing it. It is
 * the only bypass of the PIN that also releases a child's phone, so each branch is pinned here.
 */
class PinResetPathTest {

    @Test
    fun `with the app lock off nothing was guarding this, so let them through`() {
        assertEquals(
            PinResetPath.DIRECT,
            pinResetPath(appLock = false, appLockBiometric = false, biometricAvailable = false),
        )
    }

    @Test
    fun `app lock off but biometrics present still asks for the finger`() {
        // Free to require, and it keeps a passer-by from resetting the PIN on an idle phone.
        assertEquals(
            PinResetPath.BIOMETRIC,
            pinResetPath(appLock = false, appLockBiometric = false, biometricAvailable = true),
        )
    }

    @Test
    fun `app lock on with biometric unlock accepted - biometrics stands in for the PIN`() {
        assertEquals(
            PinResetPath.BIOMETRIC,
            pinResetPath(appLock = true, appLockBiometric = true, biometricAvailable = true),
        )
    }

    @Test
    fun `app lock on and biometric unlock declined - the finger must not reopen this door`() {
        // The parent who turned that toggle off often did so because a child's finger is
        // enrolled on this phone. Honouring it here is the whole point of the rule.
        assertEquals(
            PinResetPath.NEEDS_APP_LOCK_BIOMETRIC,
            pinResetPath(appLock = true, appLockBiometric = false, biometricAvailable = true),
        )
    }

    @Test
    fun `app lock on with no usable biometric - only the current PIN gets through`() {
        assertEquals(
            PinResetPath.NEEDS_BIOMETRIC_HARDWARE,
            pinResetPath(appLock = true, appLockBiometric = false, biometricAvailable = false),
        )
        // Even with the toggle on: the hardware is what's missing, and saying so is the fix.
        assertEquals(
            PinResetPath.NEEDS_BIOMETRIC_HARDWARE,
            pinResetPath(appLock = true, appLockBiometric = true, biometricAvailable = false),
        )
    }
}

/**
 * Which children the PIN card reports as already able to verify the current PIN. Errs toward
 * "can't tell": it must never claim a child is current when it isn't.
 */
class PolicyAdoptionTest {

    @Test
    fun `a child reporting the parent's version is up to date`() {
        assertEquals(PolicyAdoption.UP_TO_DATE, policyAdoption(appliedPolicyVersion = 12, parentVersion = 12))
    }

    @Test
    fun `a child ahead of the parent still counts as up to date`() {
        // Happens after a backup restore, which leaps the parent's counter (RESTORE_VERSION_LEAP)
        // and can leave a child briefly reporting a version the parent has already passed.
        assertEquals(PolicyAdoption.UP_TO_DATE, policyAdoption(appliedPolicyVersion = 13, parentVersion = 12))
    }

    @Test
    fun `a child behind the parent is still updating`() {
        assertEquals(PolicyAdoption.PENDING, policyAdoption(appliedPolicyVersion = 11, parentVersion = 12))
    }

    @Test
    fun `no snapshot at all is unknown, not stale`() {
        // A registered child whose phone has never checked in.
        assertEquals(PolicyAdoption.UNKNOWN, policyAdoption(appliedPolicyVersion = null, parentVersion = 12))
    }

    @Test
    fun `a child too old to report the field is unknown, not stale`() {
        // Legacy children send 0; calling that "updating" would be a permanent false alarm.
        assertEquals(PolicyAdoption.UNKNOWN, policyAdoption(appliedPolicyVersion = 0, parentVersion = 12))
    }
}
