#!/bin/bash
# Builds Pocket XR PC helper for Mac with swiftc (the Command Line Tools are enough; no Xcode, no SwiftPM), and its
# checks.
#
#   scripts/build.sh            this Mac's chip
#   scripts/build.sh universal  Intel and Apple silicon in one program: for releases
#   scripts/build.sh checks     the logic checks (Checks/), run at once; exit code 0 = all passed
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=$(xcrun --show-sdk-path)

# Command Line Tools installed over an older set keep a 2023 usr/include/swift/module.modulemap beside the new
# bridging.modulemap, and every Swift compile then stops at "redefinition of module 'SwiftBridging'" (as in
# pocket-xr-mac's scripts/build-direct.sh). The old file is shown to the compiler as an empty one.
vfs() {
    local out="$1" stale=/Library/Developer/CommandLineTools/usr/include/swift/module.modulemap
    VFS=()
    if [ -f "$stale" ] && [ -f "${stale%module.modulemap}bridging.modulemap" ]; then
        : > "$out/empty.modulemap"
        printf '{ "version": 0, "roots": [ { "name": "%s", "type": "file", "external-contents": "%s" } ] }\n' \
            "$stale" "$PWD/$out/empty.modulemap" > "$out/vfs.yaml"
        VFS=(-vfsoverlay "$out/vfs.yaml")
    fi
}

if [ "${1:-}" = "checks" ]; then
    OUT=.build/checks; mkdir -p "$OUT"; vfs "$OUT"
    swiftc ${VFS[@]+"${VFS[@]}"} -swift-version 5 -target "$(uname -m)-apple-macosx14.0" -sdk "$SDK" -Onone -wmo \
        -module-name HelperChecks Sources/HelperCore/*.swift Checks/*.swift -o "$OUT/checks"
    "$OUT/checks"
    exit
fi

if [ "${1:-}" = "universal" ]; then
    for arch in x86_64 arm64; do ARCH="$arch" OUT=".build/$arch" bash "$0"; done
    mkdir -p .build/release
    lipo -create .build/x86_64/PocketXRPCHelper .build/arm64/PocketXRPCHelper -output .build/release/PocketXRPCHelper
    lipo -info .build/release/PocketXRPCHelper
    exit
fi

OUT="${OUT:-.build/release}"
mkdir -p "$OUT"; vfs "$OUT"
TARGET="${ARCH:-$(uname -m)}-apple-macosx14.0"
swiftc ${VFS[@]+"${VFS[@]}"} -swift-version 5 -target "$TARGET" -sdk "$SDK" -O -parse-as-library -emit-module -emit-object -wmo \
    -module-name HelperCore -emit-module-path "$OUT/HelperCore.swiftmodule" Sources/HelperCore/*.swift -o "$OUT/HelperCore.o"
# CVirtualDisplay: CoreGraphics' virtual display classes, declared for Swift (a header and its module map only).
swiftc ${VFS[@]+"${VFS[@]}"} -swift-version 5 -target "$TARGET" -sdk "$SDK" -O -wmo -module-name PocketXRPCHelper -I "$OUT" \
    -I Sources/CVirtualDisplay Sources/PcHelper/*.swift "$OUT/HelperCore.o" \
    -framework AppKit -framework CoreGraphics -framework Security -framework ServiceManagement \
    -o "$OUT/PocketXRPCHelper"
echo "built $OUT/PocketXRPCHelper"
