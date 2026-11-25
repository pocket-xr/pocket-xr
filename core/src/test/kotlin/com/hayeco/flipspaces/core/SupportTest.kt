package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.1, 1.1.15: the "Buy me a beer" row at the top of the trackpad's Menu ([Support]). */
class SupportTest {
    /** 1.6.26: the Play build shows it too, as a tip through Google Play (Support.TIP_PRODUCT). */
    @Test
    fun theRowShowsInEveryBuild() {
        assertTrue("GitHub build", Support.shows(playBuild = false))
        assertTrue("Google Play build", Support.shows(playBuild = true))
    }

    @Test
    fun koFiIsFirstAndTheUpdateFollowsIt() {
        assertEquals(listOf(Support.MenuTop.KO_FI), Support.menuTop(playBuild = false, update = null))
        assertEquals(listOf(Support.MenuTop.KO_FI, Support.MenuTop.UPDATE), Support.menuTop(playBuild = false, update = "1.1.16"))
    }

    /** 1.6.26: the Play build's row is the tip through Play: its price where Ko-fi's name is, nothing until Play says it. */
    @Test
    fun thePlayBuildTipsThroughPlay() {
        assertEquals(listOf(Support.MenuTop.KO_FI), Support.menuTop(playBuild = true, update = null))
        assertEquals("tip_beer", Support.TIP_PRODUCT)
        assertEquals("Ko-fi", Support.where(playBuild = false, price = "$2.99"))
        assertEquals("$2.99", Support.where(playBuild = true, price = "$2.99"))
        assertEquals("", Support.where(playBuild = true, price = null))
        assertTrue(Support.description(playBuild = true).contains("Google Play"))
        assertFalse(Support.description(playBuild = true).contains("Ko-fi"))
        assertTrue(Support.description(playBuild = false).contains("Ko-fi"))
    }

    @Test
    fun theLinkIsMikesKoFiPage() {
        assertEquals("https://ko-fi.com/mikehaye", Support.KO_FI_URL)
    }

    @Test
    fun theCopyIsShortAndPlain() {
        for (line in listOf(Support.LABEL, Support.WHERE, Support.DESCRIPTION)) {
            assertFalse("no em or en dash: $line", line.contains('—') || line.contains('–'))
            assertFalse("no \"we\": $line", Regex("\\bwe\\b", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
        assertEquals("Buy me a beer", Support.LABEL)
        assertTrue("one slim line", Support.LABEL.length + Support.WHERE.length <= 24)
        assertTrue("TalkBack names Ko-fi", Support.DESCRIPTION.contains("Ko-fi"))
    }

    /** 1.2.6: the pill is the theme's blue, faint, and its text still reads well (WCAG AA is 4.5 to 1). */
    @Test
    fun thePillIsAFaintThemeBlueWithReadableText() {
        assertEquals(Look.ACCENT and 0xFFFFFF, Support.FILL and 0xFFFFFF)
        val fillAlpha = (Support.FILL ushr 24) / 255f
        assertTrue("about 15 to 20 %: $fillAlpha", fillAlpha in 0.15f..0.20f)
        assertEquals(Look.ACCENT and 0xFFFFFF, Support.RIM and 0xFFFFFF)
        val fill = over(Support.FILL, Look.PAGE)
        assertEquals(0x1A253B, fill and 0xFFFFFF)
        assertTrue("white on the pill", contrast(over(Support.TEXT, fill), fill) >= 12.0)
        assertTrue("the accent on the pill", contrast(over(Support.WHERE_TEXT, fill), fill) >= 4.5)
    }

    private fun over(top: Int, bottom: Int): Int {
        val a = (top ushr 24) / 255f
        fun ch(s: Int) = (((top shr s) and 0xFF) * a + ((bottom shr s) and 0xFF) * (1 - a) + 0.5f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(c: Int): Double {
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
