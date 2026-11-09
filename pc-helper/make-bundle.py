"""Builds the PC helper's download: PocketXR-PC-Setup.zip.

    python pc-helper/make-bundle.py <ALVR streamer folder> [settings to seed from]

The zip holds:
- PocketXrPcHelper.exe (built here with Windows' own C# compiler);
- alvr_streamer_windows/: ALVR's streamer for Windows, the version Pocket XR's client speaks (21.0.0-dev14, nightly
  2026.11.04), without its debug symbols, logs or anyone's settings;
- alvr_streamer_windows/session.json: starting settings, with ALVR's setup wizard and new-version popup off, no trusted
  phones, and what the glasses need (view sized by the phone, 60 frames a second, ALVR's own hand gestures off);
- READ ME.txt.

The streamer folder isn't in the repository (about 240 MB): it is ALVR's own release zip, unpacked.
"""
import json
import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = "21.0.0-dev14"
SKIP_EXT = (".pdb",)
SKIP_NAMES = ("crash_log.txt", "session_log.txt")


def build_exe():
    csc = r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    subprocess.check_call([csc, "-nologo", "-target:winexe", "-win32icon:pocketxr.ico", "-r:System.Web.Extensions.dll",
                           "-r:System.Windows.Forms.dll", "-r:System.Drawing.dll", "-out:PocketXrPcHelper.exe",
                           "PocketXrPcHelper.cs"], cwd=HERE)


def copy_streamer(src, dst):
    for root, dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        os.makedirs(os.path.join(dst, rel), exist_ok=True)
        for f in files:
            if f.endswith(SKIP_EXT) or f in SKIP_NAMES or f.startswith("session.json"):
                continue
            shutil.copy2(os.path.join(root, f), os.path.join(dst, rel, f))


def seed_settings(seed_from, dst):
    s = json.load(open(seed_from, encoding="utf-8-sig"))
    if not str(s.get("server_version", "")).startswith(VERSION):
        sys.exit("the settings to seed from are ALVR %s, not %s" % (s.get("server_version"), VERSION))
    s["client_connections"] = {}
    st = s["session_settings"]
    extra = st["extra"]
    extra["open_setup_wizard"] = False
    extra["new_version_popup"]["enabled"] = False
    video = st["video"]
    for k in ("transcoding_view_resolution", "emulated_headset_view_resolution"):
        video[k]["variant"] = "Scale"
        video[k]["Scale"] = 1.0
    video["preferred_fps"] = 60.0
    st["headset"]["controllers"]["content"]["hand_tracking_interaction"]["enabled"] = False
    json.dump(s, open(os.path.join(dst, "session.json"), "w", encoding="utf-8"), indent=2)


README = """Pocket XR PC helper

Lets Pocket XR on your phone start SteamVR on this PC, with nothing to do here after setup.

1. Unzip this folder anywhere.
2. Double-click PocketXrPcHelper.exe. If Windows says it protected your PC, choose More info, then Run anyway.
3. When Windows asks to let the helper change your device, choose Yes. That lets SteamVR receive the picture from your
   phone through Windows Firewall. It only asks once.
4. On your glasses, tap PC VR in Pocket XR. The first time, this PC asks whether your phone may start SteamVR: choose Yes.

The helper starts with Windows and sits in the tray, by the clock. It includes ALVR's streamer (MIT licence, see
alvr_streamer_windows/licenses), the version Pocket XR speaks, and sets it up with SteamVR for you. You need Steam and
SteamVR installed.
"""


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    seed = sys.argv[2] if len(sys.argv) > 2 else os.path.join(src, "session.json")
    build_exe()
    out = os.path.join(HERE, "dist", "PocketXR-PC-Setup")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    shutil.copy2(os.path.join(HERE, "PocketXrPcHelper.exe"), out)
    copy_streamer(src, os.path.join(out, "alvr_streamer_windows"))
    seed_settings(seed, os.path.join(out, "alvr_streamer_windows"))
    open(os.path.join(out, "READ ME.txt"), "w", encoding="utf-8").write(README)
    zpath = os.path.join(HERE, "dist", "PocketXR-PC-Setup.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for root, _, files in os.walk(out):
            for f in files:
                full = os.path.join(root, f)
                z.write(full, os.path.join("PocketXR-PC-Setup", os.path.relpath(full, out)))
    print("%s: %.0f MB" % (zpath, os.path.getsize(zpath) / 1e6))


if __name__ == "__main__":
    main()
