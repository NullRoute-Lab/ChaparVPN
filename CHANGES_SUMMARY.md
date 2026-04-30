# Android Remote DNS Configuration - Changes Summary

## Overview

Added support for custom DNS server configuration in the Android app to help users in Iran bypass DNS filtering. This allows users to configure DNS servers that will be used by the VPN interface.

## Files Modified

### 1. `android/app/src/main/java/com/gooserelay/gooserelayvpn/util/GlobalSettingsStore.kt`

**Changes:**
- Added `customDnsServers: String = ""` field to `GlobalSettings` data class
- Added `KEY_CUSTOM_DNS_SERVERS` preference key
- Updated `save()` method to persist custom DNS servers
- Updated `toModel()` method to load custom DNS servers

**Purpose:** Store user's custom DNS server configuration persistently.

### 2. `android/app/src/main/java/com/gooserelay/gooserelayvpn/service/GooseRelayVpnService.kt`

**Changes:**
- Modified DNS server configuration logic (lines 209-233)
- Added support for custom DNS servers from settings
- If `customDnsServers` is not blank, parse and use those servers
- Otherwise, fall back to default public DNS servers
- Added detailed logging to show which DNS servers are being used
- Added comments explaining DNS resolution behavior in VPN vs Proxy mode

**Purpose:** Use custom DNS servers when configured by the user.

### 3. `android/app/src/main/java/com/gooserelay/gooserelayvpn/ui/settings/GlobalSettingsScreen.kt`

**Changes:**
- Added `OutlinedTextField` for custom DNS servers input (after split tunneling section)
- Field accepts comma-separated DNS server IPs
- Added helpful supporting text explaining:
  - How to format DNS servers
  - When to use custom DNS
  - Alternative solution (Proxy mode)
- Field supports multiple lines for better readability

**Purpose:** Provide UI for users to configure custom DNS servers.

## How It Works

### VPN Mode (Default)

1. User enters custom DNS servers in Settings (e.g., "1.1.1.1, 8.8.8.8")
2. When VPN connects, `GooseRelayVpnService` reads the custom DNS setting
3. Custom DNS servers are added to the Android VPN interface via `builder.addDnsServer()`
4. Android routes all DNS queries to these servers through the VPN tunnel
5. DNS resolution happens using the configured servers

### Important Notes

- **DNS resolution still happens on the client side** in VPN mode, even with custom DNS
- For true remote DNS resolution, users should use **Proxy mode** instead
- Custom DNS is most useful when pointing to a DNS server running on the VPS
- If left empty, defaults to: 1.1.1.1, 8.8.8.8, 9.9.9.9, 94.140.14.14

## Testing

1. Build and install the modified Android app
2. Open Settings in the app
3. Scroll down to see "Custom DNS Servers" field
4. Enter DNS servers (e.g., "1.1.1.1, 8.8.8.8")
5. Save settings
6. Connect VPN
7. Check logs - should show: "Using custom DNS servers: 1.1.1.1, 8.8.8.8"

## Recommended Configuration for Iran

### Option 1: Proxy Mode (Best Solution)
- Change Connection Mode to "Proxy"
- Leave Custom DNS empty
- Configure apps to use SOCKS5 proxy at 127.0.0.1:1080
- DNS resolution happens automatically at the server

### Option 2: VPN Mode + VPS DNS
- Keep Connection Mode as "VPN"
- Set up DNS server on your VPS (see ANDROID_REMOTE_DNS.md)
- Enter your VPS IP in Custom DNS field
- All DNS queries route through VPN to your VPS

### Option 3: VPN Mode + Public DNS
- Keep Connection Mode as "VPN"
- Enter public DNS servers (e.g., "1.1.1.1, 8.8.8.8")
- Partial solution - DNS still resolved on client but using unfiltered servers

## Next Steps

1. **Build the Android app:**
   ```bash
   cd android
   ./gradlew assembleDebug
   ```

2. **Install on device:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Test the feature:**
   - Open app settings
   - Configure custom DNS
   - Connect VPN
   - Verify DNS is working

4. **Optional: Set up VPS DNS server** (see ANDROID_REMOTE_DNS.md for instructions)

## Limitations

- In VPN mode, DNS resolution still happens on the Android device (not truly remote)
- For true remote DNS, Proxy mode is required
- Custom DNS only affects VPN mode, not Proxy mode
- Some apps may ignore system DNS and use their own resolvers

## Additional Resources

- See `ANDROID_REMOTE_DNS.md` for detailed configuration guide
- See VPN service logs for DNS debugging information
