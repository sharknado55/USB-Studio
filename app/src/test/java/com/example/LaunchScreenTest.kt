package com.example

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LaunchScreenTest {

    @Test
    fun `Android 11 adds the branded splash on cold activity creation`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        Robolectric.buildActivity(MainActivity::class.java).create().use { controller ->
            val activity = controller.get()
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val splash = content.getChildAt(content.childCount - 1) as FrameLayout

            assertEquals(activity.getColor(R.color.launcher_purple), (splash.background as ColorDrawable).color)
            val logo = splash.getChildAt(0)
            assertTrue(logo is ImageView)
            assertNotNull((logo as ImageView).drawable)
        }
    }
}
