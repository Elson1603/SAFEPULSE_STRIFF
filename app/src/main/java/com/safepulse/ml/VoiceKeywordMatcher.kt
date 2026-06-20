package com.safepulse.ml

import java.text.Normalizer
import java.util.Locale

data class VoiceKeywordMatch(
    val matched: Boolean,
    val keyword: String? = null,
    val confidence: Float = 0f,
    val sourceText: String = ""
)

/**
 * Shared phrase matcher for voice SOS, voice tests, and shake confirmation.
 * It normalizes noisy speech-recognition text and scores phrase/token matches
 * instead of relying on plain substring checks.
 */
object VoiceKeywordMatcher {
    val supportedKeywords = listOf(
        "help",
        "help me",
        "save me",
        "emergency",
        "sos",
        "bachao",
        "madad",
        "police"
    )

    private val phraseGroups = listOf(
        "help" to listOf("help", "help me", "please help", "help please", "need help", "i need help"),
        "save me" to listOf("save me", "save my life", "please save me", "save me please"),
        "emergency" to listOf("emergency", "it is an emergency", "this is an emergency", "i have emergency"),
        "sos" to listOf("sos", "s o s", "send sos", "start sos", "trigger sos"),
        "bachao" to listOf("bachao", "bachaao", "bachav", "bacha lo", "bachalo", "mujhe bachao", "mujhe bacha lo", "bachana"),
        "madad" to listOf("madad", "madad karo", "mujhe madad chahiye", "help chahiye", "madad chahiye"),
        "police" to listOf("police", "call police", "police bulao", "police ko bulao", "call the police")
    )

    private val cancelGroups = listOf(
        "no" to listOf(
            "no",
            "nope",
            "no no",
            "cancel",
            "cancel it",
            "stop",
            "stop it",
            "false",
            "negative",
            "not emergency",
            "not an emergency",
            "no emergency",
            "nahi",
            "nahin",
            "nai",
            "mat karo"
        )
    )

    private val confirmationGroups = listOf(
        "yes" to listOf(
            "yes",
            "yes yes",
            "yeah",
            "yep",
            "ya",
            "haan",
            "ha",
            "hanji",
            "yes help",
            "need help",
            "i need help",
            "send help",
            "emergency",
            "start sos",
            "trigger sos"
        )
    )

    fun findBestMatch(results: List<String>, minConfidence: Float = 0.62f): VoiceKeywordMatch {
        return findMatch(prepareRecognitionResults(results), phraseGroups, minConfidence)
    }

    fun containsCancelWord(results: List<String>): VoiceKeywordMatch {
        return findMatch(prepareRecognitionResults(results), cancelGroups, minConfidence = 0.70f)
    }

    fun containsConfirmationWord(results: List<String>): VoiceKeywordMatch {
        return findMatch(prepareRecognitionResults(results), confirmationGroups, minConfidence = 0.70f)
    }

    fun prepareRecognitionResults(results: List<String>): List<String> {
        return results
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalize(it) }
    }

    private fun findMatch(
        results: List<String>,
        groups: List<Pair<String, List<String>>>,
        minConfidence: Float
    ): VoiceKeywordMatch {
        var bestMatch = VoiceKeywordMatch(false)

        results.forEachIndexed { index, text ->
            val normalized = normalize(text)
            if (normalized.isBlank()) return@forEachIndexed

            groups.forEach { (keyword, phrases) ->
                phrases.forEach { phrase ->
                    val score = phraseScore(normalized, normalize(phrase), index)
                    if (score > bestMatch.confidence) {
                        bestMatch = VoiceKeywordMatch(
                            matched = score >= minConfidence,
                            keyword = keyword,
                            confidence = score,
                            sourceText = text
                        )
                    }
                }
            }
        }

        return if (bestMatch.confidence >= minConfidence) {
            bestMatch.copy(matched = true)
        } else {
            VoiceKeywordMatch(false)
        }
    }

    private fun phraseScore(text: String, phrase: String, resultIndex: Int): Float {
        if (text.isBlank() || phrase.isBlank()) return 0f
        val rankPenalty = (resultIndex * 0.035f).coerceAtMost(0.14f)

        if (hasPhraseBoundary(text, phrase)) {
            return 0.97f - rankPenalty
        }

        val textTokens = text.split(" ").filter { it.isNotBlank() }
        val phraseTokens = phrase.split(" ").filter { it.isNotBlank() }
        if (phraseTokens.isEmpty()) return 0f

        val matchedTokens = phraseTokens.count { token ->
            textTokens.any { candidate ->
                candidate == token ||
                    (token.length >= 3 && candidate.length >= 3 && candidate.startsWith(token)) ||
                    (token.length >= 3 && candidate.length >= 3 && token.startsWith(candidate)) ||
                    tokenSimilarity(candidate, token) >= 0.80f
            }
        }

        val coverage = matchedTokens.toFloat() / phraseTokens.size
        return (coverage * 0.78f) - rankPenalty
    }

    private fun hasPhraseBoundary(text: String, phrase: String): Boolean {
        return text == phrase ||
            text.startsWith("$phrase ") ||
            text.endsWith(" $phrase") ||
            text.contains(" $phrase ")
    }

    private fun tokenSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.length < 2 || b.length < 2) return 0f

        val distance = levenshteinDistance(a, b)
        val maxLength = maxOf(a.length, b.length)
        return 1f - (distance.toFloat() / maxLength)
    }

    private fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return ascii
            .replace("&", " and ")
            .replace("[^\\p{L}\\p{Nd}\\s]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val costs = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var previous = i
            costs[0] = i + 1
            for (j in b.indices) {
                val current = costs[j + 1]
                costs[j + 1] = minOf(
                    costs[j + 1] + 1,
                    costs[j] + 1,
                    previous + if (a[i] == b[j]) 0 else 1
                )
                previous = current
            }
        }
        return costs[b.length]
    }
}
