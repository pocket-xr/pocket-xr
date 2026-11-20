#!/usr/bin/env bash
# Builds Pocket XR PC Screens for Linux (Sunshine with two patches, plus the Pocket XR setup) as a Flatpak bundle.
#
# Needs: flatpak, and from Flathub: org.flatpak.Builder, org.kde.Sdk//6.11, org.freedesktop.Sdk.Extension.node20//25.08
#   flatpak install --user flathub org.flatpak.Builder org.kde.Sdk//6.11 org.freedesktop.Sdk.Extension.node20//25.08
# Run:   bash build.sh [work dir]      (default ~/pxr-flatpak-build)
# Out:   <work dir>/PocketXR-PC-Screens.flatpak   (install: double-click it, or flatpak install --user <file>)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:-$HOME/pxr-flatpak-build}"
FLATHUB_COMMIT=675426ab2a8f   # flathub/dev.lizardbyte.app.Sunshine: "Update to v2026.914.233613"
ID=com.hayeco.pocketxr.PCScreens

mkdir -p "$WORK"
if [ ! -d "$WORK/flathub/.git" ]; then
    git clone -q https://github.com/flathub/dev.lizardbyte.app.Sunshine.git "$WORK/flathub"
fi
git -C "$WORK/flathub" fetch -q origin
git -C "$WORK/flathub" checkout -q "$FLATHUB_COMMIT"
cp "$HERE/$ID.yml" "$HERE"/0001-*.patch "$HERE"/0002-*.patch "$HERE/pocketxr-setup" "$HERE/$ID.desktop" "$HERE/$ID.metainfo.xml" "$WORK/flathub/"
cp "$HERE/../pocketxr-pc-screens.sh" "$HERE/../pocketxr_pc_helper.py" "$HERE/../../pocketxr.png" "$WORK/flathub/"
# org.kde.Sdk//6.11 already has markupsafe 3.0.4 in /usr, and pip stopped trying to uninstall it from the read-only
# runtime ("Read-only file system", seen on the Deck 20 Nov 2026): install beside it instead.
sed -i 's/pip3 install --verbose --exists-action=i /pip3 install --verbose --ignore-installed --exists-action=i /' \
    "$WORK/flathub/glad-dependencies.json"

cd "$WORK/flathub"
flatpak run org.flatpak.Builder --force-clean --user --ccache --disable-rofiles-fuse \
    --state-dir="$WORK/state" --repo="$WORK/repo" "$WORK/build" "$ID.yml"
flatpak build-bundle --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
    "$WORK/repo" "$WORK/PocketXR-PC-Screens.flatpak" "$ID"
ls -l "$WORK/PocketXR-PC-Screens.flatpak"
