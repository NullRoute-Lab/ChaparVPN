# Android Remote DNS Resolution Guide

This guide explains how to configure the GooseRelayVPN Android app to resolve DNS queries at the destination (VPS server) instead of at the source (client in Iran), bypassing DNS filtering.

## Understanding the Problem

In Android VPN mode, DNS resolution happens in two places:

1. **Client-side DNS (Default)**: Android uses the DNS servers configured in the VPN interface to resolve hostnames BEFORE sending traffic through the tunnel. This means filtered DNS in Iran still affects your connections.

2. **Server-side DNS (Remote)**: DNS queries are resolved at the VPS server AFTER traffic goes through the tunnel, bypassing local DNS filtering.

## Solutions

### Solution 1: Use Proxy Mode (Recommended for Iran)

The simplest solution is to use **Proxy Mode** instead of VPN mode:

1. Open the GooseRelayVPN app
2. Go to Settings
3. Change **Connection Mode** from "VPN" to "Proxy"
4. Configure your apps to use the SOCKS5 proxy at `127.0.0.1:1080`

**Advantages:**
- DNS resolution happens at the server (remote)
- No DNS filtering issues
- Works with all apps that support SOCKS5 proxy

**Disadvantages:**
- Each app needs to be configured individually
- Not all apps support proxy configuration

**How to configure apps in Proxy Mode:**

**Firefox:**
1. Settings → Network Settings → Manual proxy configuration
2. SOCKS Host: `127.0.0.1`, Port: `1080`
3. Enable "Proxy DNS when using SOCKS v5"

**Chrome (requires extension):**
1. Install "Proxy SwitchyOmega" extension
2. Configure SOCKS5 proxy: `127.0.0.1:1080`
3. Enable "Proxy DNS"

**Telegram:**
1. Settings → Data and Storage → Proxy Settings
2. Add SOCKS5 proxy: `127.0.0.1:1080`

### Solution 2: Custom DNS Servers in VPN Mode

If you need VPN mode (system-wide), you can configure custom DNS servers that will be routed through the VPN tunnel.

#### Option A: Use Your VPS as DNS Server (Best for Iran)

If your VPS has a DNS resolver running, you can use it directly:

1. **On your VPS**, install and configure a DNS resolver (e.g., `dnsmasq` or `unbound`):

```bash
# Install dnsmasq
sudo apt update
sudo apt install dnsmasq

# Configure dnsmasq to listen on all interfaces
sudo nano /etc/dnsmasq.conf
```

Add these lines:
```
listen-address=0.0.0.0
bind-interfaces
server=1.1.1.1
server=8.8.8.8
cache-size=1000
```

```bash
# Restart dnsmasq
sudo systemctl restart dnsmasq

# Allow DNS port in firewall
sudo ufw allow 53/udp
```

2. **In the Android app**, add a custom DNS setting:
   - You'll need to add a UI for this in the settings screen
   - Set custom DNS to your VPS IP address (e.g., `YOUR_VPS_IP`)

#### Option B: Use Public DNS (Partial Solution)

Configure the app to use public DNS servers like Cloudflare or Google DNS. This doesn't fully solve the problem since DNS queries still happen on the client side, but these servers are less likely to be filtered:

**Custom DNS servers to try:**
- Cloudflare: `1.1.1.1, 1.0.0.1`
- Google: `8.8.8.8, 8.8.4.4`
- Quad9: `9.9.9.9, 149.112.112.112`
- Shecan (Iran-friendly): `178.22.122.100, 185.51.200.2`

### Solution 3: Fake DNS (Advanced)

This is an advanced solution that requires modifying the Android app to implement a fake DNS server that:

1. Returns fake IPs (e.g., from 198.18.0.0/16 range) for all DNS queries
2. Maintains a mapping of fake IP → real hostname
3. When traffic is sent to a fake IP, the SOCKS client sends the real hostname to the server
4. Server resolves the real hostname remotely

This is complex and requires significant code changes.

## Implementation Status

### What's Already Done

✅ Added `customDnsServers` field to `GlobalSettings`
✅ Modified `GooseRelayVpnService` to use custom DNS servers if configured
✅ Added logging to show which DNS servers are being used

### What You Need to Do

1. **Add UI for Custom DNS Configuration**

Create a settings screen where users can enter custom DNS servers. Add this to your settings UI:

```kotlin
// In your settings screen composable
var customDns by remember { mutableStateOf(globalSettings.customDnsServers) }

OutlinedTextField(
    value = customDns,
    onValueChange = { customDns = it },
    label = { Text("Custom DNS Servers (comma-separated)") },
    placeholder = { Text("e.g., 1.1.1.1, 8.8.8.8") },
    modifier = Modifier.fillMaxWidth()
)

Text(
    text = "Leave empty to use default DNS servers. For remote DNS resolution in Iran, " +
           "enter your VPS IP if running a DNS server there, or use Proxy mode instead.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

2. **Save the Custom DNS Setting**

When the user saves settings, include the custom DNS:

```kotlin
scope.launch {
    GlobalSettingsStore.save(
        context,
        globalSettings.copy(customDnsServers = customDns)
    )
}
```

## Recommended Configuration for Iran

**Best Option: Proxy Mode**
- Set Connection Mode to "Proxy"
- Configure apps to use SOCKS5 proxy at `127.0.0.1:1080`
- DNS resolution happens automatically at the server

**Alternative: VPN Mode + VPS DNS**
- Keep Connection Mode as "VPN"
- Set up DNS server on your VPS (see Option A above)
- Configure custom DNS to your VPS IP
- All DNS queries will be routed through the VPN tunnel to your VPS

## Testing

To verify DNS is working correctly:

1. **Check DNS in use:**
   - Look at the VPN service logs in the app
   - You should see: "Using custom DNS servers: X.X.X.X" or "Using default DNS servers"

2. **Test DNS resolution:**
   - Try accessing blocked websites
   - If they work, DNS is being resolved correctly

3. **Verify with nslookup (if using VPS DNS):**
   ```bash
   # On your phone (using Termux or similar)
   nslookup google.com YOUR_VPS_IP
   ```

## Troubleshooting

**Problem: Websites still blocked**
- Solution: Make sure you're using Proxy mode, or VPS DNS is properly configured

**Problem: DNS queries timing out**
- Solution: Check that your VPS firewall allows UDP port 53
- Solution: Verify dnsmasq is running: `sudo systemctl status dnsmasq`

**Problem: Slow DNS resolution**
- Solution: Increase dnsmasq cache size in `/etc/dnsmasq.conf`
- Solution: Add more upstream DNS servers in dnsmasq config

## Summary

For users in Iran, the **recommended solution is Proxy Mode** because:
- ✅ DNS resolution happens at the server automatically
- ✅ No additional VPS configuration needed
- ✅ No DNS filtering issues
- ✅ Simple to use

If you need system-wide VPN mode, set up a DNS server on your VPS and configure the app to use it as custom DNS.
