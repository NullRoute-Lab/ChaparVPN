package com.gooserelay.gooserelayvpn.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val gson = Gson()
    private val profileIdArg: Long? = savedStateHandle.get<String>("profileId")?.toLongOrNull()

    val selectedProfile: StateFlow<ProfileEntity?> = (
        if (profileIdArg != null) profileRepository.getProfileByIdFlow(profileIdArg)
        else profileRepository.getSelectedProfileFlow()
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun exportConfigJson(profile: ProfileEntity): String = ConfigGenerator.exportProfileJson(profile)

    fun saveProfile(profile: ProfileEntity) {
        viewModelScope.launch { profileRepository.updateProfile(profile) }
    }

    fun importJsonToProfile(profile: ProfileEntity, json: String): ProfileEntity? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val sni = root.get("sni")
            val sniList = if (sni != null && sni.isJsonArray) {
                sni.asJsonArray.mapNotNull { it.asString?.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf("www.google.com", "mail.google.com", "accounts.google.com")
            }
            val keys = root.get("script_keys")
            val keyLines = if (keys != null && keys.isJsonArray) {
                keys.asJsonArray.mapNotNull { element ->
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
            } else profile.scriptKeysText
            val coalesceStepMs = root.get("coalesce_step_ms")?.asInt ?: 0
            val idleSlotsPerBucket = root.get("idle_slots_per_bucket")?.asInt?.coerceIn(1, 3) ?: 2

            profile.copy(
                debugTiming = root.get("debug_timing")?.asBoolean ?: profile.debugTiming,
                socksHost = root.get("socks_host")?.asString ?: profile.socksHost,
                socksPort = root.get("socks_port")?.asInt?.coerceIn(1, 65535) ?: profile.socksPort,
                socksUser = root.get("socks_user")?.asString ?: profile.socksUser,
                socksPass = root.get("socks_pass")?.asString ?: profile.socksPass,
                googleHost = root.get("google_host")?.asString ?: profile.googleHost,
                sniJson = gson.toJson(sniList),
                scriptKeysText = keyLines,
                tunnelKey = root.get("tunnel_key")?.asString ?: profile.tunnelKey,
                coalesceStepMs = coalesceStepMs,
                idleSlotsPerBucket = idleSlotsPerBucket
            )
        } catch (_: Exception) {
            null
        }
    }
}
