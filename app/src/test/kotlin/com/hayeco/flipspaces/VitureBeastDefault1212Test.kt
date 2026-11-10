package com.hayeco.flipspaces

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.spatial.VitureDevices
import com.hayeco.flipspaces.spatial.SpaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * 1.2.12 (Discussion #24, third report): VITURE head tracking on by default on the Beast, a saved off kept off, the
 * setting kept with the glasses unplugged, and the Beast in the plug-in device filter so USB access comes back at a replug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VitureBeastDefault1212Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("space", Context.MODE_PRIVATE)

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    @Test fun nothingSavedAndNoVitureSeenIsOff() {
        val s = SpaceStore(app)
        assertNull(s.vitureProduct)
        assertFalse(s.vitureHeadTracking)
    }

    @Test fun theBeastLastSeenIsOnByDefaultWhileUnplugged() {
        // No USB devices under Robolectric: the glasses are unplugged, the last VITURE product seen is the Beast.
        prefs.edit().putInt("viture_product_1212", VitureDevices.BEAST).commit()
        val s = SpaceStore(app)
        assertEquals(VitureDevices.BEAST, s.vitureProduct)
        assertTrue(s.vitureHeadTracking)
    }

    @Test fun otherVitureModelsStayOffByDefault() {
        prefs.edit().putInt("viture_product_1212", 0x1201).commit()
        assertFalse(SpaceStore(app).vitureHeadTracking)
    }

    @Test fun aBeastUserWhoTurnedItOffKeepsItOff() {
        prefs.edit().putInt("viture_product_1212", VitureDevices.BEAST).commit()
        SpaceStore(app).vitureHeadTracking = false
        // A new store, as after an unplug, a replug or the app's restart.
        assertFalse(SpaceStore(app).vitureHeadTracking)
        assertFalse(prefs.getBoolean("viture_head_tracking", true))
    }

    @Test fun aSavedOnFromOlderVersionsCarriesOver() {
        // 1.2.8 to 1.2.11 saved the switch under this key; on another model it stays on.
        prefs.edit().putInt("viture_product_1212", 0x1201).putBoolean("viture_head_tracking", true).commit()
        assertTrue(SpaceStore(app).vitureHeadTracking)
    }

    @Test fun theBeastIsInThePlugInDeviceFilter() {
        val ids = mutableListOf<Pair<Int, Int>>()
        app.resources.getXml(R.xml.usb_glasses).use { x ->
            while (x.next() != XmlPullParser.END_DOCUMENT) {
                if (x.eventType == XmlPullParser.START_TAG && x.name == "usb-device") {
                    ids += x.getAttributeValue(null, "vendor-id").toInt() to x.getAttributeValue(null, "product-id").toInt()
                }
            }
        }
        assertTrue(ids.toString(), (0x35CA to VitureDevices.BEAST) in ids)
        // XREAL's are still there (the Aura first).
        assertTrue((0x3318 to 0x043A) in ids)
        // The microphone has no motion and is not offered.
        assertFalse((0x35CA to 0x1102) in ids)
    }
}
