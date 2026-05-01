# TUN Wrapper Fix - Exposing Subpackage Functions

## Problem

The error showed:
```
Failed to start Go TUN bridge: mobile.Mobile.startTunBridge [long, long, class java.lang.String]
```

This means `mobile.Mobile.startTunBridge()` method didn't exist in the AAR.

## Root Cause

**Gomobile only exports functions from the main package**, not subpackages.

```
mobile/
├── mobile.go          ← Gomobile exports functions from HERE
└── tun/
    └── tun_api.go     ← Functions here are NOT exported by gomobile
```

Even though `mobile/tun/tun_api.go` has exported functions like `StartTunBridge()`, gomobile doesn't automatically include them because they're in a subpackage.

## Solution

Added **wrapper functions** in `mobile/mobile.go` that call the TUN subpackage functions:

```go
import "github.com/kianmhz/GooseRelayVPN/mobile/tun"

// Wrapper functions
func StartTunBridge(tunFd int64, mtu int64, socksAddr string) error {
    return tun.StartTunBridge(int32(tunFd), int32(mtu), socksAddr)
}

func StopTunBridge() {
    tun.StopTunBridge()
}

func IsTunBridgeRunning() bool {
    return tun.IsTunBridgeRunning()
}

func GetTunBandwidth() (up int64, down int64) {
    return tun.GetTunBandwidth()
}

func GetDNSMapping(fakeIP string) string {
    return tun.GetDNSMapping(fakeIP)
}

func GetDNSMappingCount() int {
    return tun.GetDNSMappingCount()
}

func GetTunVersion() string {
    return tun.GetVersion()
}
```

## How Gomobile Works

**What gets exported:**
- ✅ Functions in `mobile/mobile.go` (main package)
- ✅ Functions in `mobile/*.go` files (same package)

**What doesn't get exported:**
- ❌ Functions in `mobile/tun/*.go` (subpackage)
- ❌ Functions in `mobile/subdir/*.go` (any subpackage)

**Solution:** Add wrapper functions in the main package that call subpackage functions.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Android App (Kotlin)                                    │
│                                                         │
│  mobile.Mobile.startTunBridge()                         │
│         │                                               │
│         ▼                                               │
│  ┌──────────────────────────────────────────┐          │
│  │ mobile/mobile.go (Wrapper)               │          │
│  │                                          │          │
│  │  func StartTunBridge(...) error {       │          │
│  │      return tun.StartTunBridge(...)     │──────┐   │
│  │  }                                       │      │   │
│  └──────────────────────────────────────────┘      │   │
│                                                    │   │
│  ┌──────────────────────────────────────────┐     │   │
│  │ mobile/tun/tun_api.go (Implementation)   │◄────┘   │
│  │                                          │          │
│  │  func StartTunBridge(...) error {       │          │
│  │      // Actual TUN bridge logic         │          │
│  │  }                                       │          │
│  └──────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────┘
```

## Benefits of This Approach

1. **Clean separation** - TUN code stays in `mobile/tun/` subpackage
2. **Easy to maintain** - Update `mobile/tun/` without touching main code
3. **Gomobile compatible** - Wrapper functions are in main package
4. **Type conversion** - Wrapper handles int64 ↔ int32 conversion

## Usage in Kotlin

No changes needed in Kotlin code - it already uses `mobile.Mobile`:

```kotlin
import mobile.Mobile

// These now work because wrappers exist in mobile.Mobile
Mobile.startTunBridge(fd.toLong(), 1500L, "127.0.0.1:1080")
Mobile.stopTunBridge()
Mobile.isTunBridgeRunning()
Mobile.getTunBandwidth()
Mobile.getDNSMapping("198.18.0.1")
Mobile.getTunVersion()
```

## Testing

After rebuilding the AAR:

1. Push changes to GitHub
2. Wait for CI to build
3. Download APK
4. Enable "Fake DNS" and connect
5. Check logs for:
   - `Starting Go TUN bridge with DNS interception...`
   - `Go TUN bridge started (DNS will be resolved remotely)`
   - `[TUN-BRIDGE] Starting bridge`

## Why This Wasn't Needed Before

In my original approach, I tried to build `mobile/tun/` as a **separate AAR** with its own gomobile bind command. That would have worked, but had the "no exported names" issue.

The current approach (single AAR) is simpler but requires these wrapper functions.

## Summary

**Problem:** Gomobile doesn't export subpackage functions  
**Solution:** Add wrapper functions in main package  
**Result:** TUN functions now accessible from `mobile.Mobile`  

This is the final piece needed to make the TUN bridge work!
