"""Builds the PC helper's download: PocketXR-PC-Setup.zip.

    python pc-helper/make-bundle.py <ALVR streamer folder> [settings to seed from] [--sign]

--sign signs the helper with Mike's Azure Artifact Signing account (account "pocketxr", East US), so Windows names the
publisher instead of warning about an unknown one. It needs: the identity validation passed and a certificate profile
named "pocketxr-helper" in the account; Microsoft's Artifact Signing client tools (signtool and its Azure library) and
the Azure CLI, signed in with `az login` as a user with the account's Certificate Profile Signer role.

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


SIGNING = {
    "Endpoint": "https://eus.codesigning.azure.net",
    "CodeSigningAccountName": "pocketxr",
    "CertificateProfileName": "pocketxr-helper",
}


def find_signing_tools():
    """signtool and the Azure signing library from Microsoft's Artifact Signing client tools."""
    roots = [os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\MicrosoftArtifactSigningClientTools"),
             os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\MicrosoftTrustedSigningClientTools"),
             r"C:\Program Files (x86)\Windows Kits\10\bin"]
    signtool = dlib = None
    for root in roots:
        for d, _, files in os.walk(root):
            for f in files:
                low = f.lower()
                if low == "signtool.exe" and ("x64" in d.lower() or signtool is None):
                    signtool = os.path.join(d, f)
                if low in ("azure.codesigning.dlib.dll",):
                    dlib = os.path.join(d, f)
    if not signtool or not dlib:
        sys.exit("signing: Microsoft's Artifact Signing client tools are not installed (signtool %s, library %s)" % (signtool, dlib))
    return signtool, dlib


def sign(path):
    signtool, dlib = find_signing_tools()
    meta = os.path.join(HERE, "dist", "signing.json")
    json.dump(SIGNING, open(meta, "w"))
    subprocess.check_call([signtool, "sign", "/v", "/fd", "SHA256", "/tr", "http://timestamp.acs.microsoft.com", "/td", "SHA256",
                           "/dlib", dlib, "/dmdf", meta, path])
    subprocess.check_call([signtool, "verify", "/pa", path])
    print("signed: " + path)


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
2. Double-click PocketXrPcHelper.exe. (An unsigned test copy makes Windows say it protected your PC: choose More info,
   then Run anyway. The signed one names Mike Haye as the publisher.)
3. When Windows asks to let the helper change your device, choose Yes. That lets SteamVR receive the picture from your
   phone through Windows Firewall. It only asks once.
4. On your glasses, tap PC VR in Pocket XR. The first time, this PC asks whether your phone may start SteamVR: choose Yes.

The helper starts with Windows and sits in the tray, by the clock. It includes ALVR's streamer (MIT licence, see
alvr_streamer_windows/licenses), the version Pocket XR speaks, and sets it up with SteamVR for you. You need Steam and
SteamVR installed.
"""


def main():
    args = [a for a in sys.argv[1:] if a != "--sign"]
    signing = "--sign" in sys.argv
    if not args:
        sys.exit(__doc__)
    src = args[0]
    seed = args[1] if len(args) > 1 else os.path.join(src, "session.json")
    build_exe()
    if signing:
        os.makedirs(os.path.join(HERE, "dist"), exist_ok=True)
        sign(os.path.join(HERE, "PocketXrPcHelper.exe"))
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
