package com.safepulse.wear.data

import java.text.Normalizer
import java.util.Locale

data class WearVoiceKeywordMatch(
    val matched: Boolean,
    val keyword: String? = null,
    val confidence: Float = 0f,
    val sourceText: String = ""
)

object WearVoiceKeywordMatcher {
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
        "help" to listOf("help", "help me", "please help", "help please"),
        "save me" to listOf("save me", "save my life", "please save me"),
        "emergency" to listOf("emergency", "it is an emergency", "this is an emergency"),
        "sos" to listOf("sos", "s o s", "send sos"),
        "bachao" to listOf("bachao", "bachaao", "bachav", "bacha lo", "bachalo", "mujhe bachao"),
        "madad" to listOf("madad", "madad karo", "mujhe madad chahiye", "help chahiye"),
        "police" to listOf("police", "call police", "police bulao")
    )

    fun findBestMatch(results: List<String>, minConfidence: Float = 0.62f): WearVoiceKeywordMatch {
        var bestMatch = WearVoiceKeywordMatch(false)

        results.forEachIndexed { index, text ->
            val normalized = normalize(text)
            if (normalized.isBlank()) return@forEachIndexed

            phraseGroups.forEach { (keyword, phrases) ->
                phrases.forEach { phrase ->
                    val score = phraseScore(normalized, normalize(phrase), index)
                    if (score > bestMatch.confidence) {
                        bestMatch = WearVoiceKeywordMatch(
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
            bestMatch.copy(matched = false, keyword = null)
        }
    }

    private fun phraseScore(text: String, phrase: String, resultIndex: Int): Float {
        if (text.isBlank() || phrase.isBlank()) return 0f
        val rankPenalty = (resultIndex * 0.04f).coerceAtMost(0.16f)

        if (hasPhraseBoundary(text, phrase)) {
            return 0.96f - rankPenalty
        }

        val textTokens = text.split(" ").filter { it.isNotBlank() }
        val phraseTokens = phrase.split(" ").filter { it.isNotBlank() }
        if (phraseTokens.isEmpty()) return 0f

        val matchedTokens = phraseTokens.count { token ->
            textTokens.any { candidate -> candidate == token || tokenSimilarity(candidate, token) >= 0.82f }
        }

        val coverage = matchedTokens.toFloat() / phraseTokens.size
        return (coverage * 0.76f) - rankPenalty
    }

    private fun hasPhraseBoundary(text: String, phrase: String): Boolean {
        return text == phrase ||
                text.startsWith("$phrase ") ||
                text.endsWith(" $phrase") ||
                text.contains(" $phrase ")
    }

    private fun tokenSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.length < 3 || b.length < 3) return 0f

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
