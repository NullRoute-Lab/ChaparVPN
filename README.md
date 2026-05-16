# GooseRelayVPN Android Client
🌐 **[فارسی](README_FA.md)** 


Android client for GooseRelayVPN that runs the GooseRelay core through a Go mobile bridge and provides a complete Android UI for VPN lifecycle, profiles, logs, and settings.

- Upstream core project: https://github.com/nullroute-lab/GooseRelayVPN
- This repository: Android-focused client implementation

## What This App Does

This app creates a local SOCKS5 endpoint on Android and tunnels TCP traffic through the GooseRelay architecture:

1. Local app/browser traffic -> SOCKS5
2. GooseRelay encrypted framing (AES key from your profile)
3. HTTPS path through Google-facing endpoints (Apps Script flow)
4. Your VPS exit server handles outbound target connections

The app wraps this flow in Android `VpnService` so selected/full traffic can be routed through the tunnel.

## Key Features

- Android VPN integration (`VpnService` + tun2socks)
- Profile-based configuration
- JSON import/export for profile config
- Real-time Home status + telemetry cards
- Logs tab for Android/core diagnostics
- Split tunneling and internet sharing controls

## Configuration Model (Profile)

Profile fields are aligned with GooseRelay client config:

```json
{
  "debug_timing": false,
  "socks_host": "127.0.0.1",
  "socks_port": 1080,
  "google_host": "216.239.38.120",
  "sni": ["www.google.com", "mail.google.com", "accounts.google.com"],
  "script_keys": [
    "REPLACE_WITH_DEPLOYMENT_ID",
    "OPTIONAL_SECOND_DEPLOYMENT_ID"
  ],
  "tunnel_key": "REPLACE_WITH_OUTPUT_OF_scripts_gen-key.sh"
}
```

Notes:
- `script_keys` in UI: one key per line.
- `tunnel_key`: must match server-side key.
- Import/export in app uses JSON.

## Upstream Setup Flow (Required)

You still need upstream infrastructure prepared first:

1. Prepare VPS and run `goose-server`
2. Deploy `apps_script/Code.gs` and obtain deployment ID(s)
3. Generate key via `scripts/gen-key.sh`
4. Put deployment ID(s) and key into Android profile
5. Connect in app and configure app/browser proxy as needed

For full infra details, use upstream docs:
- https://github.com/nullroute-lab/GooseRelayVPN

## Android Build (Local)

Requirements:
- Android Studio
- JDK 17
- Go 1.22+
- Android SDK / NDK

Build AAR (Go mobile bridge):

```bash
bash android/build_go_mobile.sh
```

Build debug APK:

```bash
cd android
./gradlew :app:assembleDebug
```

## Release / CI

Workflows in this repo:
- `.github/workflows/android-ci.yml`
- `.github/workflows/release-manual.yml`
- `.github/workflows/release.yml`

Manual signed release requires secrets:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Troubleshooting

- If reconnect fails with SOCKS port busy:
  - Disconnect, wait a few seconds, reconnect.
  - Ensure no other app uses the same SOCKS port.
- If connection stays in preparing state:
  - Verify `script_keys` and `tunnel_key`.
  - Check Logs tab for core errors.
- If no traffic flows:
  - Verify VPN permission granted.
  - Verify split tunnel app selection.

## Credits

1. Main project:
   - https://github.com/nullroute-lab/GooseRelayVPN

2. Project that inspired the main project idea:
   - https://github.com/masterking32/MasterHttpRelayVPN
