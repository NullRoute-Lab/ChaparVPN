package com.gooserelay.gooserelayvpn.util

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.service.GooseRelayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Singleton bridge between Kotlin UI and Go core.
 * Manages VPN lifecycle and connection state.
 */
object VpnManager {

    enum class VpnState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
    }
    enum class LogSource {
        CORE, ANDROID
    }
    data class LogEntry(
        val line: String,
        val source: LogSource
    )

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _uploadSpeedBps = MutableStateFlow(0L)
    val uploadSpeedBps: StateFlow<Long> = _uploadSpeedBps.asStateFlow()
    private val _downloadSpeedBps = MutableStateFlow(0L)
    val downloadSpeedBps: StateFlow<Long> = _downloadSpeedBps.asStateFlow()

    data class ScanStatus(
        val statsActive: Int = 0,
        val statsSessionsOpen: Int = 0,
        val statsSessionsClose: Int = 0,
        val statsBytesOut: Long = 0,
        val statsBytesIn: Long = 0,
        val statsPollsOk: Int = 0,
        val statsPollsFail: Int = 0
    )

    private val _scanStatus = MutableStateFlow(ScanStatus())
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private val monitorScope = CoroutineScope(Dispatchers.Default)
    private var trafficMonitorJob: Job? = null

    private const val MAX_LOG_LINES = 2000

    fun updateState(newState: VpnState) {
        _state.value = newState
        if (newState == VpnState.CONNECTED) {
            _scanStatus.value = _scanStatus.value.copy(
                scanning = false
            )
        }
    }

    fun setError(message: String) {
        _errorMessage.value = message
        _state.value = VpnState.ERROR
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun appendLog(line: String) {
        appendLogInternal(line, LogSource.ANDROID)
    }

    fun appendCoreLog(line: String) {
        appendLogInternal(line, LogSource.CORE)
    }

    private fun appendLogInternal(line: String, source: LogSource) {
        val normalizedLine = normalizeLogTimestampToLocal(line)
        val current = _logEntries.value.toMutableList()
        current.add(LogEntry(normalizedLine, source))
        if (current.size > MAX_LOG_LINES) {
            current.removeAt(0)
        }
        _logEntries.value = current
        _logs.value = current.map { it.line }
        parseScanLine(normalizedLine)
    }

    fun clearLogs() {
        _logEntries.value = emptyList()
        _logs.value = emptyList()
        _scanStatus.value = ScanStatus()
    }

    fun startTrafficMonitor(context: Context) {
        val appContext = context.applicationContext
        val uid = appContext.applicationInfo.uid
        trafficMonitorJob?.cancel()
        trafficMonitorJob = monitorScope.launch {
            var prevTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            var prevRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            var prevTime = System.currentTimeMillis()
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                val dt = (now - prevTime).coerceAtLeast(1L)
                _uploadSpeedBps.value = ((tx - prevTx).coerceAtLeast(0L) * 1000L) / dt
                _downloadSpeedBps.value = ((rx - prevRx).coerceAtLeast(0L) * 1000L) / dt
                prevTx = tx
                prevRx = rx
                prevTime = now
            }
        }
    }

    fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        _uploadSpeedBps.value = 0L
        _downloadSpeedBps.value = 0L
    }

    /**
     * Start the VPN service.
     */
    fun connect(context: Context, profile: ProfileEntity) {
        if (_state.value == VpnState.CONNECTED || _state.value == VpnState.CONNECTING) return

        updateState(VpnState.CONNECTING)
        clearError()
        _scanStatus.value = ScanStatus(scanning = true)

        val intent = Intent(context, GooseRelayVpnService::class.java).apply {
            action = GooseRelayVpnService.ACTION_CONNECT
            putExtra(GooseRelayVpnService.EXTRA_PROFILE_ID, profile.id)
        }

        runCatching { context.startService(intent) }
            .onFailure {
                setError("Failed to start VPN service: ${it.message}")
                appendLog("Failed to start VPN service: ${it.message}")
                updateState(VpnState.ERROR)
            }
    }

    /**
     * Stop the VPN service.
     */
    fun disconnect(context: Context) {
        if (_state.value == VpnState.DISCONNECTED) return

        updateState(VpnState.DISCONNECTING)
        stopTrafficMonitor()

        val intent = Intent(context, GooseRelayVpnService::class.java).apply {
            action = GooseRelayVpnService.ACTION_DISCONNECT
        }
        runCatching { context.startService(intent) }
            .onFailure {
                setError("Failed to stop VPN service: ${it.message}")
                appendLog("Failed to stop VPN service: ${it.message}")
                updateState(VpnState.ERROR)
            }
    }

private fun parseScanLine(line: String) {
        // Parse STATS line
        val statsMatch = Regex(
            "active=(\\d+)\\s+sessions\\(open=(\\d+)\\s+close=(\\d+)\\)\\s+frames\\(out=(\\d+)\\s+in=(\\d+)\\)\\s+bytes\\(out=([0-9.]+)([KMG]?)\\s+in=([0-9.]+)([KMG]?)\\)\\s+polls\\(ok=(\\d+)\\s+fail=(\\d+)\\)",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (statsMatch != null) {
            val bytesOut = parseBytes(statsMatch.groupValues[4], statsMatch.groupValues[5])
            val bytesIn = parseBytes(statsMatch.groupValues[7], statsMatch.groupValues[8])
            _scanStatus.value = _scanStatus.value.copy(
                statsActive = statsMatch.groupValues[1].toIntOrNull() ?: 0,
                statsSessionsOpen = statsMatch.groupValues[2].toIntOrNull() ?: 0,
                statsSessionsClose = statsMatch.groupValues[3].toIntOrNull() ?: 0,
                statsBytesOut = bytesOut,
                statsBytesIn = bytesIn,
                statsPollsOk = statsMatch.groupValues[9].toIntOrNull() ?: 0,
                statsPollsFail = statsMatch.groupValues[10].toIntOrNull() ?: 0
            )
        }
    }
    }

    private fun parseBytes(value: String, unit: String): Long {
        val num = value.toDoubleOrNull() ?: return 0L
        return when (unit.uppercase()) {
            "G" -> (num * 1024 * 1024 * 1024).toLong()
            "M" -> (num * 1024 * 1024).toLong()
            "K" -> (num * 1024).toLong()
            else -> num.toLong()
        }
    }

    private fun normalizeLogTimestampToLocal(line: String): String {
        val candidates = listOf(
            // Example: 2026-04-05T10:20:30.123Z
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)(.*)$"),
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd HH:mm:ss.SSS"
            ),
            // Example: 2026-04-05T10:20:30Z
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)(.*)$"),
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss"
            ),
            // Example: 2026-04-05 10:20:30 UTC (check UTC variant first)
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+UTC(.*)$"),
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            ),
            // Example: 2026-04-05 10:20:30 (from mtu_logging.go, no UTC)
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s(.*)$"),
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            ),
            // Example: 2026/04/05 10:20:30 (from logger.go)
            Triple(
                Regex("^(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2})(.*)$"),
                "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss"
            )
        )

        for ((regex, inputFormat, outputFormat) in candidates) {
            val match = regex.find(line) ?: continue
            val utcStamp = match.groupValues[1]
            val suffix = match.groupValues[2]
            val localStamp = convertUtcToLocal(utcStamp, inputFormat, outputFormat) ?: continue
            return "$localStamp$suffix"
        }
        return line
    }

    private fun convertUtcToLocal(
        utcValue: String,
        inputPattern: String,
        outputPattern: String
    ): String? {
        return try {
            val input = SimpleDateFormat(inputPattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed: Date = input.parse(utcValue) ?: return null
            val output = SimpleDateFormat(outputPattern, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            output.format(parsed)
        } catch (_: Exception) {
            null
        }
    }
}
