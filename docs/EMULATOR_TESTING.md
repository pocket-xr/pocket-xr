# Testing on an Android emulator

Since 0.3.6-spatial, debug builds can be tested on an Android emulator on a PC, with no phone,
no glasses and no camera. A debug-only hand replay feeds scripted hand landmarks into the same
pipeline the glasses camera feeds: after MediaPipe, before the filter, the pinch lock, the
recognizer and the pointer (`HandFeed` in the app, `HandsRecognizer` in core). So the cursor,
the pinch lock, taps, drags, window moves and resizes and the two hand stretch run exactly as on
the Flip. Release builds have none of this: the receiver and its permission live only in
`app/src/debug`.

All commands below run on the PC, from the repo folder.

## 1. A fresh emulator

1. In Android Studio's Device Manager, create a phone or tablet with an **Android 16 (API 36)
   x86_64** system image (Google APIs). Debug APKs carry x86_64 libraries for this; release APKs
   are arm64 only.
2. Start it, then build and install:

   ```
   gradle :app:assembleDebug :addon:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb install -r addon/build/outputs/apk/debug/addon-debug.apk
   ```

3. Turn on the Pocket XR pointer accessibility service (in the add-on) without the Settings screens:

   ```
   adb shell settings put secure enabled_accessibility_services com.hayeco.flipspaces.addon/com.hayeco.flipspaces.addon.PointerAccess
   adb shell settings put secure accessibility_enabled 1
   ```

   Check it is bound: `adb shell dumpsys accessibility | grep -i flipspaces` lists the service
   as enabled and bound.

4. Allow the overlay permission, so the debug open command may start the Spaces home or the
   Space from a broadcast while the app is in the background:

   ```
   adb shell appops set com.hayeco.flipspaces SYSTEM_ALERT_WINDOW allow
   ```

5. Shizuku is not needed. Without it the app uses its normal paths (DeX windows and the
   launcher), and the Space opens but cannot create app panels. To test panels in the Space too, install Shizuku in the emulator and start it with
   the adb command its app shows under "Start via adb", then allow Pocket XR in Shizuku.

## 2. Simulating DeX

There are two kinds of second display, for different tests:

- **The overlay display** (`overlay_display_devices`, below) is only for the DeX full screen
  check: does the Spaces home or the Space fill the whole display. Hand taps cannot be tested
  on it. Android leaves TYPE_OVERLAY displays out of accessibility input
  (`adb shell dumpsys accessibility` shows `1 valid display: 0`), so every gesture the pointer
  service dispatches there ends `cancelled on display 2` and nothing opens.
- **An emulator 2D display** is for every tap, drag and resize test (section 4):

  ```
  adb shell settings delete global overlay_display_devices
  adb emu multidisplay add 1 1920 1080 160 0
  ```

  Delete the overlay display first, so the app picks the 2D display as the glasses. It shows up
  as "Emulator 2D Display" (FLAG_PRESENTATION), and `dumpsys accessibility` then shows
  `2 valid displays: 0, 3`. Remove it again with `adb emu multidisplay del 1`.

Screenshots of a second display: `adb exec-out screencap -p -d <id> > shot.png`, where `<id>` is
the SurfaceFlinger display id from `adb shell dumpsys SurfaceFlinger --displays` (a long
number), not the display id from `dumpsys display`.

### The DeX full screen check (overlay display)

The emulator has no DeX, but Android's own desktop mode on a simulated second display behaves
the same way for Pocket XR (freeform windows on an external display):

```
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_desktop_mode_on_external_displays 1
adb shell settings put global overlay_display_devices 1920x1080/160
adb reboot
```

After the reboot a 1920x1080 window titled "Overlay #1" shows the second display. Pocket XR
treats a display named "Overlay #..." as an external display, so it picks it as the glasses.
Find its display id with:

```
adb shell dumpsys display | grep -E "mDisplayId=|Overlay #"
```

To check that the Spaces home or the Space really fills it (not a small freeform window), open
it (section 3) and look at the window and task bounds:

```
adb shell "dumpsys window windows | grep -i -A20 -E 'GlassesHomeActivity|SpaceActivity' | grep -i -E 'mDisplayId|frame=|bounds='"
adb shell "dumpsys window displays | grep -i -E 'Display: mDisplayId|bounds='"
```

The window's frame should be the whole display (0,0 to 1920,1080). The app logs which full
screen path took effect:

```
adb logcat -s FlipSpaces/Launch FlipSpaces/Window FlipSpaces/Display
```

`Full screen start of ... on display N: ...` names the launch path that started it (fullscreen
windowing mode, launch bounds of the whole display, or the display id alone), with the paths
Android denied. `... requestFullscreenMode took effect` or `... already full screen` comes from
the activity once it is up. To remove the second display again:
`adb shell settings delete global overlay_display_devices`.

## 3. Opening the Spaces home or a Space without the glasses

1.5.0: the tour (docs/TUTORIAL.md) opens on the phone at any step, in states an emulator cannot be put in:

```
adb shell am broadcast -a com.hayeco.flipspaces.debug.OPEN -p com.hayeco.flipspaces --es what tour --es step WINDOWS
# steps: WELCOME SETUP POINT THREE_FINGERS WINDOWS LOOK DEPTH MORE DONE
# optional: --es shizuku NOT_INSTALLED|NOT_RUNNING|NO_PERMISSION|READY   --es model AURA|ONE|ONE_PRO|AIR|VITURE|UNKNOWN
#           --ez connected true|false
```

```
adb shell am broadcast -a com.hayeco.flipspaces.debug.OPEN -p com.hayeco.flipspaces --es what home
adb shell am broadcast -a com.hayeco.flipspaces.debug.OPEN -p com.hayeco.flipspaces --es what space
adb shell am broadcast -a com.hayeco.flipspaces.debug.OPEN -p com.hayeco.flipspaces --es what home --ei display 2
# 0.5.1: the Space in its Presentation fallback (as when DeX keeps it freeform); false turns it off
adb shell am broadcast -a com.hayeco.flipspaces.debug.OPEN -p com.hayeco.flipspaces --es what space --ez presentation true
```

Without `--ei display` it uses the largest external display. The result is logged under
`FlipSpacesReplay`.

**0.5.3: start the emulator with `-gpu host`.** Panels are drawn from ImageReader
HardwareBuffers through EGL images. Under `-gpu swiftshader_indirect` those read back as one
flat colour (the buffers themselves are right), so a panel shows its app icon card or a single
colour. The host GPU draws them as on a phone.

The Space's own frame-stall watchdog (`FlipSpaces/Watchdog`) can be tried by stalling the
render thread on purpose:

```
adb shell am broadcast -a com.hayeco.flipspaces.debug.STALL -p com.hayeco.flipspaces --ei ms 6000
```

After 1 s it lets go of the panel images on the GPU, after 4 s it releases the panel displays,
and 2 s after frames run again they reopen (4 s for a second stall within 20 s). With
`adb shell setprop debug.flipspaces.panelframes dump`, every 25th panel image and copy is
written to the app's external files folder (`panel-image-N.png`, `panel-copy-N.png`).

Since 0.4.0-launcher the phone screen opens the Space on the emulator's overlay display by
itself when Shizuku is ready, and the flat home with its banner otherwise. The launcher opens
with the Spatial windows screen's Open the launcher button, a three finger tap on the trackpad,
or a pinch replay aimed at the Apps button below the horizon. Mode decisions
and panel displays log under `FlipSpaces/Mode`, `FlipSpaces/PanelHost` and `FlipSpaces/Panel`.

Since 0.4.1-launcher the three finger tap fires when the first finger lifts; three fingers
swiped sideways are Back, a quick two finger tap is a long press, and a two finger drag or pinch
over a panel scrolls (mouse wheel) or zooms inside its app. The thermal guard can be tried with
`adb shell cmd thermalservice override-status 2` (moderate) and undone with
`adb shell cmd thermalservice reset`.

### Opening an app as a window without the launcher (1.1.1)

```
adb shell am broadcast -a com.hayeco.flipspaces.debug.ADD_APP -p com.hayeco.flipspaces --es pkg com.android.settings
```

does what picking the app in the launcher does. It is in debug builds only.

### Android 11 to 13 emulators (1.1.1)

Their images start with OpenGL ES 2.0 only, and the Space needs 3 (`eglChooseConfig failed` at once). Start them with
`-gpu host -feature GLESDynamicVersion`. On Android 11 `multidisplay add` prints `KO` but still adds the 2D display.

## 4. Hand replay

Replay on the emulator 2D display from section 2, not the overlay display: taps dispatched to
the overlay display are cancelled by Android. Watch the log while replaying:

```
adb logcat -s FlipSpacesReplay FlipSpacesHands FlipSpacesPointer FlipSpacesGesture FlipSpaces/Space
```

`FlipSpacesHands` has every pinch step (closing onset, lock with its rewind, press with what
made it a press and the thresholds in use, release, aborts, lost hands), `FlipSpacesPointer` and
`FlipSpacesGesture` every tap with its display and Android's result callback
(`Tap at x,y on display N: dispatching a 60 ms stroke`, then `completed`, `cancelled` or
`refused`), `FlipSpacesPointer` also what each press became, with its hold time and the tap
limit it was held against (`Press from Hands: pinch (right hand) held 445 ms, tap limit 650 ms:
tap`, or `long press` or `drag`), and `FlipSpaces/Space` every press in the Space with what it hit
(`Space press at yaw ..., pitch ...: panel body of Ally (panel 1) at u 0.42, v 0.61; nearest
...` or `...: nothing; nearest Ally (panel 1), 4.2 deg outside its body`).

### Built-in scenarios

Coordinates are glasses display pixels (0,0 top left; 1920x1080 on the simulated display).

```
# Point at x,y and pinch once (Mike's natural pinch: rest 0.48, bottom 0.32, reopen to 0.55)
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario tap --ef x 1030 --ef y 780

# Pinches logged on the glasses in a 0.3.5 test session, built into the replay
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario log-pinch-4
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario log-pinch-5
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario log-pinch-6
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario log-pinch-10
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario log-pinch-14

# Pinch-drag a window title bar from x,y to x2,y2
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario drag --ef x 900 --ef y 230 --ef x2 1100 --ef y2 430

# Resize from a corner handle
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario resize --ef x 1390 --ef y 890 --ef x2 1590 --ef y2 990

# Two hands pinch either side of x,y, gap px apart, and pull to gap2 px
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario stretch --ef x 960 --ef y 540 --ef gap 300 --ef gap2 700

# Stop a running replay
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY_STOP -p com.hayeco.flipspaces
```

Pinches 4 to 6 aimed at the glasses home app list and aborted in 0.3.5; each should now log a
`Pinch down` and a tap at about 1030,780. They are held about 430 to 460 ms, so their `Press
from` line must say `tap`: since 0.3.7 a pinch is a tap until it is held still past its tap
limit (650 ms to start with, then learnt per hand from its taps: 200 ms past the longest of its
last 8, never below 550 or above 900 ms). Before 0.3.7 the 260 ms trackpad limit turned them into
long touches. A pinch held still for a second or more is still a long press, and a pinch that
moves is a drag however short it is. Pinches 10 and 14 are the two Space presses that
rewound 291 and 660 px; their `Pinch aim locked` lines should now show at most 50 px of drift,
and their `Press from` lines must say `drag`: both are short pinches while the hand sweeps, and
those stay drags.

### Panel coordinates in the Space

With the Space open (and Shizuku running, for panels), give panel coordinates instead: `u` and
`v` run 0 to 1 across and down a panel's body; the title bar is just above it (v about -0.06)
and the corner handle at its bottom right (u and v about 0.98). `--ei panel <id>` picks a panel,
default the front one. The replay works out the display pixels from the Space as it stands.

```
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario tap --ef u 0.5 --ef v 0.5
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario drag --ef u 0.5 --ef v -0.06 --ef u2 0.8 --ef v2 0.2
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es scenario resize --ef u 0.98 --ef v 0.98 --ef u2 1.2 --ef v2 1.2 --ei panel 1
```

### Replay files

Write a scenario out as a file to edit, push files back, and play them:

```
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY_DUMP -p com.hayeco.flipspaces --es scenario log-pinch-6
adb pull /sdcard/Android/data/com.hayeco.flipspaces/files/log-pinch-6.replay.txt
adb shell mkdir -p /sdcard/Android/data/com.hayeco.flipspaces/files
adb push my-pinch.replay.txt /sdcard/Android/data/com.hayeco.flipspaces/files/
adb shell am broadcast -a com.hayeco.flipspaces.debug.REPLAY -p com.hayeco.flipspaces --es file /sdcard/Android/data/com.hayeco.flipspaces/files/my-pinch.replay.txt
```

The format (core's `ReplayCodec`) is plain text, `#` starts a comment:

```
flipspaces-hand-replay v1
frame 0                     # capture time in ms since the start, never going back
hand right 0.97             # right, left or unknown, then an optional score
image x,y,z x,y,z ...       # 21 normalised image landmarks in MediaPipe's order
world x,y,z x,y,z ...       # optional: 21 world landmarks in metres (pinch is measured on these)
frame 33                    # a frame with no hand lines: no hand was seen
```

Up to two hands per frame. A file that does not parse is refused with its line number in the
log. Options for both scenarios and files: `--ei latency <ms>` (capture to cursor, default 37,
as measured on the Flip) and `--ei repeat <n>`. Frames play in real time; after the last one the
hand leaves for half a second, so a lock still held ends as it would on the Flip.

## 5. What the emulator does not cover

No camera, so nothing about USB, MJPEG or MediaPipe itself; no glasses IMU, so the Space has
no head tracking (it stays facing forward, and the trackpad turns it); and Android desktop mode
is close to, not the same as, Samsung DeX. Anything that depends on those still needs the Flip.
