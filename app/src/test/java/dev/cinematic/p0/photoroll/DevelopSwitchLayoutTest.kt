package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class DevelopSwitchLayoutTest {
    @Test fun densityOneFitsLabelsUpTo44Px() {
        // 密度 1.0 相当の丸め後の px。
        val labelWidthPx = developSwitchLabelWidthPx(100, 2, 2)
        assertEquals(44, labelWidthPx)
        assertFalse(developSwitchNeedsTwoRows(43, labelWidthPx))
        assertFalse(developSwitchNeedsTwoRows(44, labelWidthPx))
        assertTrue(developSwitchNeedsTwoRows(45, labelWidthPx))
    }

    @Test fun densityThreeQuartersFitsLabelsUpTo31Px() {
        // 密度 0.75 相当の丸め後の px。
        val labelWidthPx = developSwitchLabelWidthPx(75, 2, 2)
        assertEquals(31, labelWidthPx)
        assertFalse(developSwitchNeedsTwoRows(31, labelWidthPx))
        assertTrue(developSwitchNeedsTwoRows(32, labelWidthPx))
    }

    @Test fun densityOneAndAHalfFitsLabelsUpTo66Px() {
        // 密度 1.5 相当の丸め後の px。
        val labelWidthPx = developSwitchLabelWidthPx(150, 3, 3)
        assertEquals(66, labelWidthPx)
        assertFalse(developSwitchNeedsTwoRows(66, labelWidthPx))
        assertTrue(developSwitchNeedsTwoRows(67, labelWidthPx))
    }

    @Test fun densityTwoFitsLabelsUpTo88Px() {
        // 密度 2.0 相当の丸め後の px。
        val labelWidthPx = developSwitchLabelWidthPx(200, 4, 4)
        assertEquals(88, labelWidthPx)
        assertFalse(developSwitchNeedsTwoRows(88, labelWidthPx))
        assertTrue(developSwitchNeedsTwoRows(89, labelWidthPx))
    }

    @Test fun oddInnerWidthUsesNarrowerButton() {
        // 密度 1.0 で幅 101dp 相当の丸め後の px。内側の 97px は 48px と 49px に分かれる。
        assertEquals(44, developSwitchLabelWidthPx(101, 2, 2))
    }
}
