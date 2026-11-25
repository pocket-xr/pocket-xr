package com.hayeco.flipspaces.core.spatial

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.6.20: Windows in the Space, 6 (as before) to 12, for up to six PC screens and more apps. */
class WindowsInTheSpace1620Test {
    @After fun back() { PanelLimits.maxPanels = PanelLimits.MAX_PANELS }

    private fun filled(n: Int): SpaceLayout {
        var l = SpaceLayout()
        repeat(n) { l = l.addAt("A$it", "p$it", "a", it * 20f, 0f)!!.first }
        return l
    }

    @Test fun sixUnlessRaised() {
        assertEquals(6, PanelLimits.maxPanels)
        assertNull(filled(6).addAt("B", "q", "a", 0f, 0f))
    }

    @Test fun raisedToTwelve() {
        PanelLimits.maxPanels = 12
        val eleven = filled(11)
        assertNotNull(eleven.addAt("B", "q", "a", 0f, 0f))
        assertNull(filled(12).addAt("C", "r", "a", 0f, 0f))
    }

    @Test fun keptWithinSixAndTwelve() {
        PanelLimits.maxPanels = 3
        assertEquals(6, PanelLimits.maxPanels)
        PanelLimits.maxPanels = 40
        assertEquals(12, PanelLimits.maxPanels)
    }

    @Test fun aSavedSpaceOfTwelveReadsBack() {
        PanelLimits.maxPanels = 12
        val twelve = filled(12)
        assertEquals(12, LayoutCodec.decode(LayoutCodec.encode(twelve)).panels.size)
    }
}
