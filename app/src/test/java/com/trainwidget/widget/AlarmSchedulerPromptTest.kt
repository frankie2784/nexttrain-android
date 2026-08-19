package com.nexttrain.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSchedulerPromptTest {

    // ── shouldPromptForBatteryExemption ─────────────────────────────────────

    @Test
    fun `shouldPromptForBatteryExemption is true when a route needs it and not exempted`() {
        assertTrue(
            shouldPromptForBatteryExemption(
                hasNotificationEnabledPair = true,
                isIgnoringBatteryOptimizations = false,
                dismissed = false,
            )
        )
    }

    @Test
    fun `shouldPromptForBatteryExemption is false with no notification-enabled route`() {
        assertFalse(
            shouldPromptForBatteryExemption(
                hasNotificationEnabledPair = false,
                isIgnoringBatteryOptimizations = false,
                dismissed = false,
            )
        )
    }

    @Test
    fun `shouldPromptForBatteryExemption is false once already exempted`() {
        assertFalse(
            shouldPromptForBatteryExemption(
                hasNotificationEnabledPair = true,
                isIgnoringBatteryOptimizations = true,
                dismissed = false,
            )
        )
    }

    @Test
    fun `shouldPromptForBatteryExemption is false once dismissed`() {
        assertFalse(
            shouldPromptForBatteryExemption(
                hasNotificationEnabledPair = true,
                isIgnoringBatteryOptimizations = false,
                dismissed = true,
            )
        )
    }
}
