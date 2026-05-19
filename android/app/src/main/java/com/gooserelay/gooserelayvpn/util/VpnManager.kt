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
        val statsBytesOut: String = "",
        val statsBytesIn: String = "",
        val statsPollsOk: Int = 0,
        val statsPollsFail: Int = 0,
        val accountStats: String = "",
        val hasStats: Boolean = false
    )

    private val _scanStatus = MutableStateFlow(ScanStatus())
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private val monitorScope = CoroutineScope(Dispatchers.Default)
    private var trafficMonitorJob: Job? = null

    private const val MAX_LOG_LINES = 2000
    private val stampRegex = Regex("^\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")

    fun updateState(newState: VpnState) {
        _state.value = newState
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
        var normalizedLine = normalizeLogTimestampToLocal(line)
        if (!stampRegex.containsMatchIn(normalizedLine)) {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
            val now = dateFormat.format(Date())
            normalizedLine = "$now $normalizedLine"
        }
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
        _scanStatus.value = ScanStatus()

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
        if (_state.value == VpnState.DISCONNECTED || _state.value == VpnState.DISCONNECTING) return

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
        // New format: [stats] active=8 sessions=70/62 frames=259/365 bytes=13.8MB/883.5KB polls=404/0 rst=1 endpoints=3/3
        val active = Regex("active=(\\d+)", RegexOption.IGNORE_CASE).find(line)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        val sessions = Regex("sessions=(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).find(line)
        val sessionsOpen = sessions?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val sessionsClose = sessions?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        val bytes = Regex("bytes=([^/\\s]+)/([^\\s]+)", RegexOption.IGNORE_CASE).find(line)
        val bytesOut = bytes?.groupValues?.getOrNull(1) ?: ""
        val bytesIn = bytes?.groupValues?.getOrNull(2) ?: ""

        val polls = Regex("polls=(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).find(line)
        val pollsOk = polls?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val pollsFail = polls?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        // Accounts format: accounts=[air today=259 script=514 | mr4 today=258 script=506 | mr6 today=258 script=515]
        val accounts = Regex("accounts=\\[(.+)\\]", RegexOption.IGNORE_CASE).find(line)
        val accountStats = accounts?.groupValues?.getOrNull(1)?.trim() ?: ""

        if ((active != null && (bytesOut.isNotBlank() || sessionsOpen > 0)) || (accounts != null && accountStats.isNotBlank())) {
            _scanStatus.value = _scanStatus.value.copy(
                statsActive = active ?: _scanStatus.value.statsActive,
                statsSessionsOpen = if (sessions != null) sessionsOpen else _scanStatus.value.statsSessionsOpen,
                statsSessionsClose = if (sessions != null) sessionsClose else _scanStatus.value.statsSessionsClose,
                statsBytesOut = if (bytes != null) bytesOut else _scanStatus.value.statsBytesOut,
                statsBytesIn = if (bytes != null) bytesIn else _scanStatus.value.statsBytesIn,
                statsPollsOk = if (polls != null) pollsOk else _scanStatus.value.statsPollsOk,
                statsPollsFail = if (polls != null) pollsFail else _scanStatus.value.statsPollsFail,
                accountStats = if (accounts != null) accountStats else _scanStatus.value.accountStats,
                hasStats = true
            )
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
