package com.blanke.mdwechat.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPageRippleGesturePolicyTest {
    @Test
    fun keepsRippleWhilePointerStaysInsideLocalBounds() {
        assertFalse(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(0f, 0f, 100, 80))
        assertFalse(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(99.9f, 79.9f, 100, 80))
        assertFalse(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(50f, 40f, 100, 80))
    }

    @Test
    fun resetsRippleWhenPointerLeavesLocalBoundsOrViewIsEmpty() {
        assertTrue(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(-1f, 20f, 100, 80))
        assertTrue(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(20f, -1f, 100, 80))
        assertTrue(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(100f, 20f, 100, 80))
        assertTrue(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(20f, 80f, 100, 80))
        assertTrue(MainPageRippleGesturePolicy.shouldResetFromLocalPoint(0f, 0f, 0, 80))
    }

    @Test
    fun comparesRawPointerAgainstScreenCoordinates() {
        assertFalse(
            MainPageRippleGesturePolicy.shouldResetFromRawPoint(
                250f,
                420f,
                200,
                400,
                100,
                80
            )
        )
        assertTrue(
            MainPageRippleGesturePolicy.shouldResetFromRawPoint(
                301f,
                420f,
                200,
                400,
                100,
                80
            )
        )
        assertTrue(
            MainPageRippleGesturePolicy.shouldResetFromRawPoint(
                250f,
                480f,
                200,
                400,
                100,
                80
            )
        )
    }
}
