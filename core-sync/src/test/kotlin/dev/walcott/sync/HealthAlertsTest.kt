package dev.walcott.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthAlertsTest {

    @Test
    fun `fires below the threshold when unplugged and not already alerted`() {
        assertTrue(HealthAlerts.shouldAlertLowBattery(15, charging = false, alreadyAlerted = false))
    }

    @Test
    fun `does not fire while charging, even low`() {
        assertFalse(HealthAlerts.shouldAlertLowBattery(5, charging = true, alreadyAlerted = false))
    }

    @Test
    fun `does not fire twice for the same outage`() {
        assertFalse(HealthAlerts.shouldAlertLowBattery(15, charging = false, alreadyAlerted = true))
    }

    @Test
    fun `does not fire above the threshold`() {
        assertFalse(HealthAlerts.shouldAlertLowBattery(20, charging = false, alreadyAlerted = false))
    }

    @Test
    fun `an unknown reading never fires`() {
        assertFalse(HealthAlerts.shouldAlertLowBattery(-1, charging = false, alreadyAlerted = false))
    }

    @Test
    fun `hysteresis - staying just above the threshold does not clear the alert`() {
        // 21% is above the 20 alert mark but below the 25 recover mark: still "low", so the
        // notification must not clear and immediately re-fire (the flap this guards against).
        assertFalse(HealthAlerts.clearsLowBattery(21, charging = false))
        assertFalse(HealthAlerts.shouldAlertLowBattery(21, charging = false, alreadyAlerted = true))
    }

    @Test
    fun `clears once charged past the recover mark or plugged in`() {
        assertTrue(HealthAlerts.clearsLowBattery(25, charging = false))
        assertTrue(HealthAlerts.clearsLowBattery(10, charging = true))
    }
}
