# Fake DNS Implementation for GooseRelayVPN (Android-only)

## Overview

This document explains how to implement fake DNS resolution in GooseRelayVPN Android app without modifying the Go core code. The approach intercepts DNS packets at the TUN interface level and maps them to fake IPs, then resolves the real hostnames at the server.

## Architecture Comparison

### MasterDnsVPN Approach (Complex - Requires Go Changes)
```
Android TUN → gVisor netstack → Intercept UDP:53 → ProcessDNSQuery (Go) → DNS Tunnel
```
- Requires extensive Go code changes
- Uses gVisor netstack (heavy dependency)
- Direct in-process DNS handling

### Simplified Approach (Android-only - No Go Changes)
```
Android TUN → Fake DNS Server (Android) → Return Fake IP → Map Fake IP → Real Hostname → SOCKS5 Proxy
```
- Only Android Kotlin code changes
- Lightweight implementation
- Works with existing SOCKS5 proxy

## Implementation Steps

### Step 1: Create Fake DNS Server (Android)

Create a fake DNS server that runs on the TUN interface and returns fake IPs from a reserved range (198.18.0.0/16).

**File: `android/app/src/main/java/com/gooserelay/gooserelayvpn/dns/FakeDnsServer.kt`**

```kotlin
package com.gooserelay.gooserelayvpn.dns

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeDnsServer(private val listenAddress: String = "10.0.0.1", private val listenPort: Int = 53) {
    
    private var socket: DatagramSocket? = null
    private var running = false
    private val fakeIpCounter = AtomicInteger(1)
    private val hostnameToFakeIp = ConcurrentHashMap<String, String>()
    private val fakeIpToHostname = ConcurrentHashMap<String, String>()
    
    companion object {
        private const val TAG = "FakeDnsServer"
        private const val FAKE_IP_PREFIX = "198.18"
        private const val MAX_FAKE_IPS = 65535
    }
    
    fun start() {
        if (running) return
        running = true
        
        Thread {
            try {
                socket = DatagramSocket(listenPort, InetAddress.getByName(listenAddress))
                Log.i(TAG, "Fake DNS server started on $listenAddress:$listenPort")
                
                val buffer = ByteArray(512)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val query = buffer.copyOf(packet.length)
                    val response = processQuery(query)
                    
                    if (response != null) {
                        val responsePacket = DatagramPacket(
                            response, response.size,
                            packet.address, packet.port
                        )
                        socket?.send(responsePacket)
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Fake DNS server error", e)
                }
            }
        }.start()
    }
    
    fun stop() {
        running = false
        socket?.close()
        socket = null
    }
    
    private fun processQuery(query: ByteArray): ByteArray? {
        try {
            // Parse DNS query
            val hostname = parseDnsQuery(query) ?: return null
            
            // Generate or retrieve fake IP
            val fakeIp = getFakeIpForHostname(hostname)
            
            Log.d(TAG, "DNS query: $hostname -> $fakeIp")
            
            // Build DNS response
            return buildDnsResponse(query, fakeIp)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing DNS query", e)
            return null
        }
    }
    
    private fun parseDnsQuery(query: ByteArray): String? {
        if (query.size < 12) return null
        
        var pos = 12 // Skip DNS header
        val labels = mutableListOf<String>()
        
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) break
            if (len > 63) return null // Invalid label length
            
            pos++
            if (pos + len > query.size) return null
            
            val label = String(query, pos, len, Charsets.UTF_8)
            labels.add(label)
            pos += len
        }
        
        return if (labels.isNotEmpty()) labels.joinToString(".") else null
    }
    
    private fun getFakeIpForHostname(hostname: String): String {
        return hostnameToFakeIp.getOrPut(hostname) {
            val counter = fakeIpCounter.getAndIncrement()
            if (counter > MAX_FAKE_IPS) {
                // Wrap around if we exceed the range
                fakeIpCounter.set(1)
            }
            val octet3 = (counter shr 8) and 0xFF
            val octet4 = counter and 0xFF
            val fakeIp = "$FAKE_IP_PREFIX.$octet3.$octet4"
            fakeIpToHostname[fakeIp] = hostname
            fakeIp
        }
    }
    
    fun getHostnameForFakeIp(fakeIp: String): String? {
        return fakeIpToHostname[fakeIp]
    }
    
    private fun buildDnsResponse(query: ByteArray, fakeIp: String): ByteArray {
        val response = ByteBuffer.allocate(512)
        
        // Copy query
        response.put(query)
        response.position(0)
        
        // Modify flags: QR=1 (response), AA=1 (authoritative)
        val flags = response.getShort(2).toInt()
        response.putShort(2, ((flags or 0x8400) and 0xFFFF).toShort())
        
        // Set answer count to 1
        response.putShort(6, 1)
        
        // Position at end of query
        response.position(query.size)
        
        // Add answer section
        // Name pointer to question (0xC00C)
        response.putShort(0xC00C.toShort())
        
        // Type A (1), Class IN (1)
        response.putShort(1)
        response.putShort(1)
        
        // TTL (60 seconds)
        response.putInt(60)
        
        // Data length (4 bytes for IPv4)
        response.putShort(4)
        
        // IP address
        val ipParts = fakeIp.split(".")
        ipParts.forEach { response.put(it.toInt().toByte()) }
        
        val result = ByteArray(response.position())
        response.position(0)
        response.get(result)
        return result
    }
}
```

### Step 2: Modify SOCKS5 Connection Handler

Intercept SOCKS5 CONNECT requests and replace fake IPs with real hostnames.

**File: `android/app/src/main/java/com/gooserelay/gooserelayvpn/dns/FakeDnsSocksProxy.kt`**

```kotlin
package com.gooserelay.gooserelayvpn.dns

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class FakeDnsSocksProxy(
    private val listenPort: Int,
    private val upstreamSocksHost: String,
    private val upstreamSocksPort: Int,
    private val fakeDnsServer: FakeDnsServer
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    
    companion object {
        private const val TAG = "FakeDnsSocksProxy"
    }
    
    fun start() {
        if (running) return
        running = true
        
        Thread {
            try {
                serverSocket = ServerSocket(listenPort)
                Log.i(TAG, "Fake DNS SOCKS proxy started on port $listenPort")
                
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Fake DNS SOCKS proxy error", e)
                }
            }
        }.start()
    }
    
    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
    }
    
    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            
            // Read SOCKS5 greeting
            val version = input.read()
            if (version != 5) {
                client.close()
                return
            }
            
            val nmethods = input.read()
            val methods = ByteArray(nmethods)
            input.read(methods)
            
            // Send "no authentication required"
            output.write(byteArrayOf(5, 0))
            
            // Read SOCKS5 request
            val requestHeader = ByteArray(4)
            input.read(requestHeader)
            
            if (requestHeader[1].toInt() != 1) { // Only support CONNECT
                client.close()
                return
            }
            
            val addressType = requestHeader[3].toInt()
            val targetHost: String
            val targetPort: Int
            
            when (addressType) {
                1 -> { // IPv4
                    val addr = ByteArray(4)
                    input.read(addr)
                    val fakeIp = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    
                    // Check if this is a fake IP
                    targetHost = fakeDnsServer.getHostnameForFakeIp(fakeIp) ?: fakeIp
                    
                    val portBytes = ByteArray(2)
                    input.read(portBytes)
                    targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    
                    Log.d(TAG, "SOCKS5 CONNECT: $fakeIp -> $targetHost:$targetPort")
                }
                3 -> { // Domain name
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    targetHost = String(domain, Charsets.UTF_8)
                    
                    val portBytes = ByteArray(2)
                    input.read(portBytes)
                    targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                }
                else -> {
                    client.close()
                    return
                }
            }
            
            // Connect to upstream SOCKS5 proxy with real hostname
            val upstream = Socket(upstreamSocksHost, upstreamSocksPort)
            val upInput = upstream.getInputStream()
            val upOutput = upstream.getOutputStream()
            
            // SOCKS5 handshake with upstream
            upOutput.write(byteArrayOf(5, 1, 0))
            upInput.read(ByteArray(2))
            
            // Send CONNECT request with real hostname
            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            val request = ByteArray(7 + hostBytes.size)
            request[0] = 5 // Version
            request[1] = 1 // CONNECT
            request[2] = 0 // Reserved
            request[3] = 3 // Domain name
            request[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
            request[5 + hostBytes.size] = (targetPort shr 8).toByte()
            request[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            
            upOutput.write(request)
            
            // Read upstream response
            val upResponse = ByteArray(10)
            upInput.read(upResponse, 0, 4)
            val upAddrType = upResponse[3].toInt()
            when (upAddrType) {
                1 -> upInput.read(upResponse, 4, 6) // IPv4 + port
                3 -> {
                    val len = upInput.read()
                    upInput.read(ByteArray(len + 2))
                }
                4 -> upInput.read(ByteArray(18)) // IPv6 + port
            }
            
            // Send success response to client
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            
            // Relay data
            val clientToUpstream = Thread { relay(input, upOutput) }
            val upstreamToClient = Thread { relay(upInput, output) }
            
            clientToUpstream.start()
            upstreamToClient.start()
            
            clientToUpstream.join()
            upstreamToClient.join()
            
            client.close()
            upstream.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
            client.close()
        }
    }
    
    private fun relay(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
            }
        } catch (e: Exception) {
            // Connection closed
        }
    }
}
```

### Step 3: Integrate into VPN Service

Modify `GooseRelayVpnService.kt` to use fake DNS.

**Changes to `GooseRelayVpnService.kt`:**

```kotlin
// Add at class level
private var fakeDnsServer: FakeDnsServer? = null
private var fakeDnsSocksProxy: FakeDnsSocksProxy? = null

// In startVpn() method, after starting Go client:

// Start fake DNS server
fakeDnsServer = FakeDnsServer("10.0.0.1", 53)
fakeDnsServer?.start()
VpnManager.appendLog("Fake DNS server started on 10.0.0.1:53")

// Start fake DNS SOCKS proxy (intercepts fake IPs)
fakeDnsSocksProxy = FakeDnsSocksProxy(
    listenPort = 10800, // Different port from main SOCKS
    upstreamSocksHost = "127.0.0.1",
    upstreamSocksPort = socksPort,
    fakeDnsServer = fakeDnsServer!!
)
fakeDnsSocksProxy?.start()
VpnManager.appendLog("Fake DNS SOCKS proxy started on port 10800")

// Modify VPN builder to use fake DNS
val builder = Builder()
    .setSession(getString(R.string.app_name))
    .setMtu(1500)
    .addAddress("10.0.0.2", 32)
    .addRoute("0.0.0.0", 0)
    .addDnsServer("10.0.0.1") // Point to fake DNS server
    .addRoute("198.18.0.0", 16) // Route fake IP range through VPN

// In stopVpn() method:
fakeDnsSocksProxy?.stop()
fakeDnsServer?.stop()
```

## How It Works

1. **DNS Query**: App makes DNS query → Android sends to 10.0.0.1:53
2. **Fake DNS**: Fake DNS server returns fake IP (198.18.x.x) and stores mapping
3. **Connection**: App connects to fake IP → Routed through VPN
4. **SOCKS Proxy**: Fake DNS SOCKS proxy intercepts connection
5. **Hostname Resolution**: Proxy looks up real hostname for fake IP
6. **Upstream**: Proxy sends real hostname to upstream SOCKS5 (Go client)
7. **Server Resolution**: Go client sends hostname to server, server resolves DNS

## Advantages

✅ No Go code changes required
✅ Lightweight implementation (pure Kotlin)
✅ Works with existing SOCKS5 proxy
✅ DNS resolution happens at server
✅ Bypasses DNS filtering

## Disadvantages

⚠️ Requires maintaining hostname→IP mapping
⚠️ Limited to 65,535 unique hostnames (198.18.0.0/16 range)
⚠️ Additional proxy layer adds slight latency
⚠️ More complex than using Proxy mode

## Alternative: Use Proxy Mode

The simplest solution is still to use **Proxy mode** which already handles DNS remotely without any additional code.

