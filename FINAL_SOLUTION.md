# Final Solution - TUN Module Integration

## What Was Done

The TUN module for DNS interception has been integrated into the main `gooserelayvpn.aar` file instead of being built as a separate module.

## Architecture

```
GooseRelayVPN/
├── mobile/
│   ├── (original Go code - from upstream project)
│   └── tun/              ← CUSTOM CODE - DO NOT OVERWRITE
│       ├── go.mod        ← Keep this for documentation
│       ├── tun_api.go    ← TUN bridge API
│       ├── tun_bridge.go ← DNS interception logic
│       ├── tcp_handler.go← TCP forwarding
│       └── tun_syscall.go← System calls
```

## Build Process

**Single AAR Build:**
```bash
gomobile bind -o android/app/libs/gooserelayvpn.aar ./mobile/
```

This command builds **all** packages under `./mobile/`, including:
- Original Go client code
- TUN bridge module (`mobile/tun/`)

Result: One AAR file with all functionality.

## Usage in Kotlin

```kotlin
import mobile.Mobile

// Start TUN bridge
Mobile.startTunBridge(fd.toLong(), 1500L, "127.0.0.1:1080")

// Stop TUN bridge
Mobile.stopTunBridge()

// Check if running
val running = Mobile.isTunBridgeRunning()

// Get bandwidth
val bandwidth = Mobile.getTunBandwidth()

// Get DNS mapping
val hostname = Mobile.getDNSMapping("198.18.0.1")

// Get version
val version = Mobile.getVersion()
```

## ⚠️ IMPORTANT: Updating Original Go Code

When you update the Go code from the upstream project:

### ✅ DO:
1. Update files in `cmd/`, `internal/`, root `go.mod`, etc.
2. Keep `mobile/tun/` directory intact
3. Rebuild: `cd android && bash build_go_mobile.sh`

### ❌ DON'T:
1. Delete or overwrite `mobile/tun/` directory
2. Replace the entire `mobile/` directory
3. Modify `mobile/tun/` files (unless fixing bugs)

### Safe Update Process:
```bash
# 1. Backup TUN module
cp -r mobile/tun /tmp/tun-backup

# 2. Update from upstream
cd /path/to/upstream/project
git pull
cp -r cmd/ internal/ go.mod /path/to/GooseRelayVPN/

# 3. Restore TUN module if needed
if [ ! -d /path/to/GooseRelayVPN/mobile/tun ]; then
    cp -r /tmp/tun-backup /path/to/GooseRelayVPN/mobile/tun
fi

# 4. Rebuild
cd /path/to/GooseRelayVPN/android
bash build_go_mobile.sh
```

## Why Keep go.mod in mobile/tun/?

Even though `mobile/tun/go.mod` is not used for building (since it's included in the main build), we keep it for:

1. **Documentation** - Shows this is logically a separate module
2. **IDE Support** - Helps IDEs understand the package structure
3. **Future Flexibility** - Easy to switch back to separate build if needed

## How It Works

1. **User enables "Fake DNS"** in app settings
2. **VPN connects** with special configuration (172.19.0.1/30)
3. **DNS queries intercepted** by TUN bridge → returns fake IP (198.18.x.x)
4. **TCP connections intercepted** → TUN bridge looks up real hostname
5. **SOCKS5 connection** with hostname (not IP) → tunnels to VPS
6. **VPS resolves DNS** remotely → bypasses local filtering

## Benefits

✅ **No separate module issues** - Everything in one AAR  
✅ **Simpler build process** - One gomobile bind command  
✅ **Works on GitHub Actions** - No complex build steps  
✅ **Remote DNS resolution** - Bypasses filtering in Iran  
✅ **Easy to maintain** - Just protect `mobile/tun/` during updates  

## Trade-offs

⚠️ **Less separation** - TUN code is part of mobile package  
⚠️ **Manual care needed** - Must not overwrite `mobile/tun/` when updating  
⚠️ **Same namespace** - TUN functions are in `mobile.Mobile`, not separate  

## Testing

After building, test with:

1. Enable "Fake DNS" in app settings
2. Connect VPN
3. Check logs for:
   - `Starting Go TUN bridge with DNS interception...`
   - `Go TUN bridge started (DNS will be resolved remotely)`
   - `[TUN-BRIDGE] Starting bridge`
   - `[TUN-DNS] Mapped hostname -> fake IP`

## Troubleshooting

### Build fails with "no exported names"
- Check that `mobile/tun/tun_api.go` has imports before code
- Verify all exported functions use gomobile-compatible types

### App crashes when enabling Fake DNS
- Check logcat: `adb logcat | grep -E "TUN-|GooseRelayVPN"`
- Verify `gooserelayvpn.aar` includes TUN functions
- Check that class name is `mobile.Mobile`, not `tun.Tun`

### DNS still filtered
- Verify "Fake DNS" is enabled in settings
- Check logs for `Go TUN bridge started`
- Ensure VPN is using 172.19.0.1/30 address range

## Summary

This solution works and is simpler than building separate AARs. Just remember to **protect `mobile/tun/` directory** when updating the original Go code from upstream.
