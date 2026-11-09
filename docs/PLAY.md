# Google Play

How Pocket XR goes on Google Play under the Haye & Co developer account (connect@mikehaye.com): the rules it meets, the
signing, the declarations to paste into Play Console and the videos they need. The listing text, Data safety and content
rating answers are in [store/play/LISTING.md](../store/play/LISTING.md); the images in [store/play/images](../store/play/images).

## The apps

| | Pocket XR | Pocket XR add-on |
| --- | --- | --- |
| Package | `com.hayeco.flipspaces` (kept; it is Pocket XR's since the first build and not on Play) | `com.hayeco.flipspaces.addon` |
| Version | 1.2.9, versionCode 161 | 1.1, versionCode 2 |
| minSdk / targetSdk / compileSdk | 30 / 36 / 37 | 30 / 36 / 37 |
| Bundle | [release/play/pocketxr-1.2.9-161.aab](../release/play/pocketxr-1.2.9-161.aab) (Git LFS) | [release/play/pocketxr-addon-1.1-2.aab](../release/play/pocketxr-addon-1.1-2.aab) (Git LFS) |
| R8 mapping | [release/play/pocketxr-1.2.9-161-mapping.txt](../release/play/pocketxr-1.2.9-161-mapping.txt) (Git LFS): upload it with the bundle, under App bundle explorer, Downloads, ReTrace mapping file | Not shrunk, none |
| Declares | Camera and connectedDevice foreground services | The AccessibilityService and the notification listener |

Checksums are in [release/play/SHA256SUMS](../release/play/SHA256SUMS).

Target API: Google Play requires new apps and updates to target Android 16 (API 36) or higher since 31 August 2026
(developer.android.com/google/play/requirements/target-sdk, read 4 November 2026). Both apps target 36, so nothing
changed.

The bundles are built from this public repository. The Samsung NPU hand models and the LiteRT build that runs them are
not in it, so on Play hand tracking runs MediaPipe on the GPU or CPU. The GitHub release fetches them into
`app/src/npu` before it builds (docs/RELEASE.md), but `-Ppocketxr.play=true` leaves that folder out of the build even
when it is there.

### Two apps

Since 1.1.0 the accessibility service and the notification listener are in the add-on, so Pocket XR itself declares
neither. Play Protect's block on sideloaded apps with those services now hits the add-on, so the add-on should go on Play
too. Pocket XR talks to the add-on only through a signature permission (ADDON_LINK), so **both apps must be signed with
the same app signing key on Play**, and that key decides whether Play copies work with GitHub copies:

- **Recommended: give Play the existing Pocket XR release key** (the one `pocketxr.storeFile` in Mike's `~/.gradle/gradle.properties` points to, which signs the GitHub releases)
  when creating each app, under App integrity, App signing, "Use a different key", "Export and upload a key from Java
  keystore" (Play's PEPK tool). Then Play's Pocket XR and add-on are signed exactly like the GitHub releases: a Play
  update installs over a GitHub install without uninstalling, and a Play Pocket XR works with a GitHub add-on and the
  other way round.
- Or let Google generate the key for Pocket XR, and for the add-on choose "Use the same key as another app in this
  developer account". Play copies then work together, but not with GitHub copies, and someone moving from GitHub to Play
  must uninstall first (losing their settings).

The choice cannot be changed after the first upload of each app.

## What changed for Play (1.1.11)

- The add-on has its own screen (`SetupActivity`, its launcher icon) with the prominent disclosure and consent for each
  service ([core/Disclosure.kt](../core/src/main/kotlin/com/hayeco/flipspaces/core/Disclosure.kt), tested by
  `Disclosure1111Test`). Pocket XR's "Open accessibility settings" (Hands), the one-time "Turn on the pointer" prompt and
  the Notifications page's Allow all open it. Only "Agree and turn on" goes on to Android's page; "No thanks" leaves the
  service off.
- Notification access is no longer turned on silently through Shizuku (`cmd notification allow_listener`); the user
  switches it on in Android's page after the disclosure. Only an add-on older than 1.1 is still turned on that way.
- A copy installed by Google Play (`com.android.vending`) never offers GitHub's APKs: "Check for updates" is hidden
  (Play's Device and Network Abuse policy), and "Get the add-on" opens the add-on's Play page.
- The release manifest no longer has `FOREGROUND_SERVICE_DATA_SYNC` or Play Core's disabled asset pack extraction
  service (brought by LiteRT, unused), so Play asks no dataSync declaration.
- `-Ppocketxr.play=true` signs both release builds with the upload key and shrinks Pocket XR with R8
  ([app/proguard-rules.pro](../app/proguard-rules.pro)). Every other build is unchanged.

## Manifest check

Pocket XR release (merged manifest of the bundle):

| Item | Status |
| --- | --- |
| Debug-only permissions | None in release. SYSTEM_ALERT_WINDOW, the DebugReceiver and the NPU self test are only in src/debug |
| QUERY_ALL_PACKAGES | Not declared. `<queries>` asks only for launcher apps, Samsung DeX, Shizuku, the add-on and two Settings actions: no declaration |
| SYSTEM_ALERT_WINDOW | Not in release |
| REQUEST_INSTALL_PACKAGES | Not declared |
| Shizuku `moe.shizuku.manager.permission.API_V23` | Declared by the Shizuku library. Not a Play restricted permission and needs no form; Shizuku is a separate app the user installs and starts. Reviewers may ask: Pocket XR uses it to create a virtual display per app window, start apps there, and run the Control Center's Wi-Fi and Bluetooth switches |
| `INTERACT_ACROSS_USERS_FULL` | Not requested. Used only as the guarding permission on SpaceActivity and the Shizuku provider, so only the shell and the system can start them |
| RECORD_AUDIO | Declared, never used to record (see LISTING.md). No Play form; covered by the privacy policy |
| Foreground service types | `camera` (HandTrackingService) and `connectedDevice` (GlassesReadyService): each needs the foreground service declaration below |
| Accessibility, notification listener | None in Pocket XR (they are the add-on's) |
| Exported components | All with intent filters set `android:exported` explicitly |

Add-on release: the AccessibilityService (`PointerAccess`, not `isAccessibilityTool`) and the NotificationListenerService
(`NotesListener`), the ADDON_LINK signature permission, no other permissions.

## Signing and building

The upload key is new and only for Play: `keys\pocket-xr\pocket-xr-upload.jks` in Mike's user folder (PKCS12, alias `upload`,
RSA 4096, valid to 2056), next to the other Haye & Co upload keys. Its password and fingerprints are in
`README-upload-key.txt` beside it, as for the other apps. One upload key serves both Pocket XR apps.
Upload key SHA-256: `79:D9:CB:00:99:99:1A:C7:D6:7A:AC:CF:DB:BD:5B:A9:62:70:FB:A3:81:7D:D6:96:7C:D3:10:48:D9:3E:20:83`.

The build reads `keystore.properties` at the repository root (gitignored, never committed):

```
storeFile=<path to keys/pocket-xr/pocket-xr-upload.jks>
storePassword=<from README-upload-key.txt>
keyAlias=upload
keyPassword=<the same>
```

Build with Android Studio's JBR:

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
gradle -Ppocketxr.play=true :core:test :app:bundleRelease :addon:bundleRelease
```

Run `:app:testDebugUnitTest` in its own Gradle run without `-Ppocketxr.play=true`: with it the app's tests run as a Play
copy, and the add-on tests that expect the GitHub install step fail (1.2.6).

The bundles are `app/build/outputs/bundle/release/app-release.aab` and `addon/build/outputs/bundle/release/addon-release.aab`,
the mapping `app/build/outputs/mapping/release/mapping.txt`. Copy them to release/play with the version in the name and
update SHA256SUMS. Raise `versionCode` for every upload.

## Declarations for Play Console

### Accessibility API (add-on, App content, Sensitive app permissions, Accessibility API)

Is the app an accessibility tool? **No** (`isAccessibilityTool` is not set).

Why the app uses the AccessibilityService API: **App functionality** only. Not analytics, advertising, personalisation,
account management, fraud prevention or developer communications.

Description to paste:

```
The Pocket XR add-on gives the Pocket XR app (a multi-window workspace for XREAL glasses) a cursor on the glasses outside Pocket XR's own 3D Space, when the glasses show Samsung DeX. The user moves the cursor with their hands (glasses camera) or with the phone's screen as a trackpad. The AccessibilityService API is the only way Android lets an app do the following on the glasses' display: draw the cursor in an accessibility overlay window (TYPE_ACCESSIBILITY_OVERLAY) on the glasses' display; read where app windows are on that display (their bounds and window titles, from getWindowsOnAllDisplays) so a pinch or tap lands on the right window; read the labels of the buttons in DeX's window title bars on that display to find and press DeX's full screen button; and perform the taps and drags the user makes (dispatchGesture) and Back. It does nothing on the phone's own screen, does not read text the user types or the content of app windows, and takes no action on its own: every tap and drag comes from the user's hand or finger. Nothing it reads is stored or sent off the phone; it is passed only to the Pocket XR app on the same phone, through a service guarded by a signature permission.
```

Does the app collect or share personal or sensitive user data through the API? **No.**

Prominent disclosure (as shown in the add-on, `core/Disclosure.kt`, Kind.POINTER):

```
Pocket XR pointer uses the Accessibility service API

Pocket XR is not an accessibility tool. It uses Android's AccessibilityService API for one job: to give your XR glasses a cursor you move with your hands or the phone's screen, outside the Space.

It reads:
- Where app windows are on the glasses display: their position, size and window title.
- The labels of the buttons in DeX's window title bars on the glasses display, to find and press its full screen button.

It draws the cursor on the glasses and performs the taps and drags you make there, so you can click, move and resize windows. It does nothing on the phone's own screen, and does not read what you type or what is inside app windows.

Nothing it reads is stored, collected or sent off the phone. It is shared only with the Pocket XR app on this phone, and with no one else.

Without it, the Space, the trackpad and hand tracking still work; only the cursor outside the Space is missing.

[Agree and turn on]  [No thanks]
```

The service's description in Android's Accessibility settings (`pointer_service_description`) says the same in short.

### Notification access (add-on)

Play has no separate form for a NotificationListenerService; it falls under the User Data policy (prominent disclosure
and consent, privacy policy, Data safety). The add-on shows this before opening Android's notification access page
(`Disclosure.Kind.NOTES`):

```
Pocket XR add-on uses notification access

Pocket XR can show your phone's notifications on the Space's Notifications page in your XR glasses.

It reads:
- Your notifications: the app, title and text of each, and when it came.
- Which apps are playing media, for the now playing controls.

It shows them in the glasses, opens a notification's app when you press it and dismisses the ones you clear.

Nothing it reads is stored, collected or sent off the phone. It is shared only with the Pocket XR app on this phone, and with no one else.

Without it, everything else in Pocket XR works; the Notifications page stays empty.

[Agree and turn on]  [No thanks]
```

### Foreground services (Pocket XR, App content, Foreground service permissions)

1.2.6 (Nov 2026): the form only lists the permissions when opened from the release's error ("Go to declaration"; from
App content directly it shows none). Each type asks for a task (tick boxes), a video link and one description; Save stays
off until every ticked task has its video link, so nothing can be saved as a draft before the videos exist. Paste these
as they are; each is one box on the form.

**camera** (`HandTrackingService`):

- Task: "Other" (the form offers "Background camera streaming" and "Other"; hands frames are not streamed anywhere).
- Video: video 3 below.
- Description: `Hand tracking for XREAL glasses. The user starts it on Pocket XR's Hands screen (Start hands), or, once they have turned on "Start hands with the glasses", it starts when Pocket XR's workspace opens on the glasses. The service reads the camera built into the glasses over USB and finds the user's hands on the phone, so they can point at and pinch the windows shown in the glasses while Pocket XR is not in front on the phone (the phone is in a pocket or another app is open). Frames are processed on the phone only. It must run in the foreground because the user is looking at the glasses, not the phone, the whole time. If it is paused or delayed, the cursor in the glasses stops following the user's hand mid gesture, a pinch or drag is lost, and they cannot click in the glasses until they take the phone out. The ongoing notification has "Stop until next plug-in" and "Exit" buttons.`

**connectedDevice** (`GlassesReadyService`):

- Task: "Other" (the form offers "Continuous data transfer to an external device", "Automotive key" and "Other"; the
  service itself transfers nothing, it waits for the glasses' display).
- Video: video 4 below.
- Description: `Pocket XR drives XREAL glasses plugged into the phone's USB-C port. A quiet foreground service with the notification "Pocket XR is ready for your glasses" starts when the user opens Pocket XR, after the phone restarts and after an update. It keeps Pocket XR able to receive Android's event that the glasses' display has connected, so that plugging the glasses in opens Pocket XR's workspace on them and its head tracking reconnects to the glasses' motion sensor over USB. It must be running before the glasses are plugged in, because the user can plug them in at any moment and the connection event is not delivered to an idle app. If it is stopped or delayed, plugging in the glasses shows Android's own screen instead of the user's workspace and head tracking does not connect until the user opens Pocket XR on the phone by hand. The notification's "Exit" button stops it.`

Risk to know: Play looks for foreground services the user starts. GlassesReadyService also starts after boot and waits
for the glasses. If the reviewer rejects that, the fallback is to start it only from a user action (opening Pocket XR or
the plug-in prompt), which a later step can do.

## Videos (only Mike can record these)

Each declaration needs a video link (YouTube, unlisted is fine). Record on the Galaxy Z Flip 7 with the glasses. For the
glasses side, Pocket XR's own "Record the glasses" (trackpad Menu) saves a clean 1080p video; record the phone with the
phone's screen recorder at the same time and put them side by side, or film both with a second camera.

1. **Accessibility (add-on), about 60 to 90 seconds.** Start with the add-on not yet enabled (switch it off in
   Accessibility first).
   1. Open Pocket XR on the phone, Settings, Hands.
   2. Show the "Pocket XR pointer" card and tap "Open accessibility settings".
   3. The add-on's disclosure opens: scroll through it slowly so every line can be read.
   4. Tap "No thanks": it closes and the service stays off (show the card still says Off).
   5. Tap "Open accessibility settings" again, then "Agree and turn on".
   6. Android's Accessibility page opens: switch on "Pocket XR pointer" and accept Android's dialog.
   7. Plug in the glasses (or show them already connected) in flat DeX mode. Show the cursor on the glasses moving with
      the trackpad, a tap opening an app, a drag moving a window by its title bar, and the full screen button pressed.
   8. Say or caption: "The service draws the cursor and performs the user's taps and drags on the glasses' display. It
      does nothing on the phone's screen and sends nothing off the phone."
2. **Notification access (add-on), optional but recommended, about 45 seconds.** In the Space, open the Control Center,
   Notifications, press Allow; on the phone the add-on's disclosure appears; show "No thanks", then "Agree and turn on",
   Android's Notification access page, switching it on, and the notifications appearing on the glasses.
3. **camera foreground service (Pocket XR), about 30 seconds.** Hands screen, Start hand tracking; the notification
   appears; put the phone away and point and pinch at a window in the glasses.
4. **connectedDevice foreground service (Pocket XR), about 30 seconds.** Show the Pocket XR notification, plug in the
   glasses, the Space opening on them and head tracking working; then the notification's Exit action stopping it.

## Only Mike can do

- Create both apps in Play Console (Haye & Co), choose the app signing key (see Two apps), and upload the bundles and the
  mapping.
- Record and upload the videos above and paste their links into the declarations.
- Answer the forms from LISTING.md and this file, and confirm the Data safety choice on "Shared" (LISTING.md).
- Create a closed test if Play asks (new personal developer accounts must run a closed test with testers before
  production; an organisation account does not).
- Merge this branch to main so the privacy policy URL is live:
  https://github.com/pocket-xr/pocket-xr/blob/main/PRIVACY.md
- Copy `keystore.properties` into his own checkout to build future uploads (its contents are in this file's Signing
  section and the key's README).
- Port these changes into the private repository before the next export, which would otherwise overwrite them here.
