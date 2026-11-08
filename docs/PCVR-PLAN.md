# PC VR in the Space: step 1, one app and a PC VR tile

Private until Mike says otherwise. Written 8 November 2026.

## Goal

A "PC VR" tile in the Space launcher. One tap and you're in SteamVR Home on the glasses, with Joy-Cons as your hands. No
second app on the phone, nothing to start by hand on the PC after a one-time setup, and no streaming words on screen.

## The design choice

ALVR's client library (`alvr_client_core`, MIT licence) has a C interface for everything a client does: connect
(`alvr_initialize`, `alvr_poll_event`), send head and controller tracking (`alvr_send_tracking`, `alvr_send_view_params`),
hardware decoding (`alvr_create_decoder`, `alvr_get_frame`) and frame reports (`alvr_report_compositor_start`,
`alvr_report_submit`).

**Embed it, and draw its frames with Pocket XR's own Space renderer.** Monado and the ALVR app drop out of the loop for PC
VR:

| | Today | Embedded |
| --- | --- | --- |
| Apps to install | Pocket XR plus the ALVR client | Pocket XR |
| Frame path | ALVR decodes, ALVR draws into Monado, Monado composites to the glasses | ALVR decodes, the Space draws it on the glasses |
| Head pose | Pocket XR, then Monado's prediction | Pocket XR's own, with the smoothing and late warp the Space has |
| Pacing | Two loops: ALVR's on Monado's guessed clock | The Space's frame clock, on the glasses' real refresh |
| Glasses, Beat Saber (7 Nov) | 37 to 46 new frames a second | To measure; the loop that drops to 30 is gone |

Monado stays for native Android OpenXR apps. The Joy-Con code (`JoyConPose`, `JoyConArm`, `JoyConClock`, `JoyConStick`) is
runtime-free, so it feeds ALVR directly.

The other way, a hidden ALVR app started by the tile, takes days, not weeks. But it keeps two apps and the frame path that
runs at half speed on the glasses.

## Steps

0. **Bring the private copy to 1.2.4.** It is on 1.2.0; public main has moved on (1.2.4, ctdf's fixes, PR 60's heat
   cuts once merged). Merge first, so the new work starts from today's app.
1. **Tools.** Install Rust and `cargo-ndk` on the PC (needs Mike's OK). Build `alvr_client_core` for arm64 at the nightly
   that the PC streamer runs, so the protocols match.
2. **Proof.** A debug activity that connects to the PC streamer and shows its frames flat in a window. Done when the lobby,
   then SteamVR Home, appears.
3. **Stereo in the Space.** The Space draws each eye's frame with its own projection, the 3D distance correction (as
   games get since 7 Nov) and the late warp from the frame's own pose. It sends head poses at ALVR's predicted times and
   the glasses' real refresh. Measure frames shown, delay and heat on the glasses against today's path.
4. **Joy-Cons and buttons** to ALVR, with haptics back to rumble later.
5. **Audio and microphone** through ALVR's own audio module, out of the glasses.
6. **PC companion, version 0.** A small Windows tray program that answers the phone on the local network: "start SteamVR"
   and "is it ready". It starts with Windows. Pairing: the first tap shows a code on the PC to confirm (ALVR's trust
   step).
7. **The tile.** "PC VR" in the launcher: tap, and the Space fades into SteamVR Home. Status on the tile: PC off, starting,
   ready. Home on the Joy-Con or a long press on the trackpad brings the Space back, windows where they were (the game
   pause from 7 Nov).

Later steps from the design: game tiles from the PC library, the portal transition, the phone as a controller, heat
coaching.

## Risks

- **The Rust build on Windows.** ALVR builds its client with its own `xtask`. Its Android build may need Linux-only
  tools. If so, use WSL.
- **Protocol match.** The client library must match the PC streamer's version (nightly dev14). Pin both.
- **Decoder output in GL.** `alvr_get_frame` gives a hardware buffer. The Space already samples hardware buffers for its
  windows (`ImageReader`), so this should be familiar ground, but it is the first thing the proof checks.
- **Size.** The library adds a few MB to the APK.

## Licence

ALVR is MIT. Keep its notice in the app's licences screen. Nothing here goes public until Mike says so.

## Progress, 8 November 2026

- Step 0 done: the private copy is merged up to 1.2.4.
- Step 1 done: WSL Ubuntu already had Rust; added the Android target, `cargo-ndk` 3.5.4 and `cbindgen`, and Google's
  NDK r27c for Linux. `alvr_client_core` (21.0.0-dev14, matching the PC streamer) builds with
  `cargo ndk -t arm64-v8a --platform 28` after `git submodule update --init --depth 1 openvr`. It needs
  `libc++_shared.so` beside it, and has no soname, so CMake links it with `IMPORTED_NO_SONAME`.
- Step 2 done: `app/src/main/cpp/pocketxr_alvr.cpp` and `pcvr/AlvrBridge.kt`, tested with debug `PcVrTestActivity`. The
  PC trusted the phone (shown as "Pocket XR", `9784.client.local.`) through ALVR's own API, and SteamVR Home showed in
  stereo on the phone at 60 new frames a second.
- Found on the way: ALVR's own drawing (`alvr_*_opengl`) makes a context of its own current on the caller's thread,
  so the bridge doesn't use it. With foveated encoding off, each decoded frame (both eyes side by side) is bound
  straight to an external texture in Pocket XR's own context: no extra drawing pass. ALVR needs an Activity at start, to
  ask for the microphone permission, and every DecoderConfig after the first must be ignored.
- Known gap: when no new frame comes, the previous buffer has already gone back to the decoder. Copy it once, or only
  release it when the next frame arrives.
- Step 3, first part, done on the glasses (8 November): each new frame is copied once into Pocket XR's own texture,
  then every refresh draws it turned from the pose the PC drew it for to the head now (rotational late warp).
  Shown as a Presentation, because the glasses' desktop mode makes an activity a floating window. Mike: one picture in
  both eyes, "looks right". ALVR's figures: 58.9 new frames a second, about 100 ms total. The old path gave 37 to 46 on
  the same glasses.
- Next in step 3: ask the PC for a slightly larger view, so the warp has picture to turn into (a black band showed at
  the top when looking down); predict with ALVR's own average delay instead of a fixed 60 ms (add a call to the C API);
  send the head's angular velocity. Then the Space draws it instead of a test screen.
