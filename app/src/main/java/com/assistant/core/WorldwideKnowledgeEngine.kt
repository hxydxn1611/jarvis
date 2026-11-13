package com.assistant.core

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Worldwide Zero-Key Autonomous Intelligence Engine for J.A.R.V.I.S.
 * Provides global encyclopedia, search, definitions, calculations, and real-time facts
 * with ZERO API keys required, ensuring Jarvis never goes offline when quota limits are reached.
 */
object WorldwideKnowledgeEngine {

    private const val TAG = "WorldwideKnowledge"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun queryWorldwideIntelligence(query: String): String? = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
            .removePrefix("who is").removePrefix("who was")
            .removePrefix("what is").removePrefix("what was")
            .removePrefix("tell me about").removePrefix("explain")
            .removePrefix("search for").removePrefix("define")
            .trim().removePrefix("a ").removePrefix("the ").trim()

        // 1. Math calculation & evaluation fast-path
        val mathResult = tryEvaluateMath(query)
        if (mathResult != null) {
            return@withContext mathResult
        }

        // 2. Query DuckDuckGo Instant Answer API (Zero Key)
        val ddgAnswer = queryDuckDuckGo(cleanQuery.ifBlank { query })
        if (!ddgAnswer.isNullOrBlank()) {
            return@withContext ddgAnswer
        }

        // 3. Query Wikipedia REST API Summary (Zero Key)
        val wikiAnswer = queryWikipedia(cleanQuery.ifBlank { query })
        if (!wikiAnswer.isNullOrBlank()) {
            return@withContext wikiAnswer
        }

        // 4. Query Wikipedia OpenSearch fallback
        val openSearchAnswer = queryWikipediaOpenSearch(cleanQuery.ifBlank { query })
        if (!openSearchAnswer.isNullOrBlank()) {
            return@withContext openSearchAnswer
        }

        null
    }

    private fun queryDuckDuckGo(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "JarvisAI/2.0 (Linux; Android)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body?.string() ?: ""
            response.close()
            if (body.isBlank()) return null

            val json = gson.fromJson(body, JsonObject::class.java)

            // Direct abstract text
            val abstractText = json.get("AbstractText")?.asString?.trim() ?: ""
            if (abstractText.isNotBlank()) {
                return abstractText
            }

            // Direct answer
            val answer = json.get("Answer")?.asString?.trim() ?: ""
            if (answer.isNotBlank()) {
                return answer
            }

            // Related topics first result
            val related = json.getAsJsonArray("RelatedTopics")
            if (related != null && related.size() > 0) {
                for (elem in related) {
                    if (elem.isJsonObject) {
                        val topicObj = elem.asJsonObject
                        val text = topicObj.get("Text")?.asString?.trim() ?: ""
                        if (text.isNotBlank() && !text.contains("may refer to:")) {
                            return text
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "DuckDuckGo lookup failed for '$query'", e)
            null
        }
    }

    private fun queryWikipedia(title: String): String? {
        return try {
            val encoded = URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
            val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "JarvisMobileAssistant/2.0 (support@jarvis.ai)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body?.string() ?: ""
            response.close()
            if (body.isBlank()) return null

            val json = gson.fromJson(body, JsonObject::class.java)
            val extract = json.get("extract")?.asString?.trim() ?: ""
            if (extract.isNotBlank() && !extract.contains("refer to:", ignoreCase = true)) {
                return extract
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia summary lookup failed for '$title'", e)
            null
        }
    }

    private fun queryWikipediaOpenSearch(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=2&namespace=0&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "JarvisMobileAssistant/2.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body?.string() ?: ""
            response.close()
            if (body.isBlank()) return null

            val arr = gson.fromJson(body, JsonArray::class.java)
            if (arr != null && arr.size() >= 3) {
                val descriptions = arr.get(2)?.asJsonArray
                if (descriptions != null && descriptions.size() > 0) {
                    val desc = descriptions.get(0)?.asString?.trim() ?: ""
                    if (desc.isNotBlank()) return desc
                }
                val titles = arr.get(1)?.asJsonArray
                if (titles != null && titles.size() > 0) {
                    val resolvedTitle = titles.get(0)?.asString?.trim() ?: ""
                    if (resolvedTitle.isNotBlank() && !resolvedTitle.equals(query, ignoreCase = true)) {
                        return queryWikipedia(resolvedTitle)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia OpenSearch failed for '$query'", e)
            null
        }
    }

    private fun tryEvaluateMath(input: String): String? {
        val lower = input.lowercase().trim()
        val mathPattern = Pattern.compile("""(?i)\b(calculate|what is|solve)?\s*(-?\d+(\.\d+)?)\s*([\+\-\*\/x\^]|plus|minus|times|multiplied by|divided by)\s*(-?\d+(\.\d+)?)\b""")
        val matcher = mathPattern.matcher(lower)
        if (matcher.find()) {
            try {
                val num1 = matcher.group(2)?.toDoubleOrNull() ?: return null
                val opStr = matcher.group(4)?.lowercase() ?: return null
                val num2 = matcher.group(5)?.toDoubleOrNull() ?: return null

                val res = when {
                    opStr == "+" || opStr == "plus" -> num1 + num2
                    opStr == "-" || opStr == "minus" -> num1 - num2
                    opStr == "*" || opStr == "x" || opStr == "times" || opStr == "multiplied by" -> num1 * num2
                    opStr == "/" || opStr == "divided by" -> if (num2 != 0.0) num1 / num2 else Double.NaN
                    opStr == "^" -> Math.pow(num1, num2)
                    else -> return null
                }

                if (res.isNaN()) return "Division by zero is undefined, Sir."
                val formatted = if (res == Math.floor(res) && !res.isInfinite()) res.toLong().toString() else String.format("%.2f", res)
                return "The result is $formatted, Sir."
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }
}
