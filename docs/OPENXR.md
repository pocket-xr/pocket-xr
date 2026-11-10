# OpenXR in Pocket XR

Pocket XR can act as an OpenXR runtime, so OpenXR apps and games can draw on the glasses. It is built on
[Monado](https://gitlab.freedesktop.org/monado/monado), the open source OpenXR runtime (BSL-1.0).

This is early work. It has played Beat Saber from a PC through SteamVR and ALVR on a Galaxy Z Flip 7, with Switch Joy-Cons
as controllers.

## How it fits together

1. A game's OpenXR loader asks the Khronos runtime broker which runtime is active.
2. The loader loads `libopenxr_monado.so` from Pocket XR into the game.
3. The game binds `OpenXrRuntimeService` with Monado's connect action. Monado's service then runs in Pocket XR's main
   process.
4. `canDrawOverOtherApps` answers yes when the Space is on the glasses, so the game does not offer its own screen.
5. `OpenXrGlassesLayer` opens a sub-window of the Space's window and passes its surface to Monado.
6. The Space stops drawing until the game leaves.
7. `OpenXrHeadFeed` streams the head pose to the `pocketxr` driver in our Monado fork, without the smoothing windows use.
8. The phone stays awake with its screens off while a game runs. Locking takes the glasses' display away.

A sub-window is used because Android refuses a Presentation on a display where the app's activity already is.

## Building Monado

The libraries are built from the `pocket-xr` branch of our Monado fork, with the package name baked in. Its changes are
`app/src/main/cpp/monado/monado-pocketxr.patch`: apply it to the upstream commit named at its top with `git apply`.

```bash
./gradlew --no-daemon -Pandroid.injected.build.abi=arm64-v8a -PxrtAndroidPackage=com.hayeco.flipspaces :src:xrt:targets:openxr_android:assembleOutOfProcessRelease :src:xrt:ipc:assembleRelease :src:xrt:auxiliary:assembleRelease
```

The build needs `glslangValidator` from the Vulkan SDK. Then copy:

- `libmonado-service.so` and `libopenxr_monado.so` to `app/src/main/jniLibs/arm64-v8a/`
- `ipc-release.aar` to `app/libs/monado/monado-ipc.aar`
- `auxiliary-release.aar` to `app/libs/monado/monado-auxiliary.aar`

## Before a public release

- Pocket XR is GPLv3, so the fork is part of the source we must offer. Since 1.4.0 its changes are in
  `app/src/main/cpp/monado/monado-pocketxr.patch`, with the upstream commit they apply to. Export it again with each
  rebuild of the libraries.
- Check the licences of everything Monado pulls in, and add its notices to the app.
- `app/src/main/jniLibs/` is not exported, so public builds have no runtime libraries. Decide how they ship.
- Decide who may bind the runtime. Any app can bind it today, and a client can read the head pose.

## Joy-Cons

Linux's hid-nintendo driver gives each Joy-Con a device for buttons and stick, and another for its motion sensors. The
panel service reads both as the shell user (`JoyConReader`), because apps may not. It takes the button devices from
Android while a game runs, or Home and B would act as the phone's Home and Back. `JoyConPose` turns the sensors into an
orientation, and Monado sees two Touch-shaped controllers.

Capture on the left and Home on the right line that Joy-Con up with the way the head faces. Point it forward first.
The position comes from a simple arm model, since there is no position sensor.

## Streaming from a PC with ALVR

Use an ALVR nightly: 20.14.1's video shader is missing a precision line and does not compile on the Flip's GPU.
In ALVR's video settings, set the `operating-rate` decoder option to 120. Its default of 2147483647 makes Samsung's
hardware decoders fail with NO_MEMORY, and the stream falls back to software. HEVC at 100 Mbps looked good.

## Not done yet

- Stereo depends on the Space having the glasses in 3D already. Nothing switches it for a game.
- No rumble.
- Controller grip angle is a first guess.
- Pressing the phone's power button during a game is not tested. Nor is unplugging the glasses during one: the game's
  window does not come back after a real display dropout.
- A game that pauses leaves its window over the Space until it is closed.
- The phone runs hot: thermal status 3 was seen within two minutes of streaming.
