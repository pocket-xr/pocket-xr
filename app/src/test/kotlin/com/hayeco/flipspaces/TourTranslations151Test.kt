package com.hayeco.flipspaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.5.1: the tour in every language Pocket XR speaks. Each translated strings.xml has every one of the tour's strings,
 * none of them empty or left in English by accident, with the same %1$s / %1$d places as the English, so no step falls
 * back to English or loses a glasses' name.
 */
class TourTranslations151Test {
    private val res = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val string = Regex("""<string name="((?:tour_|manifest_tour)[^"]*)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val place = Regex("""%\d+\$[sd]""")

    private fun tour(folder: String): Map<String, String> =
        string.findAll(File(res, "$folder/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private val english = tour("values")
    private val translated = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").isFile }!!.map { it.name }.sorted()

    @Test
    fun everyLanguageHasEveryTourString() {
        assertTrue("the tour's strings are in values/strings.xml", english.size > 80)
        assertEquals(13, translated.size)
        for (folder in translated) {
            assertEquals("$folder: the tour's strings", english.keys, tour(folder).keys)
        }
    }

    @Test
    fun theSamePlacesForNamesAndNumbers() {
        for (folder in translated) {
            val t = tour(folder)
            for ((key, text) in english) {
                assertEquals("$folder $key", place.findAll(text).map { it.value }.sorted().toList(), place.findAll(t.getValue(key)).map { it.value }.sorted().toList())
                assertTrue("$folder $key is empty", t.getValue(key).isNotBlank())
            }
        }
    }

    @Test
    fun theSentencesAreTranslatedNotCopied() {
        // Names and "3D" are the same everywhere; a whole English sentence in another language's file is a miss.
        val sentences = english.filter { it.value.count { c -> c == ' ' } >= 4 }
        assertTrue(sentences.size > 50)
        for (folder in translated) {
            val t = tour(folder)
            for ((key, text) in sentences) assertTrue("$folder $key is still English", t.getValue(key) != text)
        }
    }

    @Test
    fun theTwoNamesOfALanguageSayTheSame() {
        // Android reads Chinese and Portuguese from either folder name, so each pair is kept identical.
        assertEquals(tour("values-b+zh+Hans"), tour("values-zh-rCN"))
        assertEquals(tour("values-b+zh+Hant"), tour("values-zh-rTW"))
        assertEquals(tour("values-pt"), tour("values-pt-rBR"))
    }
}
