package com.example

import com.example.util.RefreshRateModeSpec
import com.example.util.selectOptimalRefreshRateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRefreshRateTest {

    @Test
    fun `selects 120Hz over 60Hz and 90Hz at matching resolution`() {
        val current = RefreshRateModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)
        val modes = listOf(
            current,
            RefreshRateModeSpec(modeId = 2, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 90f),
            RefreshRateModeSpec(modeId = 3, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 120f)
        )

        val chosen = selectOptimalRefreshRateMode(current, modes)
        assertEquals(3, chosen?.modeId)
        assertEquals(120f, chosen?.refreshRate)
    }

    @Test
    fun `selects 144Hz when display supports 144Hz gaming panel`() {
        val current = RefreshRateModeSpec(modeId = 1, physicalWidth = 1220, physicalHeight = 2712, refreshRate = 60f)
        val modes = listOf(
            current,
            RefreshRateModeSpec(modeId = 2, physicalWidth = 1220, physicalHeight = 2712, refreshRate = 90f),
            RefreshRateModeSpec(modeId = 3, physicalWidth = 1220, physicalHeight = 2712, refreshRate = 120f),
            RefreshRateModeSpec(modeId = 4, physicalWidth = 1220, physicalHeight = 2712, refreshRate = 144f)
        )

        val chosen = selectOptimalRefreshRateMode(current, modes)
        assertEquals(4, chosen?.modeId)
        assertEquals(144f, chosen?.refreshRate)
    }

    @Test
    fun `preserves active physical resolution on dual-resolution phones`() {
        val currentWqhd = RefreshRateModeSpec(modeId = 10, physicalWidth = 1440, physicalHeight = 3120, refreshRate = 60f)
        val modes = listOf(
            RefreshRateModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2340, refreshRate = 144f),
            currentWqhd,
            RefreshRateModeSpec(modeId = 11, physicalWidth = 1440, physicalHeight = 3120, refreshRate = 120f)
        )

        val chosen = selectOptimalRefreshRateMode(currentWqhd, modes)
        assertEquals(11, chosen?.modeId)
        assertEquals(1440, chosen?.physicalWidth)
        assertEquals(120f, chosen?.refreshRate)
    }

    @Test
    fun `handles 90Hz panel and empty mode list gracefully`() {
        val current90 = RefreshRateModeSpec(modeId = 2, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f)
        val modes = listOf(
            current90,
            RefreshRateModeSpec(modeId = 3, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 90f)
        )

        assertEquals(90f, selectOptimalRefreshRateMode(current90, modes)?.refreshRate)
        assertEquals(current90, selectOptimalRefreshRateMode(current90, emptyList()))
        assertNull(selectOptimalRefreshRateMode(null, emptyList()))
    }
}
