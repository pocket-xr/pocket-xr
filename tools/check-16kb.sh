#!/usr/bin/env bash
# Checks that every native library in an app bundle or APK loads on 16 KB memory pages, as Google Play requires:
# each ELF LOAD segment must be aligned to at least 16384 bytes.
#
# Usage: tools/check-16kb.sh release/play/pocketxr-1.5.2-182.aab
# Exits 1 if any library is aligned to less.
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 <bundle.aab or app.apk>" >&2
    exit 2
fi

PY=$(command -v python3 || command -v python)

"$PY" - "$1" <<'EOF'
import struct, sys, zipfile

bad = 0
with zipfile.ZipFile(sys.argv[1]) as z:
    libs = sorted(n for n in z.namelist() if n.endswith(".so"))
    for name in libs:
        b = z.read(name)
        if b[:4] != b"\x7fELF" or b[4] != 2:
            print(f"skip  (not a 64-bit ELF)  {name}")
            continue
        phoff, = struct.unpack_from("<Q", b, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", b, 0x36)
        headers = [struct.unpack_from("<IIQQQQQQ", b, phoff + i * phentsize) for i in range(phnum)]
        loads = [h[7] for h in headers if h[0] == 1]  # PT_LOAD: p_align
        low = min(loads) if loads else 0
        ok = low >= 16384
        bad += not ok
        print(f"{'ok ' if ok else 'BAD'}  {low:>6}  {name}")
    print(f"{len(libs)} libraries, {bad} not 16 KB aligned")
sys.exit(1 if bad else 0)
EOF
