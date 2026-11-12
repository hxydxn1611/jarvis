package com.assistant.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object MemoryCore {

    private const val PREF_NAME = "JarvisEpisodicMemory"
    private const val KEY_MEMORIES = "stored_memories"
    private val gson = Gson()

    fun saveFact(context: Context, key: String, value: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = getAllMemories(context).toMutableMap()
        current[key] = value
        prefs.edit().putString(KEY_MEMORIES, gson.toJson(current)).apply()
    }

    fun getAllMemories(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MEMORIES, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun dumpKnowledgeContext(context: Context): String {
        val memories = getAllMemories(context)
        if (memories.isEmpty()) return "No personalized user facts stored yet."
        return memories.entries.joinToString("; ") { "${it.key}: ${it.value}" }
    }
}

