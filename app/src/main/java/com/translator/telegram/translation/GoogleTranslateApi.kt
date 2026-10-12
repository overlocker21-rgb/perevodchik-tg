package com.translator.telegram.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GoogleTranslateApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Translates input text using Google Translate GTX endpoint.
     * No API key required.
     */
    suspend fun translate(
        text: String,
        sourceLang: String = "auto",
        targetLang: String = "ru"
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.success("")
        }

        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP Error: ${response.code}"))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
                val jsonArray = JSONArray(body)

                // Google GTX format: [[["Translated part", "Original part", ...], ...], ...]
                val sentencesArray = jsonArray.optJSONArray(0)
                    ?: return@withContext Result.failure(Exception("Invalid JSON format from translator"))

                val resultBuilder = StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val sentence = sentencesArray.optJSONArray(i)
                    if (sentence != null) {
                        val translatedChunk = sentence.optString(0, "")
                        resultBuilder.append(translatedChunk)
                    }
                }

                val fullTranslation = resultBuilder.toString().trim()
                if (fullTranslation.isNotEmpty()) {
                    Result.success(fullTranslation)
                } else {
                    Result.failure(Exception("Failed to extract translated text"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
