package com.gooserelay.gooserelayvpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gooserelay.gooserelayvpn.App
import com.gooserelay.gooserelayvpn.MainActivity
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.data.local.AppDatabase
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import com.gooserelay.gooserelayvpn.util.GlobalSettingsStore
import com.gooserelay.gooserelayvpn.util.VpnManager
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.coroutineContext

class GooseRelayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.gooserelay.gooserelayvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.gooserelay.gooserelayvpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val TAG = "GooseRelayVPN"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_SOCKS_PORT = 1080
        private const val SOCKS_STARTUP_TIMEOUT_MS = 30 * 60 * 1000L
        private const val SOCKS_POLL_INTERVAL_MS = 500L

        // Companion packages that browsers and apps rely on for network access.
        // These must be included alongside any user-selected app to ensure traffic
        // actually flows through the tunnel (e.g. Chrome uses WebView & GMS).
        private val BROWSER_COMPANION_PACKAGES = setOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.chrome",          // system Chrome on some OEMs
            "com.google.android.captiveportallogin"
        )

        // If at least one browser is selected for split tunneling, companion packages
        // are also allowed so selected browsers can fully function through VPN.
        private val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "com.UCMobile.intl",
            "com.kiwibrowser.browser"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var goClientJob: Job? = null
    private var httpProxyJob: Job? = null
    private var sharingSocksJob: Job? = null
    private var sharingSocksServer: java.net.ServerSocket? = null
    private var sharingHttpServer: java.net.ServerSocket? = null
    private var logTailJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var isStopping = false
    @Volatile
    private var socksAuthWarningShown = false
    @Volatile
    private var sessionBusyWarningShown = false
    @Volatile
    private var activeLocalSocksPort: Int = DEFAULT_SOCKS_PORT

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1)
                if (profileId > 0) {
                    startVpn(profileId)
                }
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn(profileId: Long) {
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            try {
                VpnManager.updateState(VpnManager.VpnState.CONNECTING)
                VpnManager.clearError()
                socksAuthWarningShown = false
                sessionBusyWarningShown = false

                // Show foreground notification
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_connecting)))
                acquireWakeLock()

                // Load profile from DB
                val db = AppDatabase.getInstance(this@GooseRelayVpnService)
                val profile = db.profileDao().getProfileById(profileId)
                    ?: throw IllegalStateException("Profile not found")
                val socksPort = profile.socksPort.takeIf { it in 1..65535 } ?: DEFAULT_SOCKS_PORT
                activeLocalSocksPort = socksPort
                val globalSettings = GlobalSettingsStore.load(this@GooseRelayVpnService)
                val proxyMode = globalSettings.connectionMode.equals("PROXY", ignoreCase = true)

                VpnManager.appendLog("Loading profile: ${profile.name}")

                // Check if Go core is still running from previous session
                ensureGoCoreStopped()

                ensureSocksPortAvailable(socksPort)

                // Generate config files
                val configDir = File(filesDir, "config")
                configDir.mkdirs()

                val configFile = File(configDir, "client_config.json")
                configFile.writeText(ConfigGenerator.generateConfig(profile))

                VpnManager.appendLog("Config written to: ${configFile.absolutePath}")
                VpnManager.appendLog("Starting Go core...")

                // Start Go client in background thread
                val logFile = File(cacheDir, "vpn.log")
                if (!logFile.exists()) {
                    logFile.createNewFile()
                } else {
                    logFile.writeText("")
                }

                logTailJob?.cancel()
                logTailJob = launch(Dispatchers.IO) {
                    tailLogFile(logFile)
                }

                goClientJob = launch(Dispatchers.IO) {
                    try {
                        // Call the Go mobile wrapper
                        mobile.Mobile.startClient(
                            configFile.absolutePath,
                            logFile.absolutePath
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Go core error", e)
                        VpnManager.appendLog("Go core error: ${e.message}")
                        withContext(Dispatchers.Main) {
                            VpnManager.setError("Go core error: ${e.message}")
                        }
                    }
                }

                // Wait until SOCKS5 is actually listening. MTU test/session init may take a few minutes.
                waitForSocksProxyReady(
                    host = "127.0.0.1",
                    port = socksPort,
                    timeoutMs = SOCKS_STARTUP_TIMEOUT_MS
                )
                VpnManager.appendLog("SOCKS5 proxy is ready on 127.0.0.1:$socksPort")

                // Start Internet Sharing proxies if enabled
                if (globalSettings.internetSharingEnabled) {
                    val socksPort = globalSettings.internetSharingSocksPort
                    val httpPort = globalSettings.internetSharingHttpPort
                    val user = globalSettings.internetSharingUser
                    val pass = globalSettings.internetSharingPass
                    startInternetSharing(socksPort, httpPort, user, pass)
                }

                if (proxyMode) {
                    VpnManager.appendLog("Proxy mode active: skipping Android VpnService TUN setup")
                    VpnManager.updateState(VpnManager.VpnState.CONNECTED)
                    VpnManager.startTrafficMonitor(this@GooseRelayVpnService)
                    val notification = buildNotification("Proxy mode active on port $socksPort")
                    val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                    return@launch
                }

                // GooseRelay core does not expose a local DNS mode. Always route DNS
                // through the VPN path using public resolvers.
                val vpnDnsServer = "8.8.8.8"

                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(vpnDnsServer)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val splitEnabled = globalSettings.splitTunnelingEnabled &&
                        globalSettings.splitPackagesCsv.isNotBlank()
                    if (splitEnabled) {
                        val userSelected = globalSettings.splitPackagesCsv
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        val browserSelected = userSelected.any { it in KNOWN_BROWSER_PACKAGES }
                        val installedCompanions = if (browserSelected) {
                            val pm = packageManager
                            BROWSER_COMPANION_PACKAGES.filter { pkg ->
                                runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
                            }.toSet()
                        } else {
                            emptySet()
                        }

                        // Do NOT include our own packageName here.
                        // tun2socks reads from the TUN fd directly (not via the route table),
                        // and Go core's outbound UDP sockets must bypass the TUN to reach
                        // relay endpoints directly — exactly like the non-split path which
                        // uses addDisallowedApplication(packageName).
                        val finalAllowed = userSelected + installedCompanions

                        VpnManager.appendLog(
                            "Split tunnel: ${userSelected.size} selected apps, " +
                            "${installedCompanions.size} companion packages added " +
                            "(browser selected: $browserSelected)"
                        )

                        finalAllowed.forEach { pkg ->
                            try {
                                builder.addAllowedApplication(pkg)
                            } catch (e: Exception) {
                                VpnManager.appendLog("Split tunnel skip '$pkg': ${e.message}")
                            }
                        }
                    } else {
                        // Exclude app itself by default to avoid self-loop traffic.
                        builder.addDisallowedApplication(packageName)
                    }
                }

                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("VPN interface could not be established. Check VPN permission.")

                VpnManager.appendLog("TUN interface established (fd=${vpnInterface!!.fd})")

                // Start Go-based tun2socks bridge
                VpnManager.appendLog("Starting tun2socks bridge: TUN fd -> socks5://127.0.0.1:$socksPort")
                mobile.Mobile.startTun(vpnInterface!!.fd.toLong(), "127.0.0.1:$socksPort")

                // Update state
                VpnManager.updateState(VpnManager.VpnState.CONNECTED)
                VpnManager.startTrafficMonitor(this@GooseRelayVpnService)
                VpnManager.appendLog("VPN connected successfully!")

                // Update notification
                val notification = buildNotification(getString(R.string.notification_connected))
                val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notification)

            } catch (e: CancellationException) {
                VpnManager.appendLog("Connection canceled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                VpnManager.appendLog("Error: ${e.message}")
                VpnManager.setError(e.message ?: "Unknown error")
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        if (isStopping) return
        isStopping = true
        
        VpnManager.updateState(VpnManager.VpnState.DISCONNECTING)
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                connectJob?.cancel()
                VpnManager.appendLog("Stopping VPN...")

                // Stop Go client and Tun bridge
                runCatching {
                    if (mobile.Mobile.isRunning()) {
                        mobile.Mobile.stopClient()
                    } else {
                        VpnManager.appendLog("Go core already stopped")
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Error stopping Go core", e)
                }

                // Close TUN interface
                runCatching { vpnInterface?.close() }
                vpnInterface = null

                // Cancel coroutines
                goClientJob?.cancel()
                httpProxyJob?.cancel()
                sharingSocksJob?.cancel()
                logTailJob?.cancel()

                // Close sharing servers
                runCatching { sharingSocksServer?.close() }
                sharingSocksServer = null
                runCatching { sharingHttpServer?.close() }
                sharingHttpServer = null

                VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
                VpnManager.stopTrafficMonitor()
                VpnManager.appendLog("VPN disconnected")
                releaseWakeLock()

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to stop foreground cleanly", it)
                }

                // Delay to allow UI to update before stopping service
                delay(500L)
                runCatching { stopSelf() }
            } catch (e: Exception) {
                Log.e(TAG, "Error in stopVpn", e)
            } finally {
                isStopping = false
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (!isStopping) {
            try {
                if (mobile.Mobile.isRunning()) {
                    mobile.Mobile.stopClient()
                }
            } catch (_: Exception) {
            }
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private suspend fun waitForSocksProxyReady(host: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()

            val clientJob = goClientJob
            if (clientJob != null && clientJob.isCompleted && !mobile.Mobile.isRunning()) {
                throw IllegalStateException("Go core stopped before SOCKS5 became ready")
            }

            if (canConnect(host, port)) {
                return
            }
            delay(SOCKS_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Timed out waiting for SOCKS5 listener on $host:$port")
    }

    private fun canConnect(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 300)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun ensureSocksPortAvailable(port: Int) {
        if (!isLocalPortInUse(port)) return
        VpnManager.appendLog("SOCKS5 port $port is busy, attempting to free it...")

        runCatching {
            if (mobile.Mobile.isRunning()) {
                mobile.Mobile.stopClient()
            }
        }

        repeat(5) {
            delay(400L)
            if (!isLocalPortInUse(port)) {
                VpnManager.appendLog("SOCKS5 port $port released successfully")
                return
            }
            VpnManager.appendLog("SOCKS5 port $port still busy, retrying...")
        }

        throw IllegalStateException("SOCKS5 port $port is already in use. Change LISTEN_PORT or close the app using it.")
    }

    private suspend fun ensureGoCoreStopped() {
        if (!mobile.Mobile.isRunning()) return
        VpnManager.appendLog("Go core is still running, stopping it first...")
        runCatching { mobile.Mobile.stopClient() }
        
        repeat(5) {
            delay(300L)
            if (!mobile.Mobile.isRunning()) {
                VpnManager.appendLog("Go core stopped successfully")
                return
            }
        }
        VpnManager.appendLog("Warning: Go core may still be running")
    }

    private fun isLocalPortInUse(port: Int): Boolean {
        return runCatching {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
            false
        }.getOrElse { true }
    }

    private suspend fun tailLogFile(logFile: File) {
        // Continuously mirrors Go log file into Compose logs so Android UI shows real MTU/session progress.
        RandomAccessFile(logFile, "r").use { raf ->
            var pointer = 0L
            while (coroutineContext.isActive) {
                val length = raf.length()
                if (length < pointer) {
                    pointer = 0L
                }

                if (length > pointer) {
                    raf.seek(pointer)
                    while (true) {
                        val line = raf.readLine() ?: break
                        if (line.isNotBlank()) {
                            VpnManager.appendCoreLog(line)
                            maybeReportSocksAuthIssue(line)
                            maybeReportSessionBusyIssue(line)
                        }
                    }
                    pointer = raf.filePointer
                }

                delay(250L)
            }
        }
    }

    private fun maybeReportSocksAuthIssue(line: String) {
        if (socksAuthWarningShown) return
        val normalized = line.uppercase()
        val authRelatedFailure = normalized.contains("SOCKS5_AUTH_FAILED") ||
            (normalized.contains("SOCKS5") &&
                normalized.contains("AUTH") &&
                normalized.contains("FAIL"))
        if (!authRelatedFailure) return

        socksAuthWarningShown = true
        val message = "SOCKS5 authentication failed. Check SOCKS5_AUTH, SOCKS5_USER, and SOCKS5_PASS in profile settings."
        VpnManager.appendLog(message)
        VpnManager.setError(message)
    }

    private fun maybeReportSessionBusyIssue(line: String) {
        if (sessionBusyWarningShown) return
        val normalized = line.uppercase()
        val isSessionBusy = normalized.contains("SESSION RESTART REQUESTED: SESSION BUSY RECEIVED")
        if (!isSessionBusy) return

        sessionBusyWarningShown = true
        val message = "Server is busy and cannot accept new sessions at the moment."
        VpnManager.appendLog(message)
        VpnManager.setError(message)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:runtime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun startInternetSharing(socksPort: Int, httpPort: Int, username: String, password: String) {
        // Ensure ports are available before starting
        if (isLocalPortInUse(socksPort) || isLocalPortInUse(httpPort)) {
            VpnManager.appendLog("Sharing ports in use, attempting to free...")
            if (mobile.Mobile.isRunning()) {
                runCatching { mobile.Mobile.stopClient() }
            }
            kotlinx.coroutines.delay(500L)
        }

        sharingSocksJob?.cancel()
        sharingSocksServer?.close()
        sharingSocksServer = null

        sharingSocksJob = serviceScope.launch {
            try {
                val server = java.net.ServerSocket(socksPort, 50, InetAddress.getByName("0.0.0.0"))
                server.reuseAddress = true
                sharingSocksServer = server
                VpnManager.appendLog("Sharing SOCKS5 proxy ready on 0.0.0.0:$socksPort")
                while (isActive) {
                    val client = server.accept() ?: continue
                    launch(Dispatchers.IO) {
                        handleSharingSocksClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sharing SOCKS5 proxy error", e)
                VpnManager.appendLog("Sharing SOCKS5 proxy error: ${e.message}")
            }
        }

        httpProxyJob?.cancel()
        sharingHttpServer?.close()
        sharingHttpServer = null

        httpProxyJob = serviceScope.launch {
            try {
                val server = java.net.ServerSocket(httpPort, 50, InetAddress.getByName("0.0.0.0"))
                server.reuseAddress = true
                sharingHttpServer = server
                VpnManager.appendLog("HTTP proxy ready on 0.0.0.0:$httpPort")
                while (isActive) {
                    val client = server.accept() ?: continue
                    launch(Dispatchers.IO) {
                        handleHttpProxyClient(client, socksPort, username, password)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP proxy error", e)
                VpnManager.appendLog("HTTP proxy error: ${e.message}")
            }
        }
    }

    private fun handleSharingSocksClient(client: java.net.Socket) {
        var upstream: java.net.Socket? = null
        try {
            upstream = java.net.Socket("127.0.0.1", activeLocalSocksPort)
            upstream.soTimeout = 30000
            bridgeBidirectional(client, upstream)
        } catch (_: Exception) {
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

private suspend fun handleHttpProxyClient(client: java.net.Socket, upstreamSocksPort: Int, username: String, password: String) {
        try {
            val input = client.getInputStream().bufferedReader()
            val output = client.getOutputStream().bufferedWriter()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }

            val method = parts[0]
            val url = parts[1]

            var authHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                    authHeader = value
                }
            }

            val requiresAuth = username.isNotBlank() || password.isNotBlank()
            if (requiresAuth && !isValidBasicProxyAuth(authHeader, username, password)) {
                output.write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"GooseRelayVPN\"\r\n" +
                        "Connection: close\r\n\r\n"
                )
                output.flush()
                return
            }

            if (method == "CONNECT") {
                val hostPort = url.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrElse(1) { "80" }.toIntOrNull() ?: 80

                output.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                output.flush()

                val upstream = createSocks5Tunnel(upstreamSocksPort, host, port)
                upstream.soTimeout = 30000

                bridgeBidirectional(client, upstream)
            } else {
                output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                output.flush()
            }
} catch (_: Exception) {}
        runCatching { client.close() }
    }

    private fun bridgeBidirectional(client: java.net.Socket, upstream: java.net.Socket) {
        val upToClient = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                val input = upstream.getInputStream()
                val output = client.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { client.shutdownOutput() }
            }
        }

        val clientToUp = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                val input = client.getInputStream()
                val output = upstream.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { upstream.shutdownOutput() }
            }
        }

        runBlocking {
            joinAll(upToClient, clientToUp)
        }
        runCatching { upstream.close() }
        runCatching { client.close() }
    }

    private fun createSocks5Tunnel(socksPort: Int, targetHost: String, targetPort: Int): java.net.Socket {
        val socket = java.net.Socket("127.0.0.1", socksPort)
        socket.soTimeout = 15000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = ByteArray(2)
        readFully(input, greeting, 0, greeting.size)
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 upstream greeting failed")
        }

        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) {
            throw IllegalArgumentException("Target host is too long")
        }
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val header = ByteArray(4)
        readFully(input, header, 0, header.size)
        if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 connect failed with code ${header[1].toInt() and 0xFF}")
        }

        val addrLen = when (header[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> {
                val size = input.read()
                if (size < 0) throw IllegalStateException("SOCKS5 malformed bind address length")
                size
            }
            0x04 -> 16
            else -> throw IllegalStateException("SOCKS5 unsupported bind address type")
        }
        val skip = ByteArray(addrLen + 2)
        readFully(input, skip, 0, skip.size)
        return socket
    }

    private fun isValidBasicProxyAuth(header: String?, username: String, password: String): Boolean {
        if (username.isBlank() && password.isBlank()) return true
        val value = header?.trim().orEmpty()
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val encoded = value.substringAfter(" ", "").trim()
        if (encoded.isBlank()) return false
        val decoded = runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return false
        return decoded == "$username:$password"
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read < 0) throw IllegalStateException("Unexpected EOF while reading SOCKS5 response")
            total += read
        }
    }
}
