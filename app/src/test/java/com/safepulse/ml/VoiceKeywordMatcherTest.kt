package com.safepulse.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeywordMatcherTest {

    @Test
    fun detectsClearEmergencyKeywords() {
        val samples = mapOf(
            "help" to "help",
            "please help me now" to "help",
            "this is an emergency" to "emergency",
            "save me please" to "save me",
            "bachao bachao" to "bachao",
            "madad karo" to "madad",
            "call police" to "police"
        )

        samples.forEach { (phrase, expectedKeyword) ->
            val match = VoiceKeywordMatcher.findBestMatch(listOf(phrase))
            assertTrue("Expected match for '$phrase'", match.matched)
            assertEquals(expectedKeyword, match.keyword)
        }
    }

    @Test
    fun ignoresUnclearOrUnrelatedPhrases() {
        val samples = listOf(
            "hello there",
            "open settings",
            "I know",
            "shelf is empty",
            "music is playing"
        )

        samples.forEach { phrase ->
            val match = VoiceKeywordMatcher.findBestMatch(listOf(phrase))
            assertFalse("Expected no SOS match for '$phrase'", match.matched)
        }
    }

    @Test
    fun detectsConfirmationCancelWordsWithBoundaries() {
        assertTrue(VoiceKeywordMatcher.containsCancelWord(listOf("no")).matched)
        assertTrue(VoiceKeywordMatcher.containsCancelWord(listOf("please cancel")).matched)
        assertTrue(VoiceKeywordMatcher.containsCancelWord(listOf("nahi")).matched)
        assertFalse(VoiceKeywordMatcher.containsCancelWord(listOf("I know")).matched)
    }
}
