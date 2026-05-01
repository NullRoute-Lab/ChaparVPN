#!/bin/bash

# Build TUN module separately from main Go code
# This allows updating the main Go code without affecting TUN functionality

set -e

echo "Building TUN module for Android..."

cd tun

# Build for Android ARM64
gomobile bind -target=android/arm64 -o ../tun.aar -v .

echo "TUN module built successfully: mobile/tun.aar"
echo ""
echo "To use in Android:"
echo "1. Copy tun.aar to android/app/libs/"
echo "2. Add to build.gradle: implementation files('libs/tun.aar')"
echo "3. Import in Kotlin: import tun.Tun"
