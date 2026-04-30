package com.gooserelay.gooserelayvpn.util

import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

object ConfigGenerator {
    private val gson = Gson()

    fun generateConfig(profile: ProfileEntity): String {
        val root = JsonObject().apply {
            addProperty("debug_timing", profile.debugTiming)
            addProperty("socks_host", profile.socksHost)
            addProperty("socks_port", profile.socksPort)
            addProperty("socks_user", profile.socksUser)
            addProperty("socks_pass", profile.socksPass)
            addProperty("google_host", profile.googleHost)
            add("sni", parseSni(profile.sniJson))
            add("script_keys", parseScriptKeys(profile.scriptKeysText))
            addProperty("tunnel_key", profile.tunnelKey)
        }
        return gson.toJson(root)
    }

    fun exportProfileJson(profile: ProfileEntity): String = generateConfig(profile)

    private fun parseSni(value: String): JsonArray {
        if (value.isBlank()) return JsonArray()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val list = gson.fromJson<List<String>>(value, type).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            JsonArray().apply { list.forEach { add(it) } }
        } catch (_: Exception) {
            JsonArray()
        }
    }

    private fun parseScriptKeys(text: String): JsonArray {
        val keys = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return JsonArray().apply { keys.forEach { add(it) } }
    }
}
