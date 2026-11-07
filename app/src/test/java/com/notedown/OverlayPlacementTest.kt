package com.notedown

import com.notedown.overlay.OverlayPlacement
import org.junit.Assert.*
import org.junit.Test

class OverlayPlacementTest {
    @Test fun expandedControlsStayInsideTheSameDockedEdge() {
        val bounds = OverlayPlacement(12, 36, 388, 600)
        assertEquals(336, bounds.x(false, 52))
        assertEquals(108, bounds.x(false, 280))
        assertEquals(12, bounds.x(true, 280))
        assertEquals(548, bounds.y(null, 52))
        assertEquals(540, bounds.y(null, 60))
    }

    @Test fun rememberedHeightAdaptsToKeyboardAndRotation() {
        val portrait = OverlayPlacement(12, 36, 388, 600)
        val fraction = portrait.fraction(292, 52)
        val landscape = OverlayPlacement(36, 12, 780, 300)
        assertEquals(130, landscape.y(fraction, 52))
        assertEquals(fraction, landscape.fraction(130, 52), 0.001f)
        assertTrue(portrait.dockLeft(12, 52))
        assertFalse(portrait.dockLeft(336, 52))
    }

    @Test fun crampedBoundsAndOutOfRangePositionsDoNotCrash() {
        val bounds = OverlayPlacement(12, 36, 40, 50)
        assertEquals(12, bounds.x(false, 280))
        assertEquals(36, bounds.y(1f, 60))
        assertEquals(0f, bounds.fraction(500, 60))
        val normal = OverlayPlacement(12, 36, 388, 600)
        assertEquals(36, normal.y(-1f, 52))
        assertEquals(548, normal.y(2f, 52))
    }
}
