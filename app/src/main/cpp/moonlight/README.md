# Moonlight's native stream core (PC screens)

This is from [moonlight-android](https://github.com/moonlight-stream/moonlight-android) at b48494c, under the
GPLv3, the same licence as Pocket XR (`LICENSE-moonlight-android.txt`).

## What it contains

- **From moonlight-android's `jni/moonlight-core`:** `simplejni.c`, `callbacks.c`, `minisdl.c`/`.h`, the
  controller and USB id headers, and the prebuilt `openssl` (libcrypto) and `opus` static libraries for
  arm64-v8a and x86_64.
- **`moonlight-common-c`:** [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) at
  874ac95, the commit moonlight-android pins, with its `enet` and `nanors` submodules.

## How it's built

`../CMakeLists.txt` builds `libmoonlight-core.so` the way moonlight-android's `Android.mk` does.
`cpu-features` comes from the NDK.
