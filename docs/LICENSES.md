# Third party notices

Pocket XR is written from scratch. It contains no code from XREAL's apps or SDK, or from any other proprietary
software. GPL and unlicensed projects listed in `docs/SPATIAL.md` were read as references only; nothing from them is
in this repository.

## Libraries (Maven, from google() and mavenCentral())

- AndroidX, Jetpack Compose: Apache License 2.0.
- MediaPipe Tasks Vision (`com.google.mediapipe:tasks-vision`): Apache License 2.0.
- Shizuku API and provider (`dev.rikka.shizuku`): Apache License 2.0.
- LiteRT (`com.google.ai.edge.litert:litert`), by Google: Apache License 2.0. The app depends on it from Maven to run
  the hand models on the phone's NPU when they are present.
- MediaPipe hand models, by Google: Apache License 2.0. The hand landmarker (`hand_landmarker.task`) is not in the
  repository; the app downloads it from Google's model storage on first use. The versions compiled for the Galaxy Z
  Flip 7's NPU (and the LiteRT build that runs them) are not in the public repository yet; without them hand tracking
  runs MediaPipe on the GPU or CPU. The official GitHub release APK includes them (docs/RELEASE.md); the Google Play
  build does not.

## ALVR (PC VR, private builds)

- ALVR's client library (`alvr_client_core`, built from https://github.com/alvr-org/ALVR at nightly 21.0.0-dev14,
  with Pocket XR's small patch in `app/src/main/cpp/alvr/alvr-pocketxr.patch`): MIT License, Copyright (c) 2018-2019
  polygraphene, Copyright (c) 2020-2024 alvr-org. The licence text ships inside the app as
  `assets/licenses/ALVR.txt`. The PC helper's bundle includes ALVR's streamer for Windows with its own `licenses` folder.

## Fonts (0.5.68)

- Inter 4.1, Copyright (c) 2016 The Inter Project Authors, https://github.com/rsms/inter, under the SIL Open Font
  License 1.1 (https://openfontlicense.org). `app/src/main/res/font/inter_regular.ttf` and `inter_medium.ttf` are the
  release's static Regular and Medium files, unmodified.

## Protocol facts

The glasses' control link (169.254.2.1, TCP port 52999: the message types, the 6 byte header, the field numbers, and
the 3D mode, recentre and calibration messages that `core/spatial/GlassesWire.kt` and `GlassesDisplayCommands.kt`
build and read) comes from our own captures of the glasses' USB network traffic. No XREAL code is used.

The XREAL One family sensor stream that `core/spatial/OneImuProtocol.kt` reads (glasses at
169.254.2.1, TCP port 52998, frame magic 0x28 0x36 or 0x27 0x36, big endian length, 128 byte
little endian report with timestamp, report type, gyro, accelerometer, magnetometer and
temperature at fixed offsets) was taken from the public descriptions in these MIT licensed
projects. The parser, fusion filter and network code in Pocket XR were written independently
for this project; no source file was copied. Credit and the licence notice follow.

- one-xr, Copyright (c) 2026 Neil Skaria, https://github.com/Skarian/one-xr (MIT). Also the
  source of the facts that Android reaches the glasses through `Network.socketFactory` and that
  some firmware uses the 0x27 0x36 magic.
- One-Pro-IMU-Retriever-Demo, Sami Mitwalli, https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo
  (MIT, asks for credit): report rate (about 1 kHz) and the complementary filter approach.
- xreal_one_driver, rohitsangwan01, https://github.com/rohitsangwan01/xreal_one_driver (MIT).
- xreal-one-cli, George Horrell, https://github.com/georgehorrell/xreal-one-cli (MIT).

MIT License notice (one-xr; the other MIT projects use the same terms with their own
copyright lines):

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Framework behaviour

Virtual display flags, permissions and the shell's privileges come from the Android framework
source (AOSP, Apache License 2.0) as summarised in `docs/SPATIAL.md` section 2. The approach of
creating displays and injecting input as the shell follows scrcpy (Apache License 2.0,
https://github.com/Genymobile/scrcpy); its code was read, not copied.

## Material Symbols (icons)

0.5.96 (open source audit): the trackpad's and the Menu's icons are Material Symbols Rounded, by Google. They are
licensed under the Apache License 2.0 (https://github.com/google/material-design-icons, LICENSE). They were redrawn as
Android vector drawables from Google's own `symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml` files,
recoloured and resized. Each drawable's header names its symbol.
