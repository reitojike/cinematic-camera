package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class PreviewClickPolicyTest {
    @Test fun touchNotificationDoesNotAddCenterFocusButAccessibilityDoes() {
        val policy = PreviewClickPolicy()
        val requests = mutableListOf<Pair<Float, Float>>()
        fun click() { if (policy.shouldFocusCenter) requests += .5f to .5f }
        requests += .2f to .8f // GestureDetector's exact tap request precedes performClick.
        policy.notifyTapClick { click() }
        assertEquals(listOf(.2f to .8f), requests)
        click()
        assertEquals(listOf(.2f to .8f, .5f to .5f), requests)
    }

    @Test fun tapNotificationRestoresClickActionEvenIfListenerThrows() {
        val policy = PreviewClickPolicy()
        runCatching { policy.notifyTapClick { error("listener") } }
        assertTrue(policy.shouldFocusCenter)
    }
}
