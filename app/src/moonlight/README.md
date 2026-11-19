# Moonlight's stream code (PC screens)

This is the Java half of Moonlight's client core, used by Pocket XR's PC screens (`pcscreen/`). It is from
[moonlight-android](https://github.com/moonlight-stream/moonlight-android) at b48494c, under the GPLv3, the same
licence as Pocket XR (`LICENSE-moonlight-android.txt` in `../main/cpp/moonlight`).

## What was copied

- `com/limelight/nvstream`, without `mdns` and `wol`, which aren't used yet;
- `com/limelight/binding/crypto/AndroidCryptoProvider.java`;
- `com/limelight/binding/audio/AndroidAudioRenderer.java`;
- `com/limelight/LimeLog.java`.

## Changes

`binding/audio/AndroidAudioRenderer.java` uses standard audio tracks only, since a low-latency one gave no sound
through the XREAL Aura on a Flip7. It also replaces a low-latency track that doesn't play after 500 ms with a
standard one, and logs the audio's peak level every 5 seconds. This was seen on a Flip7 with the XREAL Aura's USB
audio.

`nvstream/http/NvHTTP.java` no longer reads Moonlight's `BuildConfig`, and its verbose logging is off.

## Why the package names are Moonlight's

The package names stay `com.limelight.*` because the native library (`libmoonlight-core.so`, `../main/cpp/moonlight`)
finds these classes by those names.
