# GitHub releases

How Mike builds and publishes an official Pocket XR APK on GitHub Releases. Google Play bundles are built separately
(docs/PLAY.md); never pass `-Ppocketxr.play=true` in the same Gradle run as the GitHub APK.

## The NPU parts

On phones with the Exynos 2500 (the Galaxy Z Flip 7), the GitHub release runs the hand models on the phone's NPU
(`NpuHandEngine`, docs/HAND-TRACKING.md). That needs four files that are not in this repository, because the licence of
the models compiled with Samsung's SDK is not yet checked:

- `assets/npu/hand_detector_e9955.tflite` and `assets/npu/hand_landmarks_e9955.tflite`: MediaPipe's palm detector and
  hand landmark models compiled for the Exynos 2500's NPU.
- `jniLibs/arm64-v8a/libLiteRt.so`: our LiteRT v2.3.2 build, which exports what the Samsung dispatch needs.
- `jniLibs/arm64-v8a/libLiteRtDispatch_Samsung.so`: the Samsung NPU dispatch.

They live in the project's private repository, in `app/src/npu`. `tools/fetch-npu.sh` copies them from a pinned commit
there into `app/src/npu` here (gitignored) and checks each one's SHA-256. `app/build.gradle.kts` adds that folder to the
build unless `-Ppocketxr.play=true` is set. Without it, or in the Play build, hand tracking runs MediaPipe on the GPU or
CPU. Only someone with read access to the private repository can fetch them; everyone else's builds work without them.

1.1.13 was built without this step, so it had no NPU hand tracking. Every GitHub release from 1.1.14 on runs it.

## Steps

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` and add the version to CHANGELOG.md.
2. Fetch the NPU parts (Git Bash, from the repository root):

   ```
   bash tools/fetch-npu.sh
   ```

3. Run the tests and build the signed APK in one PowerShell window, typing the release key's password when asked. The
   key's file and alias are in Mike's `~/.gradle/gradle.properties` (`pocketxr.storeFile`, `pocketxr.keyAlias`); the
   password is never stored. Use Android Studio's JBR and Gradle 9.8.2 (AGP 9.5 needs Gradle 9.6 or later):

   ```
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:POCKETXR_STORE_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host "Release key password" -AsSecureString)).Password
   gradle :core:test :app:testDebugUnitTest :app:assembleRelease "-Ppocketxr.official=true"
   ```

   - The prompt must hide what you type (`-AsSecureString`). A plain `Read-Host` shows the password on screen and in
     the terminal's scrollback (1.2.5).
   - Keep the quotes around `-Ppocketxr.official=true`: unquoted, PowerShell splits it at the dot and Gradle looks for a
     task called `.official=true`.
   - Close the window afterwards, so the password doesn't stay in its environment.

4. Copy `app/build/outputs/apk/release/app-release.apk` to `pocketxr-x.y.z.apk` and check it:

   ```
   apksigner verify --print-certs pocketxr-x.y.z.apk
   bash tools/check-npu.sh pocketxr-x.y.z.apk
   ```

   The certificate's SHA-256 must be the release key's, as in README.md
   (`33:EA:D4:CD:...:93:AD`), and check-npu.sh must say OK.

5. Publish the release with the tag `vx.y.z`, the APK attached, marked Latest, and the release key's fingerprint at the
   end of the notes.
