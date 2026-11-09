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
- Step 3 polish done (8 November): ALVR's C interface gained `alvr_get_total_prediction_offset_ns` (patch in
  `app/src/main/cpp/alvr/alvr-pocketxr.patch`); the head goes to the PC predicted by ALVR's measured delay (about 90 ms)
  with its turn rate. The PC draws a view 6 % wider on every side at the glasses' sharpness (2144 x 1216 per eye), and
  the warp reads each frame's own view. The PC's ALVR now follows the phone's size (transcoding and emulated headset
  resolution set to Scale 1.0; a SteamVR restart applies it). Mike: "No edges, smooth". 59.5 new frames a second, 89 ms.
- Joy-Cons, zoom and loading (8 November): both Joy-Cons are Quest Touch controllers to SteamVR, and the SteamVR menu and
  buttons work. The PC draws a view 1.3 times wider that the glasses show in the same space (more in view, as a game's
  field of view setting). Mike: "Feels right". Hands sit 7 cm lower and 5 cm out from the default.
- Joy-Con drift: both measured about 0.8 degrees a second, which is why they needed recentring so often. It is only
  measured when a Joy-Con lies still, and was only saved when a game closed cleanly, so it had never been kept. Now it is
  saved as soon as a rest measures it, and each recentre logs how far out the Joy-Con was.
- Loading scene: a SteamVR cold start gives about 5 to 11 s of nothing, then SteamVR's blue room, then its dashboard.
  `pcvr/PcVrLoading.kt` shows a dark room with a blue horizon, fixed in the world, and a card: "Connecting to your PC",
  "Starting SteamVR", "Reconnecting to your PC", with a spinner and a Joy-Con row asking you to set them down (each gets
  a check once its drift is measured). After 1.5 s of steady frames it fades out over 0.8 s. Mike: "Looks great".
- The 20 s freezes: not caught in 15 minutes of recording. Short dips then were the phone (the PC kept 60 a second) while
  it was hot.
- Step 5, game sound (8 November): already working with nothing added. ALVR's client library plays the PC's game sound
  itself (AAudio, out to the glasses as a USB headset), and the PC streamer sends it and mutes the PC's speakers.
  Mike: "Clean sound". The microphone stays off: on Windows it needs VB-CABLE on the PC, only worth it for voice chat.
- Step 7, the tile (8 November): "PC VR" is the first pinned tile in the Space's launcher (`pcvr/PcVrSession.kt`). It
  opens the stream over the paused Space in a sub-window, as OpenXR games do, with the Space's windows kept. Back: the
  phone's "Back to your Space" button (a touch wakes its dark screen for 8 s), or a 2 s hold of Home or Capture, shown
  on the glasses as the game dimming into the loading room with a ring (a press still only recentres). Mike: "Both
  worked". The test copy is named "Pocket XR Test" on the phone; it opens from its own "Open the Space" button after the
  real app is force stopped.
- Left: the PC helper (step 6), so the tile also starts SteamVR; the tile's status (PC off, starting, ready); the
  portal transition; SteamVR hand tracking; the 20 s freezes if they come back.
- Step 6, the PC helper, version 0 (8 November): `pc-helper/PocketXrPcHelper.ps1`, a PowerShell tray program (nothing
  to install on Windows). Port 47610: UDP to be found, TCP for pair, status and start. The PC's user allows a phone once
  (a Yes/No box); its token then lets it start SteamVR. The tile finds it (`pcvr/PcVrHelper.kt`), and the loading card
  says each step: "Allow Pocket XR on your PC", "Starting SteamVR on MIKE". First run, from SteamVR off: tap to SteamVR
  in 11.4 s, including clicking Yes. Mike: "It started". Found on the way: the tray's timer kept ticking inside the
  question box's own loop and opened endless boxes; it now stops while one is open.
- Next for the helper: a proper .exe with an icon and an installer; "Start with Windows" on by default.
- 8 November, evening, "complete all 5":
  - First-time setup in the PC helper (now a C# program built with Windows' own compiler, installing itself and
    starting with Windows): checks Steam, SteamVR and ALVR; trusts the phone; sets ALVR's view, 60 frames a second and
    hand gestures; starts ALVR's dashboard (ALVR needs it to restart SteamVR, and without it waits forever with no
    picture); lifts SteamVR's safe-mode block on ALVR (SteamVR sets it after a few abrupt stops, then says no headset).
  - Heat, measured: at thermal status 3 Android holds the app at exactly 30 frames a second, whatever the decoder hint,
    bitrate or copy pass; cool, 60. Asking the PC for 30 when hot was tried and removed: a different rate makes ALVR
    restart SteamVR, and the game with it. Open: cutting the heat itself.
  - The tile says how the PC is: "PC VR: ready", "PC VR: starting", "PC VR: PC off".
  - Freeze guard: a second with no new frame and the loading room fades in ("Waiting for your PC"), the PC is asked for
    a full frame every 3 s; logged as "PC VR freeze".
  - Portal: PC VR opens as a circle with a blue rim over the live Space, which pauses once it has filled the view, and
    closes the same way.
  - SteamVR hand tracking: the glasses' tracked hands as OpenXR's 26 joints (`core/hands/HandSkeleton.kt`, tested),
    sent for any hand with no Joy-Con in it; ALVR's hand gestures make a pinch the trigger.
- Hands in SteamVR, tuned with Mike (8 November): hand tracking keeps running in PC VR; a Joy-Con set down gives way to
  the tracked hand; the controller is the Space's own hand ray with the Space's pinch as the trigger (ALVR's gestures
  off); ALVR's 11 cm forward controller offset is taken back out; SteamVR's laser needed the pose turned 30 degrees
  (0 and 15 aimed low; 30 "On target"). Mike: pinch works, "perfectly smooth", smoother than the Space's own cursor.
- Heat, 9 November: the phone was charging the whole time (14 % to 21 % while streaming, through the glasses' hub), and
  the 30 frames a second cap comes from the SKIN sensor (45 C, status 3). The battery's charge current is the only power
  reading the app has, and the charger moves it: hand tracking on against off was steady at about 150 mA (0.6 W) less
  charge, twice. The picture size test was void: the PC helper (running outside the Claude app's container, where it
  couldn't be stopped) set ALVR back to full size at each SteamVR restart, so all four runs were full size, and their
  spread (478 to 757 mA) is the reading's noise. Hand tracking now pauses while both Joy-Cons are held. Open: the
  charging itself, and a size test with the phone not charging.
- First-time setup, 9 November, on Mike's PC reset to new (helper gone, phone unpaired and untrusted, ALVR's view and
  frame rate at its defaults, its gestures on, SteamVR's safe-mode block, everything closed): SteamVR 9.9 s after the
  tap, all fixed by the helper. Programs started from the Claude app run in its app container (AppData and HKCU writes go
  to its package), which made the helper's install look broken from inside it; it wasn't.
