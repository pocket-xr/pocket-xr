package com.hayeco.flipspaces.spatial

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.spatial.SteadyArrange
import com.hayeco.flipspaces.ui.steadyGapStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Steady mode's Screen gap ([SpaceStore.steadyScreenGap]) and its remembered on or off ([SpaceStore.steadyRemembered]):
 * the defaults change nothing for someone who never touches them, and both are saved for the next start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SteadySettingsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        app.getSharedPreferences("space", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theScreenGapIsTheOldGapUntilChanged() = assertEquals(SteadyArrange.GAP_SHARE, SpaceStore(app).steadyScreenGap, 0f)

    @Test
    fun theScreenGapIsSavedWithinItsRange() {
        SpaceStore(app).steadyScreenGap = 0f
        assertEquals(0f, SpaceStore(app).steadyScreenGap, 0f)
        SpaceStore(app).steadyScreenGap = 0.07f
        assertEquals(0.07f, SpaceStore(app).steadyScreenGap, 0f)
        SpaceStore(app).steadyScreenGap = 0.5f
        assertEquals(SteadyArrange.MAX_GAP, SpaceStore(app).steadyScreenGap, 0f)
    }

    @Test
    fun theSliderStopsEveryHundredth() {
        assertEquals(0f, steadyGapStep(0.004f), 0f)
        assertEquals(0.04f, steadyGapStep(0.0412f), 0f)
        assertEquals(0.1f, steadyGapStep(0.0999f), 0f)
    }

    @Test
    fun steadyModeIsOffUntilTheUserTurnsItOn() = assertFalse(SpaceStore(app).steadyRemembered)

    @Test
    fun theUsersChoiceIsSaved() {
        SpaceStore(app).steadyRemembered = true
        assertTrue(SpaceStore(app).steadyRemembered)
        SpaceStore(app).steadyRemembered = false
        assertFalse(SpaceStore(app).steadyRemembered)
    }
}
