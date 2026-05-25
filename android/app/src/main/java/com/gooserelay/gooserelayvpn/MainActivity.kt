package com.gooserelay.gooserelayvpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.lifecycleScope
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.ui.navigation.AppNavigation
import com.gooserelay.gooserelayvpn.ui.theme.GooseRelayVPNTheme
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var profileRepository: ProfileRepository

    private val gson = Gson()
    @Volatile
    private var lastHandledImportUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleJsonImportIntent(intent)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                GooseRelayVPNTheme {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJsonImportIntent(intent)
    }

    private fun handleJsonImportIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return

        val uri = when {
            intent.data != null -> intent.data
            intent.clipData != null && intent.clipData!!.itemCount > 0 -> intent.clipData!!.getItemAt(0).uri
            else -> null
        } ?: return

        val uriToken = uri.toString()
        if (lastHandledImportUri == uriToken) return
        val mime = intent.type.orEmpty()
        val isJsonLike = mime.contains("json", ignoreCase = true) ||
            uri.toString().lowercase().endsWith(".json")
        if (!isJsonLike) return

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lifecycleScope.launch {
            val content = readTextFromUri(uri)
            if (content.isBlank()) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.profiles_invalid_json_msg,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val imported = parseImportedProfile(uri, content)
            if (imported == null) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.profiles_invalid_json_msg,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            lastHandledImportUri = uriToken
            val id = profileRepository.insertProfile(imported)
            profileRepository.setSelectedProfile(id)
            Toast.makeText(
                this@MainActivity,
                R.string.profiles_json_imported_msg,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.orEmpty()
    }

    private fun parseImportedProfile(uri: Uri, jsonContent: String): ProfileEntity? {
        return try {
            val root = gson.fromJson(jsonContent, JsonObject::class.java)

            val name = readDisplayName(uri) ?: "Imported Profile"
            val debugTiming = root.get("debug_timing")?.asBoolean ?: false
            val socksHost = root.get("socks_host")?.asString ?: "127.0.0.1"
            val socksPort = root.get("socks_port")?.asInt?.coerceIn(1, 65535) ?: 1080
            val socksUser = root.get("socks_user")?.asString ?: ""
            val socksPass = root.get("socks_pass")?.asString ?: ""
            val googleHost = root.get("google_host")?.asString ?: "216.239.38.120"
            val sniJson = parseSniJson(root.get("sni"))
            val scriptKeysText = parseScriptKeysJson(root.get("script_keys"))
            val tunnelKey = root.get("tunnel_key")?.asString ?: ""
            val coalesceStepMs = root.get("coalesce_step_ms")?.asInt ?: 0
            val idleSlotsPerBucket = root.get("idle_slots_per_bucket")?.asInt?.coerceIn(1, 3) ?: 2

            ProfileEntity(
                name = name,
                debugTiming = debugTiming,
                socksHost = socksHost,
                socksPort = socksPort,
                socksUser = socksUser,
                socksPass = socksPass,
                googleHost = googleHost,
                sniJson = sniJson,
                scriptKeysText = scriptKeysText,
                tunnelKey = tunnelKey,
                coalesceStepMs = coalesceStepMs,
                idleSlotsPerBucket = idleSlotsPerBucket
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSniJson(sniElement: com.google.gson.JsonElement?): String {
        if (sniElement == null || sniElement.isJsonNull) {
            return "[\"www.google.com\",\"mail.google.com\",\"accounts.google.com\"]"
        }
        return try {
            if (sniElement.isJsonArray) {
                val list = sniElement.asJsonArray.mapNotNull { it.asString?.trim() }.filter { it.isNotEmpty() }
                if (list.isEmpty()) {
                    "[\"www.google.com\",\"mail.google.com\",\"accounts.google.com\"]"
                } else {
                    gson.toJson(list)
                }
            } else if (sniElement.isJsonPrimitive) {
                val str = sniElement.asString
                if (str.isBlank()) {
                    "[\"www.google.com\",\"mail.google.com\",\"accounts.google.com\"]"
                } else {
                    val list = str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    gson.toJson(list)
                }
            } else {
                "[\"www.google.com\",\"mail.google.com\",\"accounts.google.com\"]"
            }
        } catch (_: Exception) {
            "[\"www.google.com\",\"mail.google.com\",\"accounts.google.com\"]"
        }
    }

    private fun parseScriptKeysJson(scriptKeysElement: com.google.gson.JsonElement?): String {
        if (scriptKeysElement == null || scriptKeysElement.isJsonNull) return ""
        return try {
            if (scriptKeysElement.isJsonArray) {
                scriptKeysElement.asJsonArray.mapNotNull { element ->
                    when {
                        element.isJsonObject -> {
                            val obj = element.asJsonObject
                            val id = obj.get("id")?.asString?.trim()
                            val account = obj.get("account")?.asString?.trim()
                            if (id.isNullOrBlank()) null
                            else if (account.isNullOrBlank()) id
                            else "$id|$account"
                        }
                        element.isJsonPrimitive -> element.asString.trim()
                        else -> null
                    }
                }.filter { it.isNotBlank() }.joinToString("\n")
            } else if (scriptKeysElement.isJsonPrimitive) {
                scriptKeysElement.asString
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun readDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0 || !cursor.moveToFirst()) return@use null
                cursor.getString(idx)
            }
        }.getOrNull()
            ?.substringBeforeLast(".")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}