# Remote DNS Resolution for GooseRelayVPN Android

## What Was Done

I've added custom DNS server configuration to the Android app to help bypass DNS filtering in Iran. This allows you to configure which DNS servers the VPN uses.

## Changes Made (Android Only - No Go Code Changed)

### 3 Files Modified:

1. **GlobalSettingsStore.kt** - Added `customDnsServers` field to store DNS configuration
2. **GooseRelayVpnService.kt** - Modified to use custom DNS servers when configured
3. **GlobalSettingsScreen.kt** - Added UI field for entering custom DNS servers

### 2 Documentation Files Created:

1. **ANDROID_REMOTE_DNS.md** - Complete guide with all configuration options
2. **CHANGES_SUMMARY.md** - Technical details of what was changed

## Quick Start

### Option 1: Use Proxy Mode (Recommended for Iran) ✅

This is the **best solution** because DNS resolution happens automatically at the server:

1. Open GooseRelayVPN app
2. Go to Settings
3. Change "Connection Mode" from "VPN" to "Proxy"
4. Save settings
5. Connect
6. Configure your apps to use SOCKS5 proxy: `127.0.0.1:1080`

**Apps that support SOCKS5:**
- Firefox (built-in proxy settings)
- Telegram (built-in proxy settings)
- Chrome (via Proxy SwitchyOmega extension)
- Most command-line tools (curl, wget, etc.)

### Option 2: Use VPN Mode with Custom DNS

If you need system-wide VPN mode:

1. **Set up DNS server on your VPS:**
   ```bash
   # On your VPS
   sudo apt update
   sudo apt install dnsmasq
   
   # Edit config
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
   # Restart and allow firewall
   sudo systemctl restart dnsmasq
   sudo ufw allow 53/udp
   ```

2. **Configure Android app:**
   - Open Settings
   - Scroll to "Custom DNS Servers"
   - Enter your VPS IP address (e.g., `YOUR_VPS_IP`)
   - Save settings
   - Connect VPN

3. **Verify:**
   - Check app logs
   - Should see: "Using custom DNS servers: YOUR_VPS_IP"

## How to Build and Install

```bash
# Navigate to android directory
cd android

# Build the app
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the `android` folder in Android Studio and build from there.

## Understanding DNS in VPN vs Proxy Mode

### VPN Mode (System-wide)
```
App → Android System → DNS Query (using configured DNS) → VPN Tunnel → Internet
                        ↑
                        DNS happens here (on your device)
```

**Limitations:**
- DNS resolution happens on your Android device
- Even with custom DNS, queries originate from your device
- DNS goes through VPN tunnel but is still resolved client-side

**When to use:**
- You need system-wide VPN for all apps
- You have a DNS server on your VPS
- You want to use specific DNS servers

### Proxy Mode (App-specific)
```
App → SOCKS5 Proxy (127.0.0.1:1080) → VPN Tunnel → Server → DNS Query → Internet
                                                             ↑
                                                             DNS happens here (on server)
```

**Advantages:**
- DNS resolution happens on the VPS server
- Bypasses all local DNS filtering
- No additional configuration needed

**When to use:**
- You're in Iran or other filtered networks
- You want true remote DNS resolution
- Your apps support SOCKS5 proxy

## Testing

1. **Build and install the app**
2. **Open Settings** - you should see "Custom DNS Servers" field
3. **Enter DNS servers** (e.g., "1.1.1.1, 8.8.8.8")
4. **Save and connect**
5. **Check logs** - should show which DNS servers are being used
6. **Test browsing** - try accessing blocked websites

## Troubleshooting

**Q: Websites still blocked in VPN mode**
- A: Use Proxy mode instead, or set up DNS server on your VPS

**Q: DNS queries timing out**
- A: Check VPS firewall allows UDP port 53
- A: Verify dnsmasq is running: `sudo systemctl status dnsmasq`

**Q: Custom DNS field not showing**
- A: Make sure you rebuilt and reinstalled the app after making changes

**Q: How do I know if DNS is working?**
- A: Check the VPN service logs in the app
- A: Try accessing a website that's normally blocked

## Important Notes

⚠️ **No Go code was modified** - All changes are in the Android Kotlin code only

✅ **Proxy mode is recommended** for Iran because it provides true remote DNS resolution

✅ **VPN mode with custom DNS** requires setting up a DNS server on your VPS

✅ **Default DNS servers** (1.1.1.1, 8.8.8.8, etc.) are used if custom DNS is empty

## Files to Review

- `ANDROID_REMOTE_DNS.md` - Complete configuration guide
- `CHANGES_SUMMARY.md` - Technical details of changes
- Modified files are marked in `git status`

## Summary

You now have the ability to configure custom DNS servers in the Android app. For the best experience in Iran, I recommend using **Proxy mode** which automatically handles DNS resolution at the server without any additional configuration.
