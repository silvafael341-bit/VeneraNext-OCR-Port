package com.github.cyrilpeng.veneranext

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.LinkedHashMap

/**
 * Small embedded translation client used by the first native bridge.
 * It mirrors the request/response shape expected by the reader while keeping
 * the engine replaceable: a local Venera-SSR/MML backend can be plugged in
 * here later without changing the MethodChannel contract.
 */
class EmbeddedTranslator(
    private val targetLanguage: String = "pt"
) {
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    }

    @Synchronized
    private fun cached(key: String): String? = cache[key]

    @Synchronized
    private fun put(key: String, value: String) {
        cache[key] = value
    }

    fun translate(text: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        cached(clean)?.let { return it }

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = URL(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=${URLEncoder.encode(targetLanguage, "UTF-8")}" +
                "&dt=t&q=$encoded"
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "VeneraNext")
        }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            val translated = parseFirstTranslation(body)
            if (!translated.isNullOrBlank()) put(clean, translated)
            translated
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFirstTranslation(body: String): String? {
        // The gtx endpoint returns a JSON-like nested array. The first
        // translation segment is the first quoted string in the first array.
        val regex = Regex("\\[\\[\\\"((?:\\\\.|[^\\\"])*)\\\"")
        val match = regex.find(body) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\\\", "\\")
            .trim()
            .ifEmpty { null }
    }
}
