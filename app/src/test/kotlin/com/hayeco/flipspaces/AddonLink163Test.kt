package com.hayeco.flipspaces

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.platform.AddonLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.3: where the add-on's services are. The Google Play build carries them itself, so they are Pocket XR's own
 * components (com.hayeco.flipspaces/com.hayeco.flipspaces.addon.PointerAccess) and the add-on always counts as installed;
 * every other build still reaches the separate add-on app, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AddonLink163Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun builtInServicesAreInPocketXrsOwnPackage() {
        val holder = AddonLink.holder(builtIn = true, appPackage = "com.hayeco.flipspaces")
        assertEquals("com.hayeco.flipspaces", holder)
        assertEquals(
            ComponentName("com.hayeco.flipspaces", "com.hayeco.flipspaces.addon.PointerAccess"),
            AddonLink.component(holder, "PointerAccess"),
        )
        assertEquals(
            ComponentName("com.hayeco.flipspaces", "com.hayeco.flipspaces.addon.NotesListener"),
            AddonLink.component(holder, "NotesListener"),
        )
    }

    @Test fun separateAddonKeepsItsOwnPackage() {
        val holder = AddonLink.holder(builtIn = false, appPackage = "com.hayeco.flipspaces")
        assertEquals("com.hayeco.flipspaces.addon", holder)
        // The same name the add-on's services always had, so GitHub and debug builds are unchanged.
        assertEquals("com.hayeco.flipspaces.addon/.PointerAccess", AddonLink.component(holder, "PointerAccess").flattenToShortString())
        assertEquals("com.hayeco.flipspaces.addon/.NotesListener", AddonLink.component(holder, "NotesListener").flattenToShortString())
    }

    /** This build's own choice: built in only in the Play build (-Ppocketxr.play=true). */
    @Test fun thisBuild() {
        assertEquals(BuildConfig.PLAY_BUILD, AddonLink.BUILT_IN)
        if (BuildConfig.PLAY_BUILD) {
            assertEquals(app.packageName, AddonLink.PACKAGE)
            assertEquals(ComponentName(app.packageName, "com.hayeco.flipspaces.addon.PointerAccess"), AddonLink.POINTER)
            assertTrue(AddonLink.isInstalled(app))
        } else {
            assertEquals(AddonLink.ADDON_PACKAGE, AddonLink.PACKAGE)
            assertEquals(ComponentName(AddonLink.ADDON_PACKAGE, "com.hayeco.flipspaces.addon.PointerAccess"), AddonLink.POINTER)
            assertEquals(ComponentName(AddonLink.ADDON_PACKAGE, "com.hayeco.flipspaces.addon.NotesListener"), AddonLink.NOTES)
            // Robolectric has no add-on installed.
            assertFalse(AddonLink.isInstalled(app))
        }
    }
}
