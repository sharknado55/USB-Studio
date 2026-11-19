package com.example

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.FileBlockTransport
import com.example.data.UsbDiskEngine
import com.example.ui.components.applyFastGaussianBoxBlur
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.UsbStudioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w411dp-h915dp-xxhdpi")
class LegacyRenderScriptBackdropBlurTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `fast Gaussian box blur smoothly diffuses sharp color edges and preserves opaque alpha`() {
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            if (x < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val temp = IntArray(width * height)

        applyFastGaussianBoxBlur(pixels, temp, width, height, radius = 4)

        val midRow = height / 2
        val leftEdgeRed = (pixels[midRow * width + (width / 2 - 2)] shr 16) and 0xFF
        val rightEdgeRed = (pixels[midRow * width + (width / 2 + 1)] shr 16) and 0xFF
        val alphaSample = (pixels[midRow * width + (width / 2)] ushr 24) and 0xFF

        assertEquals("Blurred pixels must remain fully opaque", 0xFF, alphaSample)
        assertTrue("Left side of boundary should receive bright bleed ($leftEdgeRed)", leftEdgeRed in 10..220)
        assertTrue("Right side of boundary should receive dark bleed ($rightEdgeRed)", rightEdgeRed in 40..245)
        assertTrue("Left edge should still be darker than right edge", leftEdgeRed < rightEdgeRed)
    }

    @Test
    fun `Android 11 API 30 mounts real RenderScript backdrop blur view and handles theme and tab switches smoothly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TestSafDocumentsProvider.register(app.cacheDir)
        val blockFile = File(app.cacheDir, "api30_blur_test.img").apply { delete() }
        val engine = UsbDiskEngine(
            context = app,
            blockTransportFactory = { FileBlockTransport(blockFile, totalBlocks = 16384L) }
        )
        val viewModel = UsbStudioViewModel(app, engine)
        val effectsReady = mutableStateOf(false)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            MyApplicationTheme(darkTheme = state.darkTheme) {
                UsbStudioApp(state = state, viewModel = viewModel, effectsReady = effectsReady.value)
            }
        }

        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tab_files").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_drives").assertDoesNotExist()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_bottom_bar").assertDoesNotExist()
        composeTestRule.runOnIdle { effectsReady.value = true }

        // Android 11 (API 30) must mount the RenderScript backdrop blur view behind both header & bottom pill
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_drives").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_bottom_bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_theme_button").assertIsDisplayed()

        // Toggle theme & switch tabs: top bar and bottom pill must NEVER disappear or get skipped
        composeTestRule.onNodeWithTag("toggle_theme_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_drives").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_bottom_bar").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_files").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_files").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_bottom_bar").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_partitions").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_partitions").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_bottom_bar").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_drives").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_drives").assertIsDisplayed()
        composeTestRule.onNodeWithTag("legacy_renderscript_blur_bottom_bar").assertIsDisplayed()

        blockFile.delete()
    }
}
