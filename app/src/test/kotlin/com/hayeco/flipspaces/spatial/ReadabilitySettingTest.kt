package com.hayeco.flipspaces.spatial

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.R
import com.hayeco.flipspaces.ui.SpaceSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Readability's switch in the Space settings ([SpaceStore.readability]): off unless switched on, saved so a new start of
 * the app keeps it, off again after the screen's reset, and named the same as in the other Pocket XR versions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadabilitySettingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        app.getSharedPreferences("space", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun offByDefault() {
        assertFalse(SpaceStore(app).readability)
        assertFalse(SpaceSettings(1f, 1f, true).readability)
    }

    @Test
    fun savedAcrossARestart() {
        SpaceStore(app).readability = true
        assertTrue(app.getSharedPreferences("space", Context.MODE_PRIVATE).getBoolean("readability", false))
        assertTrue(SpaceStore(app).readability)
        SpaceStore(app).readability = false
        assertFalse(SpaceStore(app).readability)
    }

    @Test
    fun resetTurnsItOff() {
        val store = SpaceStore(app)
        store.readability = true
        store.resetSpatialSettings()
        assertFalse(store.readability)
    }

    @Test
    fun theSwitchIsCalledReadability() {
        assertEquals("Readability", app.getString(R.string.home_readability))
        assertTrue(app.getString(R.string.home_readability_help).isNotBlank())
    }
}
