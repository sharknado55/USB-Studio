package com.example

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.example.data.FileBlockTransport
import com.example.data.UsbDiskEngine
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.UsbStudioViewModel
import com.example.viewmodel.WorkbenchTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class UsbStudioUiCujTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var blockFile: File
    private lateinit var viewModel: UsbStudioViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "otg_disks").deleteRecursively()
        File(app.filesDir, "otg_mounts").deleteRecursively()
        File(app.filesDir, "iso_images").deleteRecursively()
        TestSafDocumentsProvider.register(app.cacheDir)

        blockFile = File(app.cacheDir, "ui_cuj_block.img").apply { delete() }
        val engine = UsbDiskEngine(
            context = app,
            blockTransportFactory = { FileBlockTransport(blockFile, totalBlocks = 131072L) }
        )
        viewModel = UsbStudioViewModel(app, engine)
        viewModel.refreshUsbBus()
    }

    private fun waitUntilWithLooper(timeoutMillis: Long = 10000L, condition: () -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis = timeoutMillis) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        composeTestRule.waitForIdle()
    }

    private fun launchApp(mountSampleSafDrives: Int = 0) {
        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            MyApplicationTheme(darkTheme = state.darkTheme) {
                UsbStudioApp(state = state, viewModel = viewModel)
            }
        }
        waitUntilWithLooper(timeoutMillis = 8000L) {
            !viewModel.uiState.value.isScanningBus
        }
        for (idx in 1..mountSampleSafDrives) {
            val uri = Uri.parse("content://${TestSafDocumentsProvider.AUTHORITY}/tree/USB$idx-000$idx%3A")
            viewModel.mountSafVolume(uri)
            waitUntilWithLooper(timeoutMillis = 5000L) {
                viewModel.uiState.value.drives.size >= idx
            }
        }
    }

    @Test
    fun `cuj0_empty state displays cleanly across all 5 tabs with zero dummy drives`() {
        launchApp(mountSampleSafDrives = 0)

        assertTrue(viewModel.uiState.value.drives.isEmpty())
        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithText("No drive attached").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Drives Connected").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drives_empty_state_card").assertIsDisplayed()

        assertTrue(composeTestRule.onAllNodesWithText("SanDisk", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("ProGrade", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("nav_tab_files").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("file_manager_empty_state").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_format").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("format_tool_empty_state").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_iso_writer").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("iso_writer_empty_state").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_partitions").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("partition_manager_empty_state").assertIsDisplayed()
    }

    @Test
    fun `cuj1_dashboard renders mounted drives, theme toggle, drive switching, and quick tools`() {
        launchApp(mountSampleSafDrives = 2)

        composeTestRule.onNodeWithText("usb-studio").assertIsDisplayed()
        composeTestRule.onNodeWithText("storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("OTG Disk Hub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mount_saf_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_usb_bus_button").assertIsDisplayed()

        assertTrue(viewModel.uiState.value.darkTheme)
        composeTestRule.onNodeWithTag("toggle_theme_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(viewModel.uiState.value.darkTheme)
        composeTestRule.onNodeWithTag("toggle_theme_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.darkTheme)

        val secondId = viewModel.uiState.value.drives[1].id
        composeTestRule.onNodeWithTag("drives_dashboard_list")
            .performScrollToNode(hasTestTag("drive_card_$secondId"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drive_card_$secondId").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.selectedDriveId == secondId)

        composeTestRule.onNodeWithTag("drives_dashboard_list")
            .performScrollToNode(hasTestTag("quick_tool_files"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_tool_files").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("file_manager_screen").assertIsDisplayed()
    }

    @Test
    fun `cuj2_rapid back-and-forth tab transitions across all 5 tabs always settle reliably`() {
        launchApp(mountSampleSafDrives = 1)

        // Rapidly bounce across tabs without pausing between clicks to verify transitions never drop/stick
        val sequence = listOf(
            WorkbenchTab.PARTITIONS to "partition_manager_screen",
            WorkbenchTab.FILES to "file_manager_screen",
            WorkbenchTab.ISO_WRITER to "iso_writer_screen",
            WorkbenchTab.FORMAT to "format_tool_screen",
            WorkbenchTab.DRIVES to "drives_dashboard_list",
            WorkbenchTab.FILES to "file_manager_screen",
            WorkbenchTab.PARTITIONS to "partition_manager_screen",
            WorkbenchTab.DRIVES to "drives_dashboard_list"
        )
        sequence.forEach { (tab, screenTag) ->
            composeTestRule.onNodeWithTag("nav_tab_${tab.route}").performClick()
            composeTestRule.waitForIdle()
            assertEquals(tab, viewModel.uiState.value.currentTab)
            composeTestRule.onNodeWithTag(screenTag).assertIsDisplayed()
        }
    }

    @Test
    fun `cuj3_file manager creates folder showing Folder label, navigates inside, edits file, and deletes item`() {
        launchApp(mountSampleSafDrives = 1)

        composeTestRule.onNodeWithTag("nav_tab_files").performClick()
        composeTestRule.waitForIdle()

        // Create a folder via dialog
        composeTestRule.onNodeWithTag("file_manager_screen")
            .performScrollToNode(hasTestTag("new_folder_button"))
        composeTestRule.onNodeWithTag("new_folder_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("dialog_text_input").performTextInput("MyMedia")
        composeTestRule.onNodeWithTag("dialog_confirm_button").performClick()

        waitUntilWithLooper(timeoutMillis = 5000L) {
            viewModel.uiState.value.fileEntries.any { it.name == "MyMedia" }
        }
        composeTestRule.onNodeWithTag("file_manager_screen")
            .performScrollToNode(hasTestTag("file_item_MyMedia"))
        composeTestRule.onNodeWithTag("file_item_MyMedia").assertIsDisplayed()
        // Folder label must display "Folder", never bogus "4096 item(s)"
        composeTestRule.onNodeWithText("Folder").assertIsDisplayed()

        // Search filter
        composeTestRule.onNodeWithTag("file_manager_screen")
            .performScrollToNode(hasTestTag("file_search_input"))
        composeTestRule.onNodeWithTag("file_search_input").performTextInput("MyMedia")
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.filteredFiles.size == 1)
        composeTestRule.onNodeWithTag("file_search_input").performTextClearance()
        composeTestRule.waitForIdle()

        // Create a text file via dialog
        composeTestRule.onNodeWithTag("file_manager_screen")
            .performScrollToNode(hasTestTag("new_file_button"))
        composeTestRule.onNodeWithTag("new_file_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor_filename_input").performTextInput("hello.txt")
        composeTestRule.onNodeWithTag("editor_content_input").performTextInput("Hello USB Studio")
        composeTestRule.onNodeWithTag("editor_save_button").performClick()

        waitUntilWithLooper(timeoutMillis = 5000L) {
            viewModel.uiState.value.fileEntries.any { it.name == "hello.txt" }
        }

        // Open created file for editing, verify loaded content, and save
        composeTestRule.onNodeWithTag("file_manager_screen")
            .performScrollToNode(hasTestTag("file_item_hello.txt"))
        composeTestRule.onNodeWithTag("file_manager_screen")
            .performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.3f) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("file_item_hello.txt").performClick()
        waitUntilWithLooper(timeoutMillis = 5000L) {
            viewModel.uiState.value.selectedFileForEdit?.name == "hello.txt" &&
                viewModel.uiState.value.editingFileText == "Hello USB Studio"
        }
        composeTestRule.onNodeWithTag("editor_save_button").performClick()
        waitUntilWithLooper(timeoutMillis = 5000L) {
            viewModel.uiState.value.selectedFileForEdit == null
        }

        // Delete created file
        composeTestRule.onNodeWithTag("file_manager_screen")
            .performScrollToNode(hasTestTag("delete_file_hello.txt"))
        composeTestRule.onNodeWithTag("delete_file_hello.txt").performClick()
        waitUntilWithLooper(timeoutMillis = 5000L) {
            viewModel.uiState.value.fileEntries.none { it.name == "hello.txt" }
        }
    }

    @Test
    fun `cuj4_formatter enforces two-step confirmation and completes cleanly`() {
        launchApp(mountSampleSafDrives = 1)

        composeTestRule.onNodeWithTag("nav_tab_format").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("format_tool_screen")
            .performScrollToNode(hasTestTag("arm_format_button"))
        composeTestRule.onNodeWithTag("arm_format_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("format_tool_screen")
            .performScrollToNode(hasTestTag("execute_format_button"))
        composeTestRule.onNodeWithTag("execute_format_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("execute_format_button").performClick()

        waitUntilWithLooper(timeoutMillis = 12000L) {
            !viewModel.uiState.value.formatStatus.isRunning &&
                viewModel.uiState.value.formatStatus.completedVolumeUuid != null
        }
        composeTestRule.onNodeWithTag("format_tool_screen")
            .performScrollToNode(hasText("Complete"))
        composeTestRule.onNodeWithText("Complete").assertIsDisplayed()
    }

    @Test
    fun `cuj5_iso writer flashes selected local image and displays verified completion readout`() {
        launchApp(mountSampleSafDrives = 1)

        val tempIso = File(app.cacheDir, "ui_test_boot.iso")
        tempIso.writeBytes(ByteArray(128 * 1024) { (it % 127).toByte() })

        composeTestRule.onNodeWithTag("nav_tab_iso_writer").performClick()
        composeTestRule.waitForIdle()

        assertTrue(composeTestRule.onAllNodesWithText("FreeDOS", substring = true).fetchSemanticsNodes().isEmpty())

        viewModel.selectCustomIsoUri(Uri.fromFile(tempIso), "ui_test_boot.iso", tempIso.length())
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("iso_writer_screen")
            .performScrollToNode(hasTestTag("execute_iso_flash_button"))
        composeTestRule.onNodeWithTag("execute_iso_flash_button").performClick()

        waitUntilWithLooper(timeoutMillis = 15000L) {
            !viewModel.uiState.value.isoFlashStatus.isRunning &&
                viewModel.uiState.value.isoFlashStatus.sha256Hex.length == 64
        }
        composeTestRule.onNodeWithTag("iso_writer_screen")
            .performScrollToNode(hasText("COMPLETE"))
        composeTestRule.onNodeWithText("COMPLETE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("iso_writer_screen")
            .performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.25f) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SHA-256").assertIsDisplayed()

        tempIso.delete()
    }

    @Test
    fun `cuj6_partition manager stages changes, discards, deletes and reallocates partition, and commits table`() {
        launchApp(mountSampleSafDrives = 1)

        composeTestRule.onNodeWithTag("nav_tab_partitions").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("partition_manager_screen")
            .performScrollToNode(hasTestTag("part_scheme_MBR"))
        composeTestRule.onNodeWithTag("part_scheme_MBR").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("partition_manager_screen")
            .performScrollToNode(hasTestTag("discard_partition_changes_button"))
        composeTestRule.onNodeWithTag("discard_partition_changes_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("discard_partition_changes_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(viewModel.uiState.value.hasUncommittedPartitionChanges)

        composeTestRule.onNodeWithTag("partition_manager_screen")
            .performScrollToNode(hasTestTag("delete_part_1"))
        composeTestRule.onNodeWithTag("delete_part_1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("partition_manager_screen")
            .performScrollToNode(hasTestTag("allocate_part_1"))
        composeTestRule.onNodeWithTag("allocate_part_1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("new_partition_label_input").performTextInput("MEDIA_VOL")
        composeTestRule.onNodeWithTag("confirm_new_partition_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(viewModel.uiState.value.hasUncommittedPartitionChanges)
        assertTrue(viewModel.uiState.value.stagedPartitions.any { it.label == "MEDIA_VOL" })

        composeTestRule.onNodeWithTag("partition_manager_screen")
            .performScrollToNode(hasTestTag("commit_partition_table_button"))
        composeTestRule.onNodeWithTag("commit_partition_table_button").performClick()
        waitUntilWithLooper(timeoutMillis = 5000L) {
            !viewModel.uiState.value.hasUncommittedPartitionChanges
        }
        assertTrue(viewModel.uiState.value.selectedDrive!!.partitions.any { it.label == "MEDIA_VOL" })
    }

    @Test
    fun `cuj7_hex sector inspector dialog displays 512B sector dump and closes cleanly`() {
        launchApp(mountSampleSafDrives = 1)

        viewModel.inspectDriveSector(0L, "LBA 0 • Master Boot Record")
        waitUntilWithLooper(timeoutMillis = 5000L) {
            viewModel.uiState.value.activeHexInspection != null
        }

        composeTestRule.onNodeWithText("LBA 0 • Master Boot Record").assertIsDisplayed()
        composeTestRule.onNodeWithText("512 BYTES").assertIsDisplayed()
        composeTestRule.onNodeWithTag("close_hex_dialog_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.activeHexInspection == null)
    }
}
