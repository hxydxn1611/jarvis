package com.assistant.core

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class MemoryNode(
    val category: String, // PREFERENCE, HABIT, RELATIONSHIP, RULE, PROJECT, FACT
    val key: String,
    val fact: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Semantic Long-Term Knowledge Graph for J.A.R.V.I.S.
 * Automatically preserves and links personal facts, habits, rules, and preferences.
 */
object KnowledgeGraph {

    private const val PREFS_NAME = "JarvisKnowledgeGraph"
    private const val KEY_NODES = "memory_nodes"
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAllNodes(context: Context): List<MemoryNode> {
        val json = getPrefs(context).getString(KEY_NODES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MemoryNode>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveNode(context: Context, category: String, key: String, fact: String) {
        val current = getAllNodes(context).toMutableList()
        // Replace existing entry if matching key exists
        current.removeAll { it.key.equals(key, ignoreCase = true) }
        current.add(MemoryNode(category.uppercase(), key, fact))
        
        val json = gson.toJson(current)
        getPrefs(context).edit().putString(KEY_NODES, json).apply()
    }

    @Synchronized
    fun saveMultipleNodes(context: Context, nodes: List<MemoryNode>) {
        val current = getAllNodes(context).toMutableList()
        for (node in nodes) {
            current.removeAll { it.key.equals(node.key, ignoreCase = true) }
            current.add(node)
        }
        val json = gson.toJson(current)
        getPrefs(context).edit().putString(KEY_NODES, json).apply()
    }

    @Synchronized
    fun deleteNode(context: Context, key: String) {
        val current = getAllNodes(context).toMutableList()
        current.removeAll { it.key.equals(key, ignoreCase = true) }
        val json = gson.toJson(current)
        getPrefs(context).edit().putString(KEY_NODES, json).apply()
    }

    fun buildPromptContext(context: Context): String {
        val nodes = getAllNodes(context)
        if (nodes.isEmpty()) return "No personal records indexed yet."

        val grouped = nodes.groupBy { it.category }
        val sb = StringBuilder()
        for ((cat, list) in grouped) {
            sb.append("[$cat]:\n")
            for (node in list.takeLast(6)) {
                sb.append(" • ${node.key}: ${node.fact}\n")
            }
        }
        return sb.toString().trim()
    }
}
