# 6DoF head tracking: research and plan

Status: written 10 November 2026. Step 1 (section 5) is built and was checked on the Aura the same day; the rest is
not built yet.

Today Pocket XR is 3DoF. It reads the glasses' motion sensor and turns the windows as the head turns. 6DoF adds
position: lean in to read, step to the side, walk around a window. A tester asked for it on the VITURE Luma Ultra
("Please support Viture Luma ultra for 6dof"), and the README lists it under "Where the community can take it".

This page says, per glasses, where a 6DoF pose can come from, what can be tested, and what to build first.

## 1. The three possible sources

A position needs cameras. A motion sensor alone drifts away within a second. So every route below uses cameras
somewhere. There are three places the tracking can run:

1. **In the glasses, and the glasses give the pose to the phone.** Best case: Pocket XR just reads a position.
2. **In the glasses, and the glasses hold their own picture still.** The glasses lock the whole picture in the room.
   Pocket XR draws a fixed canvas and gets no pose. This is how Steady mode already works on the Aura (in 3DoF).
3. **On the phone.** Pocket XR reads the glasses' cameras and motion sensor and runs visual-inertial tracking (VIO)
   itself.

## 2. Per glasses

| Glasses | Cameras for tracking | Best source | Licence | Can we test? |
| --- | --- | --- | --- | --- |
| VITURE Luma Ultra | Two grey tracking cameras plus a front camera | Route 1: VITURE's own SDK returns a 6DoF pose on Android | SDK is closed. Terms not published | Only through a tester |
| XREAL Aura | Two grey fisheye tracking cameras plus a colour camera | Route 2: the glasses' own Spatial Anchor tracks in 6DoF (checked). Route 3 only on the colour camera | Nothing to license | Yes, Mike has an Aura |
| XREAL One / One Pro with XREAL Eye | One colour camera (the Eye) | Route 2: the glasses' own 6DoF anchor | Nothing to license | Only through a tester |
| VITURE Beast, Luma, Luma Pro, Pro | No tracking cameras | None. 3DoF only | | |
| XREAL One, One Pro, 1S without the Eye; Air family | None (the Air 2 Ultra has two, reachable only through XREAL's Unity SDK) | None. 3DoF only | | |

### 2.1 VITURE Luma Ultra

What VITURE publishes (their XR Glasses SDK page, read 10 November 2026):

- **The SDK.** A C library (`libglasses.so`) with headers. It supports Android (`arm64-v8a` and `armeabi-v7a`). On
  Android it takes the USB device's file descriptor after the normal Android USB permission prompt, the same way
  Pocket XR already opens the Beast. It needs the `CAMERA` permission on Android 14 and later.
- **The Luma Ultra is the only VITURE glasses with 6DoF.** VITURE's table lists it as "3/6DOF" with stereo tracking
  cameras for VIO. Every other model is 3DoF. In the SDK the Luma Ultra is its own family, called Carina.
- **The pose.** Pocket XR would poll `xr_device_provider_get_gl_pose_carina`. It returns position in metres and a
  rotation quaternion, in OpenGL axes (x right, y up, z back). It takes a look-ahead time for prediction and returns a
  status: 0 stable, 1 unstable (just after start, or after a sudden move). VITURE's examples poll at about 120 Hz on a
  background thread.
- **Recenter.** `xr_device_provider_reset_origin_carina` moves the origin (position and turn). Tilt stays on gravity.
  `xr_device_provider_reset_pose_carina` restarts tracking fully, for when it is lost.
- **3DoF or 6DoF** is chosen before start (`xr_device_provider_set_dof_type_carina`). 6DoF is the default.
- **The cameras.** The SDK can also hand out the two grey tracking images (25 a second). We would not need
  them.
- **Where the tracking runs is not stated.** The SDK is large (one open source project that uses it says about 174 MB
  with its demos), which suggests the VIO runs on the phone. CPU use and heat on a Flip 7 must be measured.

Other projects:

- **DroidDeck** has a pull request (Droid-Deck/DroidDeck#1035) adding experimental Luma Ultra 6DoF on Android
  through this SDK. It does not ship the SDK: builds need a local copy. No tester results are posted.
- **UxSpace** (darkclad/uxspace, Apache-2.0) also uses the SDK and says it "must not be committed or redistributed".
- No open, reverse engineered description of the Luma Ultra's 6DoF over USB was found. Writing our own would mean
  guessing VITURE command ids, which this project does not do.

**The blocker is the licence, not the code.** Pocket XR is GPL-3.0. A closed library linked into a GPL app needs:

1. VITURE's written permission to ship `libglasses.so` inside an app on GitHub and Google Play. Their pages give no
   SDK licence. The download is behind a button on viture.com/developer, and the only notice seen is "All rights
   reserved".
2. A GPL "additional permission" (GPL-3.0 section 7) that allows linking the VITURE library. Mike can grant this for
   his own code. Commits from the project's other authors (the "Jarvis worker" and Claude commits) are Mike's work
   done with tools, so this should be his call alone, but he should confirm that.

A cleaner shape: put the SDK in a **separate VITURE add-on app**, like the existing Pocket XR add-on. It owns the USB
device, runs the SDK, and streams the pose to Pocket XR over a signature-protected binder (the add-on already binds
this way). Pocket XR stays pure GPL. The add-on gets its own licence with the exception. VITURE's permission to
redistribute is still needed.

### 2.2 XREAL Aura

What we have measured on Mike's Aura:

- **The glasses send their factory calibration** (command 10015, already used by Pocket XR). It has two grey
  "SLAM" fisheye cameras, 640 x 512, global shutter, about 13.7 cm apart, and one colour camera, 2016 x 1512, with
  full lens models and each camera's exact position and angle against the motion sensor. That is everything a VIO
  needs for calibration, with no calibration step for the user.
- **Pocket XR already reads the Aura's motion sensor** over its USB network at a high rate, with the factory gyro
  and accelerometer calibration.
- **One standard USB camera (UVC) shows up on the phone, and it is the colour camera only.** Read on 10 November 2026
  (Hands, Developer, Read camera formats): one streaming interface with HEVC (2016x1512, 1920x1080, 1080x1920,
  1920x1920, 2880x1600 at 30 fps, 4032x3024 at 15 fps) and MJPEG (1920x1080, 1080x1920 at 30 fps). 2016x1512 is the
  colour camera's size in the factory calibration. There is no grey 640x512 picture, so the tracking cameras are not
  offered as a standard camera. Hand tracking uses the MJPEG 1920x1080 stream.
- **No way to read the glasses' own head pose has been found.** The Steady mode work showed this: when the glasses
  hold the picture, the phone gets no live pose back. So route 1 is closed on the Aura for now.

So the Aura route is **route 3: Pocket XR runs VIO on the phone**, and the open question "can the phone read the
two grey tracking cameras?" now has its answer: **not as a standard camera.** That leaves:

- **Mono VIO on the colour camera.** Possible with what the phone already reads, but worse than stereo: rolling
  shutter, a narrower view, auto exposure, MJPEG or HEVC decode on every frame, and no hardware time link to the
  motion sensor. OpenVINS does mono; Basalt does not.
- The grey cameras may be reachable some other way. We would only use a way we have seen XREAL's own app use on the
  wire (as with 3D switching, command 10274). No guessed command ids.

The VIO engine: **Basalt** (BSD-3-Clause). It is stereo visual-inertial, it is fast, it handles fisheye lenses,
and Monado (already inside Pocket XR for OpenXR) has a ready interface for it. **OpenVINS** (GPL-3.0, so it fits
Pocket XR's licence) is the fallback, and it also does mono. The Aura's fisheye lens model is not one Basalt reads
directly, so its parameters need converting once to a model Basalt has. That is offline maths, not a new
calibration.

Costs to measure: CPU, heat and battery on the Flip 7 while it also drives the glasses display. Basalt runs two
640 x 512 cameras in real time on laptop class CPUs. A phone is tighter.

**The Aura has a 6DoF anchor of its own (route 2).** Checked by Mike on 10 November 2026: with the glasses' own
**Spatial Anchor** setting on and the plain DeX screen showing, leaning in made the screen bigger. The glasses track
position themselves (with their own tracking cameras, which the phone cannot read) and hold the whole picture in place
in the room.

This explains an older result: Spatial Anchor made Pocket XR's normal tracking worse, because the glasses and Pocket
XR both moved the picture. It also gives the Aura a cheap 6DoF mode, built like Steady mode: Pocket XR draws a fixed
picture with no head motion, and the glasses hold it in the room in 6DoF. The cost is the same as Steady mode's:
windows live in the glasses' one canvas, not all around. 

Tried in the Pocket XR Space the same day:

- **With 3D on, it does not work.** When the Space opened, the glasses were in 3D side by side and Pocket XR read
  Spatial Anchor as off. The picture was plain 3DoF.
- **In 2D it works "great"** (Mike): Pocket XR's 3D off, the glasses' Full SBS off, Spatial Anchor on, Pocket XR's
  Head tracking off. The windows stay in place in the room as the head leans and steps aside. The picture is the
  glasses' normal 16:9 screen, so it is smaller than Steady mode's Ultrawide.

- **Ultrawide works too, once Pocket XR stops turning Spatial Anchor off.** Turning Ultrawide on in the glasses'
  menu re-plugs their display; Pocket XR reopened the Space, saw Spatial Anchor on, and turned it off itself (the
  Aura's auto-off in `GlassesDisplayControl`, "Spatial Anchor off (10405 = 0)" at 10:24:02). That first try was 3DoF
  for that reason. With Spatial Anchor turned back on in the menu, Ultrawide held in 6DoF (Mike).

So on the Aura the glasses can hold Pocket XR's picture in 6DoF, wide or 16:9, in 2D (3D side by side turned it off).
**Built: Steady mode in 6DoF** (a Settings switch, Aura only, off by default). Steady mode then turns Spatial Anchor
on, last, instead of off, and the per-link auto-off leaves it on. Tried on the Aura with a signed build on 10 November
2026: "Steady mode on: done (Ultrawide 1, screen size 1.2, Spatial Anchor 1)", Spatial Anchor read back as on, and
Mike confirmed the wide screen and its 3 windows stayed in place in 6DoF. Still to try: how far the head can move
before the picture drifts or resets.

### 2.3 XREAL One and One Pro with the XREAL Eye

- The Eye is one colour camera that clips on between the nose pads. With a firmware update in 2025, the One and One
  Pro gained a **6DoF spatial anchor**: the glasses' X1 chip tracks with the Eye and the motion sensor, and holds the
  picture in the room. It is turned on in the glasses' own menu ("Spatial Display").
- This is route 2. The glasses do not give the pose to the phone. XREAL's SDK (Unity only, Beam Pro and some Galaxy
  S phones) is the only documented way to get the pose, and it does not fit Pocket XR (see `docs/HAND-TRACKING.md`).
- The Eye's video goes over vendor USB, so route 3 is closed too.

What Pocket XR can do: treat it like Steady mode. Pocket XR draws a fixed canvas with no head motion, and the glasses
anchor it with 6DoF. Windows stay in one canvas, but leaning in and moving work. Mostly a docs and settings job.

**Note:** Mike no longer has his XREAL One (since 6 November 2026). Anything here needs a tester with a One or One
Pro and an Eye.

## 3. What Pocket XR itself needs (all routes 1 and 3)

The app is built around a rotation only. To use a position:

- **A pose type.** `HeadTracker.renderPose` and `gamePose` return a `Quat` today. They need a pose with a position,
  plus a "position is good" flag. The 3DoF sources keep returning a zero position.
- **Prediction and smoothing on position.** `HeadPrediction`, `DrawSmoothing` and `RestLock` handle rotation only.
  The first version should not smooth position at all, and should use the source's own prediction where it has one
  (the VITURE SDK does).
- **The renderer.** The view matrix gets the head position, and each eye is offset by half the eye distance. The
  windows already live at a distance in front, so this is a translation in the same matrix.
- **Near limit.** A user can now lean into a window. Clip or fade windows closer than about 25 cm.
- **Recenter** resets position as well as turn.
- **Lost tracking.** When the source says unstable or lost, hold the last position and keep rotating from the motion
  sensor (3DoF), then ease back when it returns. Never jump.
- **The tracking mode.** "Space locked (6DoF)" already exists, greyed out ("6DoF, not on these glasses"). It becomes
  available while a 6DoF source is live.
- **OpenXR.** `OpenXrHeadFeed` passes the position to our Monado driver, so games get real 6DoF.
- **Glasses report and logs** say which source is used, the rate, and how often it went unstable.

To keep clear of the 1.3.3 work in progress, this stays out of `SteadyArrange.kt`, `SpaceRecorder.kt`,
`TrackpadMenu.kt` and the settings files until 1.3.3 is merged.

## 4. How to test

- **Without 6DoF glasses.** A debug-only replay source feeds a recorded or scripted pose path through the same code
  (like the hand replay in `docs/EMULATOR_TESTING.md`). That tests the renderer, recenter, lost tracking and OpenXR on
  the emulator and on the Flip with any glasses.
- **Aura, offline first.** Record the grey camera images and motion data to a file on the phone, then run Basalt on a
  PC on the recording. If tracking is good on the PC, port it. If it is not, we learn that before writing Android
  code.
- **Simple accuracy check** (any glasses): walk a 1 m tape forward and back, then a 2 m square. Read the end
  position against the start. Also stand still for a minute and read the drift.
- **VITURE Luma Ultra.** A tester build with the add-on, a pose log (rate, status, position), CPU and temperature,
  and the accuracy check above. The tester who asked is the first person to ask.
- **One / One Pro with Eye.** A tester tries the glasses' 6DoF anchor with Steady mode and reports.

## 5. Recommended plan

**Step 0: answers before code (a day, mostly waiting).**

- Mike: does the Aura's own menu have a 6DoF anchor option?
- Pocket XR: add the Aura camera's picture formats to the Glasses report (read only, standard USB descriptors). One
  report from Mike's Aura answers "can the phone read the grey cameras?".
- Mike: write to VITURE developer support asking for the SDK licence, and whether an open source GPL app (or a
  separate add-on) may ship `libglasses.so` on GitHub and Google Play.
- Ask the Luma Ultra tester whether they can run test builds and send logs.

**Step 1: the pose plumbing (testable now, no new glasses).** Section 3, with the replay source. The Space and
OpenXR games move with a scripted position. Nothing changes for 3DoF users.

Done on 10 November 2026. On the Flip 7 with the Aura, a debug build's scripted path moved the launcher about 30 cm
closer and back in Space locked, and the lost tracking path froze and then glided back with no jump (Mike, wearing the
glasses). Not done yet: the near limit for windows closer than about 25 cm, the phone's tracking mode list offering
Space locked without a restart, lining a real source's axes up with Pocket XR's recenter (with Step 2), and an OpenXR
game run with a position. To try it on a debug build:

```
adb shell am broadcast -a com.hayeco.flipspaces.debug.POSITION --es scenario lean --ei repeat 3 --ez lock true
```

The scenarios are `sway`, `lean`, `square` and `lost`. `lock` holds the Space in Space locked while the path plays.

**Step 2: the first real 6DoF source.** Whichever unblocks first:

- **VITURE Luma Ultra**, if VITURE allows redistribution. Fastest to a working result: VITURE does the tracking.
  Shape: the VITURE add-on (section 2.1).
- **Aura**, if the grey cameras are readable. The offline recording test first, then Basalt on the phone. Slower,
  but Mike can test it himself and it is all open source.

**Step 3: One / One Pro with Eye.** Docs and a Steady style canvas for the glasses' own 6DoF anchor. Small, but needs
a tester.

**My recommendation:** do Step 0 and Step 1 now. Step 1 is needed by every route and needs no answers. Then let the
answers from Step 0 choose between VITURE and the Aura for Step 2. If VITURE says no, the Aura route is the only one
Pocket XR can own end to end.

## Decisions for Mike

1. Contact VITURE about the SDK licence? (Needed for the Luma Ultra in any form.)
2. If VITURE agrees: the SDK in a separate add-on app (recommended), or a GPL exception in Pocket XR itself?
3. Spend time on phone-side VIO for the Aura (a larger job, maybe several weeks), if Step 0 shows the grey cameras
   are readable?

## Sources

- VITURE XR Glasses SDK documentation: https://www.viture.com/developer/glasses-sdk/glasses
- VITURE Unity XR SDK (Luma Ultra 6DoF, needs VITURE's neckband): https://www.viture.com/developer/unity-sdk/unity
- DroidDeck pull request 1035 (Luma Ultra 6DoF on Android): https://github.com/Droid-Deck/DroidDeck/pull/1035
- UxSpace (uses the VITURE SDK, notes it is not redistributable): https://github.com/darkclad/uxspace
- XREAL SDK 3.1.0 release notes (6DoF on the One series with the Eye): https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.1.0
- XREAL One Pro product page (3DoF and 6DoF anchor): https://www.xreal.com/one-pro
- XRLinuxDriver device table (Luma Ultra through VITURE's closed SDK): https://xrlinuxdriver.readthedocs.io
- Basalt VIO (BSD-3-Clause): https://gitlab.com/VladyslavUsenko/basalt
- OpenVINS (GPL-3.0): https://github.com/rpng/open_vins
- Pocket XR's own measurements: the Aura's factory calibration (command 10015), `docs/HAND-TRACKING.md`,
  `docs/GLASSES.md`.
