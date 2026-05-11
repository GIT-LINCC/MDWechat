package com.blanke.mdwechat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialFloatButtonShapePolicyTest {
    @Test
    fun roundedRectangleFabUsesMd3CornerRadiusFromPreview() {
        assertEquals(16f, MaterialFloatButtonShapePolicy.roundedRectangleCornerRadiusDp, 0f)
    }
}
