# Hand tracking in Pocket XR

This page has two parts. Section 1 says how hand tracking works in the app now. The rest is the research note written
in October 2026, before hand tracking was built, on how a phone app (Pocket XR on a Galaxy Z Flip 7) could let the
user grab, move and resize windows on XREAL glasses by hand. That note covers the public XREAL SDK, Android XR /
Jetpack XR, generic camera routes and how the input reaches other apps' windows. It comes from public docs, release
notes, press and open source project pages only. It is kept for the reasoning behind the design; where it differs from
section 1, section 1 is what the code does.

Markers used in the note: **Public API** (a public Android API), **Adb** (needs an adb or shell command), **Shizuku**
(needs Shizuku's shell identity), **Verify** (still to be checked on the device when it was written).

The note was researched by web search; its facts come from search result summaries of the official pages linked in
section 9.

## 1. What the app does now

- **Which glasses.** Hands need a camera in the glasses. The XREAL Aura's camera turned out to be a standard USB Video
  Class (UVC) camera that the phone can read over USB, so the route in section 5a works on the Aura. Glasses without a
  camera have no hand tracking yet. The XREAL One and One Pro can take the XREAL Eye camera add-on, so hand tracking
  is possible there too, but the Eye does not stream as a standard camera (section 3), so it needs its own camera
  route. That work is open for the community (help wanted); see `docs/GLASSES.md`.
- **Providers** (`HandInputManager`, core's `HandProviders`): the XREAL SDK provider is always reported unavailable
  (section 3 says why), the glasses camera is used when it is there, and the phone trackpad always works. All of them
  feed the same pointer, so hands and the trackpad can be used together.
- **Camera.** `GlassesUsbAccess` finds the glasses (the Aura first, then any XREAL device with a video interface, then
  any USB camera, which helps testing) and asks for access with Android's own USB prompt. `UvcCameraSession` reads the
  camera through Android's USB host API with only standard UVC requests, nothing vendor specific. `HandTrackingService`
  is a foreground service of type camera that keeps it running.
- **Models.** By default MediaPipe's palm detector and hand landmark models run on the Exynos 2500's NPU through LiteRT
  (`NpuHandEngine`, 0.5.43; on the Flip 7 they take about 3 ms each there, against 46 to 55 ms each on the CPU). With
  the NPU switched off in the Developer card, MediaPipe Hand Landmarker runs on the GPU, falling back to the CPU
  (`HandLandmarkerRunner`). The hand landmarker model is downloaded once from Google (`HandModelStore`).
- **Aiming.** Core's `HandRay` treats the glasses camera as a pinhole camera and aims a ray from the camera through a
  steady point on the hand's knuckles, so a pinch does not move the cursor. A ray from an estimated shoulder is an
  option. The head pose at each frame's capture time places the ray in the room, so a still hand keeps its cursor while
  the head turns. There is no aim calibration (1.6.2 removed the "pinch on the dots" one).
- **Pinch.** Core's `HandsRecognizer`, `PinchDetector` and `PinchLock`: the pinch is measured on MediaPipe's world
  landmarks, the aim locks where the cursor was before the fingers started closing, the press threshold is learnt per
  hand, and a pinch is a tap until it is held still past its learnt tap limit. One hand is tracked by default; two
  hands are an option, and pulling two pinching hands apart scales a window.
- **Start.** After the first Start hands, hands start by themselves whenever the Space opens on the glasses. The
  notification's Stop button stops them until the next plug-in.

Debug builds can replay scripted hands through the same pipeline on an emulator: see `docs/EMULATOR_TESTING.md`.

## Research summary (October 2026)

- **The XREAL SDK is not a usable route for Pocket XR.** It is Unity only (no native Android or
  OpenXR API), its phone support is Beam Pro plus Samsung Galaxy S24 and later flagships (no Z
  Flip on the list), hand tracking works only with the Air 2 Ultra (not the One / One Pro), and
  hand data reaches only the Unity app that owns the glasses display. It cannot serve a system
  cursor over other apps' windows.
- **Jetpack XR hand tracking runs only on Android XR devices** (Galaxy XR headset, XREAL Aura's
  compute puck). A Flip with ordinary glasses on DisplayPort is not an Android XR device, so
  `Hand.left(session)` has nothing to talk to.
- **XREAL Aura.** Public material described Aura as glasses for its own Android XR compute puck,
  which does the hand tracking itself (section 4). In our own tests the Aura glasses plug straight
  into the Flip and show up as a display and USB devices, including a standard camera (section 1).
- **Most of the glasses' cameras are not standard webcams.** On the One Pro with XREAL Eye, the
  only open source demo found that reads the camera depends on code from XREAL's own app and on
  vendor commands, so Pocket XR does not use it. The Air 2 Ultra's tracking cameras are reached
  only through XREAL's software, and XREAL says it does not give developers raw 3D sensor data.
- **Route recommended at the time:** MediaPipe Hand Landmarker running on the Flip's own camera,
  with the Flip standing half folded (Flex mode) on the desk, driving the Pocket XR pointer. Gestures are
  turned into touches on the glasses display by the existing Pointer AccessibilityService
  (`dispatchGesture` with `setDisplayId`, continued strokes for drags). Shizuku gives exact
  window bounds where it is available. The trackpad stays as the always-working fallback, and a
  glasses camera is used instead if one appears as a standard Camera2 or UVC device. That is what
  happened with the Aura, so the app uses the glasses camera (section 1).

## 2. Which glasses can do what

| Glasses | Cameras | Hand tracking | Plugs into the Flip? |
|---|---|---|---|
| XREAL Air 2 Ultra | Two world-facing tracking cameras | Yes, through XREAL SDK (Unity) on Beam Pro or Galaxy S24+ class phones | Yes, DP alt mode |
| XREAL One / One Pro | None built in; optional XREAL Eye (12 MP RGB) | No. SDK 3.1.0 added 6DoF with the Eye, not hands | Yes, DP alt mode |
| ROG XREAL R1 | Display glasses | No | Yes, DP alt mode or Thunderbolt |
| XREAL Aura | Three: one RGB in the bridge, two side sensors for hand tracking | Yes, by Android XR on the puck | Public material said no (glasses into the puck). In our tests, yes: display, USB network and a standard UVC camera |

"XREAL 1 Ultra" did not turn up in any XREAL listing, docs or press. It turned out to be the display name the Aura
glasses report to the phone.

The Galaxy Z Flip 7 is the first Flip with wired video out (DP alt mode over USB-C); older Flips
and the Flip 7 FE have none, which is why old XREAL forum posts say Flips do not work.

## 3. The XREAL SDK for Android

- **Current version:** XREAL SDK 3.1.0 (release notes dated 25 November 2025). It added 6DoF on
  the One series with XREAL Eye, a `Scanning` value in `NotTrackingReason`, automatic logcat
  capture, and a fix for hand tracking data out of sync after pause and resume. It was tested
  on Beam Pro and Galaxy S25. SDK 3.0.0 was the big rewrite onto Unity's XR stack
  (XR Plugin Management, AR Foundation, XR Interaction Toolkit, XR Hands) and moved hands from
  23 joints to the 26 joint OpenXR layout. **Verify** on the download page that nothing newer
  has shipped.
- **Engine:** Unity only (2021.3, 2022.3 or 6000.0 LTS with Android Build Support). XREAL says
  the SDK does not support OpenXR, and there is no Java / Kotlin API for a normal Android app.
- **Phones:** "Beam Pro or a supported Android phone; currently only Samsung S24 and subsequent
  flagship series." A Z Flip 7 is not named. Whether "flagship" covers the Z series is
  unclear; treat the Flip as unsupported.
- **Hand tracking glasses:** Air 2 Ultra only. The One series (including One Pro with Eye) and
  Air / Air 2 / Air 2 Pro have no hand tracking in the compatibility table.
- **How it gets camera and IMU data:** client / server. XREAL's ControlGlasses app (on phones;
  built into Beam Pro's system) is the server that talks to the glasses over USB, runs tracking
  and hand tracking, and launches SDK apps into 3D mode. Apps built with the SDK are clients and
  receive poses and hand joints, not frames. XREAL says developers get no access to the 3D
  environment sensor data, for privacy; the RGB camera on supported glasses has its own API.
- **Licence:** downloading the SDK means accepting the XREAL APIs Terms of Service and Privacy
  Policy, and apps must disclose what the SDK collects. The SDK is free to download. The search
  results did not show clauses about personal versus commercial use or redistribution. The
  blockers are Unity, the phone list and the client model, not the terms.

Why it does not fit Pocket XR even with an Air 2 Ultra: an SDK app is a full screen Unity
renderer that owns the glasses. Hand data only exists inside that app, so it cannot drive a
cursor over YouTube or Chrome freeform windows on the same display. Pocket XR would have to
become a Unity app rendering other apps as textures, which Android does not allow for third
party apps without system privileges.

Not used: the open source demo `Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android` shows the
One Pro Eye streaming H.265 over UVC bulk transfers (VID 0x3318, PID 0x0436), but it depends on
code from XREAL's own app, so Pocket XR does not use it. The community Linux drivers
(`ar-drivers-rs`, XRLinuxDriver, Monado's Xreal Air driver) cover IMU and display modes, not a
camera path, and are GPL / mixed licence desktop code.

## 4. Android XR / Jetpack XR

- **Hand API:** ARCore for Jetpack XR. Turn on `HandTrackingMode.BOTH` in the session config,
  then read `Hand.left(session)` / `Hand.right(session)` state flows of OpenXR joint poses.
  Needs `android.permission.HAND_TRACKING`. ARCore for Jetpack XR, SceneCore and XR Runtime
  reached beta in August 2026. **Public API**, on Android XR devices only.
- **Where it runs:** devices running Android XR (Galaxy XR headset, XREAL Aura's puck). The
  runtime and the camera pipeline live in the XR device's OS. A Flip 7 on One UI 8 is a normal
  phone; plugging DP glasses into it does not create an XR session, so these APIs are not
  available there.
- **Jetpack Projected (Developer Preview 3, December 2025; DP4 May 2026):** a phone app can
  project UI to Android XR audio and display glasses and use a projected `Context` to reach the
  glasses' camera, sensors and mic. This is for Android XR glasses paired with a phone (Google's
  AI glasses class), not for XREAL DP glasses, and the docs talk about touchpad input, not hand
  tracking. **Verify** the device list before planning on it.
- **XREAL Aura:** Android XR on the puck (Snapdragon Reality Elite, 12 or 16 GB). The glasses
  connect to the puck; the puck's second USB-C port takes power and DisplayPort input from a
  laptop, handheld or phone. Ships in the US mid November 2026 from $1,279.
  - With the puck, **hand driven window move and resize is already the system behaviour of
    Android XR**, for Android XR apps.
  - A phone plugged into the puck's DP input shows up as one flat screen. DisplayPort carries
    video only, and nothing found says Aura forwards hand pinches back to the source device.
  - Pocket XR does not use the puck: the glasses plug straight into the Flip, and the app does
    its own hand tracking with the glasses camera (section 1).

## 5. Generic routes for a phone app

### 5a. Glasses camera as a standard camera (only if it exists)

If a pair exposes a camera as a standard UVC device, two public paths exist:

- Camera2 external cameras: Android's external camera HAL lists UVC webcams with
  `LENS_FACING_EXTERNAL`, if the vendor built it in (`android.hardware.usb.host` and kernel UVC
  support). AOSP says it is meant for light use, not AR. **Verify** on the Flip: list cameras
  with `CameraManager` while the glasses are attached.
- User space UVC: `UsbManager` permission plus libusb / libuvc through the device's file
  descriptor (as in saki4510t/UVCCamera and AndroidUSBCamera). Check the device descriptor for a
  Video class (0x0E) interface first.

The known XREAL cameras need vendor commands before they stream (section 3), so expect this to
come up empty. Pocket XR should still probe at start up and use a glasses camera if one appears,
because a head mounted camera gives the best mapping (what you see is where your hand is). The
Aura's camera turned out to be a standard UVC camera, read with user space UVC (section 1).

Frames then go to MediaPipe Hand Landmarker, same as 5b.

### 5b. The Flip's own camera plus MediaPipe (recommended at the time, not built)

- **MediaPipe Hand Landmarker** (`com.google.mediapipe:tasks-vision`, `HandLandmarker` in
  `LIVE_STREAM` mode): 21 landmarks per hand, image and world coordinates, handedness, up to N
  hands. Google lists about 12 ms GPU latency for the full model on a Pixel 6; in video and
  live stream mode it tracks from frame to frame and only reruns palm detection when tracking
  is lost. Apache 2.0, runs fully on device. **Public API**
- **Camera:** CameraX `ImageAnalysis` at 640x480 or 720p, 30 fps (60 if the sensor offers it),
  `STRATEGY_KEEP_ONLY_LATEST`.
- **Posture:** Flip 7 half folded on the desk (Flex mode). The inner selfie camera on the top half
  looks at the space between the phone and the user, so hands held a little above the desk are in
  view. The bottom half stays a trackpad, with a small camera preview and a "hand seen" light.
  Foldable posture comes from Jetpack WindowManager `FoldingFeature`.
- **Mapping:** the camera is not on the head, so pointing at the glasses' picture cannot be
  absolute. Map a virtual "air pad" (a box in the camera image) to the glasses display,
  relative like a trackpad: hand movement times a gain with pointer acceleration, a clutch (open
  hand = cursor moves, fist or hand out of view = cursor stays). Drive the cursor from the
  midpoint of thumb tip and index tip, or the index knuckle (landmark 5), which barely moves
  while pinching; the index tip jumps.
- **Pinch:** distance from thumb tip (4) to index tip (8) divided by hand size (wrist 0 to middle
  knuckle 9) so it works at any distance. Hysteresis: pinch starts below about 0.25 and ends
  above about 0.4 (tune on device). Smooth positions with a One Euro filter. Ignore the first
  frame or two after a pinch starts so the cursor does not jerk.
- **Permissions and lifetime:** `CAMERA`, plus a foreground service with type `camera` started
  while Pocket XR is visible (Android 11+ blocks camera use from the background). The home
  activity on the glasses counts as visible. The privacy dot will show the whole time; that is
  expected.
- **Budget:** about 33 ms per frame at 30 fps, plus 10 to 25 ms inference, plus gesture dispatch.
  Roughly 60 to 90 ms hand to cursor; fine for window moves, a bit loose for fine clicking.

### 5c. The phone as trackpad (fallback)

The phone's trackpad needs no camera and works with every pair. Hand tracking reuses its cursor,
hit testing and injection, so hands are an extra input source for the same pointer, not a
separate system.

## 6. Gestures

All gestures act at the cursor on the glasses display. Window bounds come from
`AccessibilityService.getWindowsOnAllDisplays()` for the glasses display id and
`AccessibilityWindowInfo.getBoundsInScreen`. Hit zones (sizes in glasses dp, tune on device):

| Where the pinch starts | Pinch and release quickly | Pinch, hold, move |
|---|---|---|
| Title bar (top caption strip, about 40 dp; **Verify** One UI's freeform caption height) | Focus the window | Move the window |
| Corner (24 dp square inside each corner) or edge (12 dp strip) | Nothing | Resize from that corner or edge |
| Inside the window | Tap | Drag or scroll (pinch drag = touch drag) |
| Pinch held 600 ms without moving | | Long press |

Feedback: the cursor changes shape over each zone (move arrows, resize arrows, hand), and Pocket
XR draws an outline of the target bounds during a move or resize so the user sees the result
before the window catches up.

## 7. Injecting the input into other apps

Pocket XR's own surfaces (home, dock, overlays) take hand events directly in process. Other apps'
windows need one of these:

1. **AccessibilityService gestures (baseline, no privileges beyond enabling the service).**
   **Public API**
   - `dispatchGesture(GestureDescription, callback, handler)` with
     `GestureDescription.Builder.setDisplayId(glassesId)` (API 30) sends touches to the glasses
     display. Needs `android:canPerformGestures="true"`.
   - Tap: one short stroke. Long press: one stroke held about 600 ms.
   - Live drags (move, resize, drag in content): `StrokeDescription(path, start, duration,
     willContinue = true)` and then `stroke.continueStroke(...)` (API 26) for each new segment,
     dispatched from the previous gesture's `onCompleted`. Keep segments short (16 to 33 ms) so
     the window follows the hand; end with `willContinue = false` when the pinch releases. If a
     continued stroke is cancelled (another touch, a system gesture), restart from the current
     cursor position.
   - Moving a window is a drag on the system caption bar; resizing is a drag on the window
     corner or edge. This uses One UI's own freeform handling, so it works for any app.
   - Clicks can also be done without touches: find the node under the cursor and call
     `performAction(ACTION_CLICK)`. Useful when an app ignores injected touches.
   - Risks (**Verify** on One UI 8): gesture injection on an external display, the latency of
     continued strokes, and whether the freeform caption reacts to injected drags.
2. **Shizuku (optional, shell identity).** **Shizuku**
   - Exact move and resize by task id through the hidden task manager calls; hand moves then set
     bounds directly instead of dragging the caption, which is
     smoother and works even if the caption is hidden.
   - `input -d <displayId> tap|swipe|motionevent ...` as a backup touch injector if accessibility
     gestures are blocked on the external display.
   - Hidden APIs change between releases, so this stays behind a feature check.
3. **What Pocket XR can do itself without either:** choose launch bounds when it starts an app
   (`ActivityOptions.setLaunchDisplayId` and `setLaunchBounds`). There is no public API for one
   normal app to move another app's window after launch, which is why 1 and 2 exist.

## 8. Recommended plan (as written; section 1 has what was built)

1. Trackpad, cursor overlay, Pointer AccessibilityService with tap, long press
   and continued stroke drags on the glasses display. Hand tracking depends on all of it.
2. Add a `HandInput` source behind the same pointer interface: CameraX + MediaPipe Hand
   Landmarker on the Flip's inner camera in Flex mode, relative mapping, pinch with hysteresis,
   One Euro filter. Keep the pure parts (pinch state machine, filter, hit zones, gesture to
   stroke planning) in `core` so they are unit tested on the JVM.
3. Window moves and resizes via caption and corner drags; switch to Shizuku exact bounds when
   Shizuku is running.
4. At start up, probe for a glasses camera through Camera2 `LENS_FACING_EXTERNAL` and USB Video
   class interfaces; if one exists, offer it as the hand camera.
5. For the Aura with its puck: test whether the puck sends any input back to a DP source.

Do not use: the XREAL SDK (Unity only, phone not supported, no hands over other apps), any code
taken from XREAL's own apps, or vendor camera commands.

## 9. Sources

XREAL SDK and devices
- XREAL SDK docs home: https://docs.xreal.com/
- Hand Tracking: https://docs.xreal.com/Input%20and%20Interactions/Hand%20Tracking
- Compatibility table: https://docs.xreal.com/XREALDevices/Compatibility
- Getting Started: https://docs.xreal.com/Getting%20Started%20with%20XREAL%20SDK
- FAQ: https://docs.xreal.com/Frequently%20Asked%20Questions
- SDK 3.0.0 release note: https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.0.0
- SDK 3.1.0 release note: https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.1.0
- ControlGlasses guide: https://docs.xreal.com/Tools/ControlGlasses
- SDK download and terms: https://developer.xreal.com/download/
- XREAL glasses list: https://docs.xreal.com/XREALDevices/XREAL%20Glasses
- ROG XREAL R1 connection: https://tutorials.xreal.com/docs/glasses/r1/first-use/connect-device/
- Forum, Air 2 Ultra hand tracking: https://community.xreal.com/t/how-do-you-invoke-hand-tracking-when-using-the-air2-ultra/6149
- Forum, Samsung compatibility (old Flips have no DP): https://community.xreal.com/t/compatibility-of-samsung-smartphones-with-nreal-glasses/3545

XREAL Aura
- Product page: https://www.xreal.com/aura
- Price and shipping: https://www.prnewswire.com/news-releases/xreal-aura-starts-at-1-279--bringing-wired-xr-glasses-with-android-xr-to-customers-this-year-302900445.html
- Puck, not phone, powers Aura: https://www.androidauthority.com/xreal-project-aura-tethered-puck-3566760/
- Puck ports and DP in: https://www.androidauthority.com/xreal-project-aura-computing-puck-android-xr-3669197/
- Tethered compute: https://www.uploadvr.com/xreals-project-aura-android-xr-tethered-compute/
- Cameras: https://www.engadget.com/2177998/android-xr-xreal-project-aura-hands-on/
- Specs: https://www.gsmarena.com/xreal_aura_glasses_unveiled_with_android_xr_and_the_new_snapdragon_reality_elite-news-73315.php
- Inputs (hands, voice, Bluetooth): https://vrarwiki.com/wiki/Xreal_Aura

Android XR / Jetpack XR
- Hands with ARCore for Jetpack XR: https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/hands
- `Hand` reference: https://developer.android.com/reference/androidx/xr/arcore/Hand
- ARCore for Jetpack XR releases: https://developer.android.com/jetpack/androidx/releases/xr-arcore
- Jetpack XR SDK: https://developer.android.com/develop/xr/jetpack-xr-sdk
- Projected context: https://developer.android.com/develop/xr/jetpack-xr-sdk/access-hardware-projected-context
- Developer Preview 3: https://android-developers.googleblog.com/2025/12/build-for-ai-glasses-with-android-xr.html
- Developer Preview 4: https://android-developers.googleblog.com/2026/05/android-xr-sdk-developer-preview-4-updates.html

Cameras and hand landmarks
- MediaPipe Hand Landmarker: https://ai.google.dev/mediapipe/solutions/vision/hand_landmarker
- AOSP external USB cameras: https://source.android.com/docs/core/camera/external-usb-cameras
- UVCCamera (libusb / libuvc on Android): https://github.com/saki4510t/UVCCamera
- One Pro Eye demo (cited as the route not to take): https://github.com/Aloim/Xreal-One-Pro-Eye-RGB-Camera-feed-on-Android
- ar-drivers-rs: https://github.com/badicsalex/ar-drivers-rs
- XRLinuxDriver: https://github.com/wheaney/XRLinuxDriver
- Monado Xreal Air driver: https://monado.pages.freedesktop.org/monado/group__drv__xreal__air.html

Phone and injection
- Z Flip 7 video out: https://www.usbctohdmi.com/compatibility/samsung/galaxy-z-flip-series
- `GestureDescription.Builder` (`setDisplayId`): https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder
- `GestureDescription`: https://developer.android.com/reference/android/accessibilityservice/GestureDescription
