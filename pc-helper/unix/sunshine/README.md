# Pocket XR PC Screens for Linux

Sunshine v2026.914.233613 with two patches, packaged with the Pocket XR setup and PC helper as one Flatpak
(`com.hayeco.pocketxr.PCScreens`). It gives a Linux computer on KDE Plasma (Wayland) what Apollo gives Windows: up to
three PC screens, each its own virtual screen at the phone window's size.

## The patches

`0001-kwin-virtual-output.patch` (config.h, config.cpp, kwingrab.cpp; 67 lines added). New option
`kwin_virtual_output` (off by default). With `capture = kwin`, each stream asks KWin for a new output of the client's
width and height (`zkde_screencast_unstable_v1.stream_virtual_output`, protocol version 2 or later), named
`sunshine-<port>`, so several Sunshines can run side by side. KWin removes the output when the stream ends.
No portal, no dialog, no `kscreen-doctor`. It applies to Sunshine's own source with `git am`, so it can be rebased on a
new release and offered upstream.

Why not the portal: stock Sunshine's portal capture asks for monitors only (`SOURCE_TYPE_MONITOR`), and a virtual
monitor made by another program is never offered to it. KDE's portal can make a virtual monitor (type 4), but at a
fixed 1920x1080 that then has to be resized with `kscreen-doctor`, with a dialog the first time.

KWin lets a sandboxed app use its screencast protocol when the app's own desktop file says
`X-KDE-Wayland-Interfaces=zkde_screencast_unstable_v1` (Sunshine's own permission file lands inside the sandbox,
where KWin does not look). That is why the app ships `com.hayeco.pocketxr.PCScreens.desktop`.

`0002-audio-sinks-per-port.patch` (audio.cpp, about a dozen lines): a Sunshine on another port than the default
names its null sinks `sink-sunshine-<kind>-<port>`. Before, every Sunshine on one computer used the same sinks, and the
first whose session ended removed them, cutting the others' sound. The newest stream takes the sound (it makes its sink
the default), which matches the phone's "latest PC screen plays the sound".

## Build

On a Linux computer with Flatpak (the Steam Deck works, in Desktop Mode):

```sh
flatpak install --user flathub org.flatpak.Builder org.kde.Sdk//6.11 org.freedesktop.Sdk.Extension.node20//25.08
bash build.sh ~/pxr-flatpak-build
```

`build.sh` checks out Flathub's Sunshine manifest repository at `675426a` (Sunshine v2026.914.233613), puts
`com.hayeco.pocketxr.PCScreens.yml`, the patches and the Pocket XR files next to it, builds with
`org.flatpak.Builder`, and writes `PocketXR-PC-Screens.flatpak`. The bundle fetches its runtime
(`org.kde.Platform//6.11`) from Flathub when installed.

The manifest is Flathub's with these changes: own app id, launcher (`pocketxr-setup`) and AppStream file (Discover's Launch button needs
its launchable), no CUDA (so no NVIDIA
encoding), no tests, `BUILD_WERROR` off, Sunshine's own desktop and metainfo files removed.

## New Sunshine release

1. In Sunshine's repository: `git checkout <new tag>`, `git am 0001-*.patch 0002-*.patch`, fix any conflict,
   `git format-patch -2` back over the patch files.
2. In `build.sh`: the Flathub commit for that release. In the manifest: `BUILD_VERSION`, `COMMIT`, the git `commit`,
   and any module changes from Flathub's manifest.

## What the app does when opened

`pocketxr-setup` copies `pocketxr-pc-screens.sh` and `pocketxr_pc_helper.py` to `~/.local/share/pocketxr` and runs
the setup outside the sandbox (`flatpak-spawn --host ... --gui`). See the top of `../pocketxr-pc-screens.sh`.
