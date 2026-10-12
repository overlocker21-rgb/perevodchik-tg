package com.translator.telegram.translation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

object TranslationRepository {

    // In-memory cache for translated strings to prevent redundant API calls
    private val translationCache = ConcurrentHashMap<String, String>()

    // Regex to detect Russian Cyrillic characters
    private val cyrillicRegex = Regex("[а-яА-ЯёЁ]")

    /**
     * Checks whether text is already written in Russian.
     */
    fun isAlreadyRussian(text: String): Boolean {
        val totalLetters = text.count { it.isLetter() }
        if (totalLetters == 0) return true // Only numbers/symbols, don't translate
        val cyrillicLetters = text.count { cyrillicRegex.matches(it.toString()) }
        // If more than 50% of characters are Cyrillic, consider it already Russian
        return (cyrillicLetters.toFloat() / totalLetters.toFloat()) > 0.4f
    }

    /**
     * Translates single text with caching and language check.
     */
    suspend fun translate(text: String, targetLang: String = "ru"): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        // Return from cache if available
        translationCache[trimmed]?.let { return it }

        // If it's already in Russian, no translation needed
        if (isAlreadyRussian(trimmed)) {
            translationCache[trimmed] = trimmed
            return trimmed
        }

        val result = GoogleTranslateApi.translate(
            text = trimmed,
            sourceLang = "auto",
            targetLang = targetLang
        )

        return result.getOrNull()?.also { translated ->
            translationCache[trimmed] = translated
        } ?: "[Ошибка перевода: проверьте интернет]"
    }

    /**
     * Translates a list of strings concurrently.
     */
    suspend fun translateBatch(
        texts: List<String>,
        targetLang: String = "ru"
    ): Map<String, String> = coroutineScope {
        val uniqueTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val deferredResults = uniqueTexts.map { text ->
            async {
                val translation = translate(text, targetLang)
                text to translation
            }
        }

        deferredResults.awaitAll().toMap()
    }

    fun clearCache() {
        translationCache.clear()
    }
}
