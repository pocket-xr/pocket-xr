#!/bin/bash
# Builds "Pocket XR PC helper.app" in build/ (Intel and Apple silicon) and signs it: with the Developer ID Application
# certificate when the keychain has one (for downloads; then scripts/notarise.sh), else ad hoc (this Mac only).
# Signing with the Developer ID needs the login keychain unlocked: run it in Terminal on the Mac, not over ssh.
#
#   scripts/make-app.sh          universal, signed
#   scripts/make-app.sh adhoc    universal, signed ad hoc (for trying it out)
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/build.sh checks
bash scripts/build.sh universal

APP="build/Pocket XR PC helper.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/PocketXRPCHelper "$APP/Contents/MacOS/PocketXRPCHelper"
cp Resources/Info.plist "$APP/Contents/Info.plist"
[ -f Resources/AppIcon.icns ] && cp Resources/AppIcon.icns "$APP/Contents/Resources/"

DEVID=""
if [ "${1:-}" != "adhoc" ]; then
    DEVID="$(security find-identity -v -p codesigning | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' | head -1)"
fi
if [ -n "$DEVID" ]; then
    # For downloads: the hardened runtime and a secure timestamp, which notarisation needs.
    codesign --force --timestamp --options runtime --sign "$DEVID" "$APP"
    echo "Signed with $DEVID."
else
    codesign --force --sign - "$APP"
    echo "Signed ad hoc (this Mac only)."
fi
codesign --verify --strict --verbose=2 "$APP"
echo "Built $APP"
