package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialTabBadgePolicyTest {
    @Test
    fun positiveCountMapsToNumberBadge() {
        assertEquals(MaterialTabBadgePolicy.Mode.NUMBER, MaterialTabBadgePolicy.resolveMode(3))
    }

    @Test
    fun zeroMapsToHiddenBadge() {
        assertEquals(MaterialTabBadgePolicy.Mode.HIDDEN, MaterialTabBadgePolicy.resolveMode(0))
    }

    @Test
    fun negativeCountMapsToDotBadge() {
        assertEquals(MaterialTabBadgePolicy.Mode.DOT, MaterialTabBadgePolicy.resolveMode(-1))
    }
}
