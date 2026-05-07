package com.gooserelay.gooserelayvpn.ui.profiles

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.gooserelay.gooserelayvpn.ui.theme.ConnectedGreen
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvSpace
import com.gooserelay.gooserelayvpn.ui.profiles.ProfilesViewModel
import com.gooserelay.gooserelayvpn.util.ConfigGenerator

data class ScriptKeyEntry(
    val id: String = "",
    val account: String = ""
)

fun parseScriptKeysText(text: String): List<ScriptKeyEntry> {
    Log.d("ProfilesScreen", "parseScriptKeysText input: '$text'")
    val result = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            if (line.contains("|")) {
                val parts = line.split("|")
                val entry = ScriptKeyEntry(
                    id = parts.getOrElse(0) { "" }.trim(),
                    account = parts.getOrElse(1) { "" }.trim()
                )
                Log.d("ProfilesScreen", "Parsed pipe: id='${entry.id}', account='${entry.account}'")
                entry
            } else {
                Log.d("ProfilesScreen", "Parsed no-pipe: id='${line.trim()}'")
                ScriptKeyEntry(id = line.trim(), account = "")
            }
        }
    Log.d("ProfilesScreen", "parseScriptKeysText result: $result")
    return result
}

fun scriptKeysToText(entries: List<ScriptKeyEntry>): String {
    Log.d("ProfilesScreen", "scriptKeysToText input: ${entries.size} entries")
    entries.forEachIndexed { i, entry ->
        Log.d("ProfilesScreen", "  [$i] id='${entry.id}', account='${entry.account}'")
    }
    val result = entries
        .filter { it.id.isNotBlank() }
        .joinToString("\n") { entry ->
            if (entry.account.isNotBlank()) "${entry.id}|${entry.account}" else entry.id
        }
    Log.d("ProfilesScreen", "scriptKeysToText output: '$result'")
    return result
}

@Composable
fun ProfilesScreen(
    onBack: () -> Unit
) {
    val viewModel: ProfilesViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsState()
    val context = LocalContext.current
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }

    Scaffold(
        topBar = {
            MdvBackTopAppBar(
                title = "Profiles",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { editing = null; showEditor = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                    }
                }
            )
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = null,
                        tint = MdvColor.OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(MdvSpace.S3))
                    Text("No profiles yet")
                    Spacer(modifier = Modifier.height(MdvSpace.S2))
                    Button(onClick = { editing = null; showEditor = true }) { Text("Create Profile") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles) { profile ->
                    Card(
                        onClick = { viewModel.selectProfile(profile.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.isSelected)
                                MdvColor.PrimaryContainer.copy(alpha = 0.16f)
                            else
                                MdvColor.SurfaceHigh
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (profile.isSelected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = ConnectedGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name)
                                Text("${profile.socksHost}:${profile.socksPort}")
                            }

                            IconButton(onClick = { editing = profile; showEditor = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { viewModel.deleteProfile(profile) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        ProfileEditorDialog(
            context = context,
            profile = editing,
            onSave = {
                if (editing == null) viewModel.addProfile(it) else viewModel.updateProfile(it)
                showEditor = false
                editing = null
            },
            onDismiss = { showEditor = false; editing = null }
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    context: Context,
    profile: ProfileEntity?,
    onSave: (ProfileEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(profile?.name ?: "Default") }
    var debugTiming by remember { mutableStateOf(profile?.debugTiming ?: false) }
    var socksHost by remember { mutableStateOf(profile?.socksHost ?: "127.0.0.1") }
    var socksPort by remember { mutableStateOf((profile?.socksPort ?: 1080).toString()) }
    var socksUser by remember { mutableStateOf(profile?.socksUser ?: "") }
    var socksPass by remember { mutableStateOf(profile?.socksPass ?: "") }
    var googleHost by remember { mutableStateOf(profile?.googleHost ?: "216.239.38.120") }
    var sniCsv by remember { mutableStateOf(profile?.sniJson?.replace("[", "")?.replace("]", "")?.replace("\"", "") ?: "www.google.com, mail.google.com, accounts.google.com") }
    var scriptKeysText by remember { mutableStateOf(profile?.scriptKeysText ?: "") }
    var scriptKeyEntries by remember { mutableStateOf(parseScriptKeysText(profile?.scriptKeysText ?: "")) }
    var tunnelKey by remember { mutableStateOf(profile?.tunnelKey ?: "") }
    var coalesceStepMs by remember { mutableStateOf((profile?.coalesceStepMs ?: 0).toString()) }
    var idleSlotsPerBucket by remember { mutableStateOf((profile?.idleSlotsPerBucket ?: 1).toString()) }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }

    Log.d("ProfilesScreen", "=== DIALOG INIT === profile=${profile?.name ?: "NEW"}")
    Log.d("ProfilesScreen", "Loaded scriptKeysText: '${profile?.scriptKeysText ?: "(empty)"}'")
    Log.d("ProfilesScreen", "Parsed scriptKeyEntries (${scriptKeyEntries.size}): $scriptKeyEntries")

    LaunchedEffect(scriptKeyEntries) {
        Log.d("ProfilesScreen", "*** scriptKeyEntries CHANGED (${scriptKeyEntries.size} items) ***")
        scriptKeyEntries.forEachIndexed { i, entry ->
            Log.d("ProfilesScreen", "  [$i] id='${entry.id}', account='${entry.account}'")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val raw = readTextFromUri(context, uri)
            val root = Gson().fromJson(raw, JsonObject::class.java)
            name = root.get("name")?.asString ?: name
            debugTiming = root.get("debug_timing")?.asBoolean ?: debugTiming
            socksHost = root.get("socks_host")?.asString ?: socksHost
            socksPort = root.get("socks_port")?.asInt?.toString() ?: socksPort
            socksUser = root.get("socks_user")?.asString ?: socksUser
            socksPass = root.get("socks_pass")?.asString ?: socksPass
            if ((socksUser.isBlank()) != (socksPass.isBlank())) {
                showErrorDialog = "Import rejected: socks_user and socks_pass must both be set or both be empty (SOCKS5 auth requires both values)."
                return@runCatching
            }
            showErrorDialog = null
            googleHost = root.get("google_host")?.asString ?: googleHost
            sniCsv = when {
                root.get("sni")?.isJsonArray == true -> root.getAsJsonArray("sni")?.mapNotNull { it.asString }?.joinToString(", ") ?: ""
                root.get("sni")?.isJsonPrimitive == true -> root.get("sni")?.asString ?: ""
                else -> ""
            }
            val keys = when {
                root.get("script_keys")?.isJsonArray == true -> {
                    Log.d("ProfilesScreen", "=== IMPORTING SCRIPT KEYS FROM JSON ===")
                    root.getAsJsonArray("script_keys")?.mapNotNull { element ->
                        when {
                            element.isJsonObject -> {
                                val obj = element.asJsonObject
                                val id = obj.get("id")?.asString?.trim()
                                val account = obj.get("account")?.asString?.trim()
                                Log.d("ProfilesScreen", "Import JSON obj: id='$id', account='$account'")
                                if (id.isNullOrBlank()) null
                                else if (account.isNullOrBlank()) {
                                    Log.d("ProfilesScreen", "  -> No account, saving as: '$id'")
                                    id
                                }
                                else {
                                    val result = "$id|$account"
                                    Log.d("ProfilesScreen", "  -> With account, saving as: '$result'")
                                    result
                                }
                            }
                            element.isJsonPrimitive -> element.asString.trim()
                            else -> null
                        }
                    }?.filter { it.isNotBlank() }?.joinToString("\n") ?: ""
                }
                else -> ""
            }
            Log.d("ProfilesScreen", "Final imported keys text: '$keys'")
            scriptKeysText = keys
            Log.d("ProfilesScreen", "About to parse imported keys with parseScriptKeysText()")
            scriptKeyEntries = parseScriptKeysText(keys)
            Log.d("ProfilesScreen", "After parsing, scriptKeyEntries has ${scriptKeyEntries.size} items")
            coalesceStepMs = (root.get("coalesce_step_ms")?.asInt ?: 0).toString()
            idleSlotsPerBucket = (root.get("idle_slots_per_bucket")?.asInt?.coerceIn(1, 3) ?: 1).toString()
            tunnelKey = root.get("tunnel_key")?.asString ?: tunnelKey
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val draft = ProfileEntity(
            id = profile?.id ?: 0,
            name = name,
            debugTiming = debugTiming,
            socksHost = socksHost,
            socksPort = socksPort.toIntOrNull()?.coerceIn(1, 65535) ?: 1080,
            socksUser = socksUser,
            socksPass = socksPass,
            googleHost = googleHost,
            sniJson = if (sniCsv.isBlank()) "[]" else sniCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\""),
            scriptKeysText = scriptKeysToText(scriptKeyEntries),
            tunnelKey = tunnelKey,
            coalesceStepMs = coalesceStepMs.toIntOrNull() ?: 0,
            idleSlotsPerBucket = idleSlotsPerBucket.toIntOrNull()?.coerceIn(1, 3) ?: 1,
            isSelected = profile?.isSelected ?: false,
            createdAt = profile?.createdAt ?: System.currentTimeMillis()
        )
        writeTextToUri(context, uri, ConfigGenerator.exportProfileJson(draft))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val error = when {
                    scriptKeyEntries.none { it.id.isNotBlank() } -> "At least one script key is required"
                    tunnelKey.isBlank() -> "Tunnel key is required"
                    (socksUser.isBlank()) != (socksPass.isBlank()) -> "socks_user and socks_pass must both be set or both be empty"
                    else -> null
                }
                if (error != null) {
                    showErrorDialog = error
                    return@Button
                }
                val sniJson = sniCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    .joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                Log.d("ProfilesScreen", "=== SAVING PROFILE ===")
                Log.d("ProfilesScreen", "scriptKeyEntries state (${scriptKeyEntries.size} entries):")
                scriptKeyEntries.forEachIndexed { i, entry ->
                    Log.d("ProfilesScreen", "  [$i] id='${entry.id}', account='${entry.account}'")
                }
                val scriptKeysForSave = scriptKeysToText(scriptKeyEntries)
                Log.d("ProfilesScreen", "Final scriptKeysText to save: '$scriptKeysForSave'")
                onSave(
                    ProfileEntity(
                        id = profile?.id ?: 0,
                        name = name,
                        debugTiming = debugTiming,
                        socksHost = socksHost,
                        socksPort = socksPort.toIntOrNull()?.coerceIn(1, 65535) ?: 1080,
                        socksUser = socksUser,
                        socksPass = socksPass,
                        googleHost = googleHost,
                        sniJson = sniJson,
                        scriptKeysText = scriptKeysForSave,
                        tunnelKey = tunnelKey,
                        coalesceStepMs = coalesceStepMs.toIntOrNull() ?: 0,
                        idleSlotsPerBucket = idleSlotsPerBucket.toIntOrNull()?.coerceIn(1, 3) ?: 1,
                        isSelected = profile?.isSelected ?: false,
                        createdAt = profile?.createdAt ?: System.currentTimeMillis()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (profile == null) "New Profile" else "Edit Profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Import JSON") }
                    OutlinedButton(onClick = { exportLauncher.launch("goose_profile.json") }, modifier = Modifier.weight(1f)) { Text("Export JSON") }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile Name") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Debug Timing")
                    Switch(checked = debugTiming, onCheckedChange = { debugTiming = it })
                }
                OutlinedTextField(value = socksHost, onValueChange = { socksHost = it }, label = { Text("socks_host") })
                OutlinedTextField(value = socksPort, onValueChange = { socksPort = it.filter(Char::isDigit) }, label = { Text("socks_port") })
                OutlinedTextField(value = socksUser, onValueChange = { socksUser = it }, label = { Text("socks_user") })
                OutlinedTextField(value = socksPass, onValueChange = { socksPass = it }, label = { Text("socks_pass") })
                OutlinedTextField(value = googleHost, onValueChange = { googleHost = it }, label = { Text("google_host") })
                OutlinedTextField(value = sniCsv, onValueChange = { sniCsv = it }, label = { Text("sni (comma separated)") })
                ScriptKeysEditor(
                    entries = scriptKeyEntries,
                    onEntriesChanged = { scriptKeyEntries = it }
                )
                OutlinedTextField(value = tunnelKey, onValueChange = { tunnelKey = it }, label = { Text("tunnel_key") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = coalesceStepMs, onValueChange = { coalesceStepMs = it.filter(Char::isDigit) }, label = { Text("coalesce_step_ms (0-40)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = idleSlotsPerBucket, onValueChange = { idleSlotsPerBucket = it.filter(Char::isDigit) }, label = { Text("idle_slots (1-3)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    )

    val dialogError = showErrorDialog
    if (dialogError != null) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text("OK")
                }
            },
            title = { Text("Error") },
            text = { Text(dialogError) }
        )
    }
}

@Composable
private fun ScriptKeysEditor(
    entries: List<ScriptKeyEntry>,
    onEntriesChanged: (List<ScriptKeyEntry>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "script_keys: For per-account parallelism, add Deployment ID and account name (e.g., id|account)",
            style = MaterialTheme.typography.bodySmall,
            color = MdvColor.OnSurfaceVariant
        )

        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = entry.id,
                    onValueChange = { newId ->
                        val newEntries = entries.toMutableList()
                        newEntries[index] = entry.copy(id = newId)
                        onEntriesChanged(newEntries)
                    },
                    label = { Text("Deployment ID") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = entry.account,
                    onValueChange = { newAccount ->
                        val newEntries = entries.toMutableList()
                        newEntries[index] = entry.copy(account = newAccount)
                        onEntriesChanged(newEntries)
                    },
                    label = { Text("Account") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        val newEntries = entries.toMutableList()
                        newEntries.removeAt(index)
                        onEntriesChanged(newEntries)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MdvColor.Error
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                val newEntries = entries.toMutableList()
                newEntries.add(ScriptKeyEntry())
                onEntriesChanged(newEntries)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add script key")
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
}

private fun writeTextToUri(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
}
