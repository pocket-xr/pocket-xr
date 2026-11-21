# Google Play listing: Pocket XR

Everything to paste into Play Console for **Pocket XR** (`com.hayeco.flipspaces`), developer account Haye & Co. Since
1.6.3 the Play build carries the add-on's two services (the pointer and notification access) itself, so their
Accessibility API declaration and disclosures are Pocket XR's, below under App content answers, and the add-on needs no
listing of its own (its old one is kept at the end for reference). Images are in [images/](images/) and described in
[MANIFEST.md](MANIFEST.md). The foreground service declarations and the demo videos are in
[docs/PLAY.md](../../docs/PLAY.md).

## Store settings

| Field | Answer |
| --- | --- |
| App name (30 max) | Pocket XR: Spatial Desktop |
| Default language | English (United States), en-US |
| App or game | App |
| Free or paid | Free |
| Category | Productivity |
| Tags | Productivity, Tools (pick the closest Play offers) |
| Contact email | connect@mikehaye.com |
| Website | https://github.com/pocket-xr/pocket-xr |
| Privacy policy | https://github.com/pocket-xr/pocket-xr/blob/main/PRIVACY.md |
| Ads | No, the app contains no ads |
| In-app purchases | None |
| Target audience | 18 and over only |
| Appeals to children | No |

## Short description (80 max)

```
Your Android apps as windows around you on XREAL glasses, run from your phone
```

## Full description (4000 max)

```
Pocket XR turns an Android phone and a pair of XREAL glasses into a multi-window workspace. Your apps float around you as windows you can move, resize, curve and push back. Head tracking keeps them in place, and the phone becomes the controller.

WINDOWS IN SPACE
- Any app opens as a window around you, at its own size and distance.
- Drag windows by their edges, snap them side by side, curve them, push them further or pull them closer.
- Move, push, pull or resize every window at once.
- Arrange layouts and presets that reopen a set of windows where they were.
- A launcher with search, the time, and your phone's battery, signal and Wi-Fi.
- A Control Center for layouts, Wi-Fi, Bluetooth, notifications and recentring.
- Record the glasses: a clean video of what you see, saved on your phone.

SEEING IT
- 3D side by side, with each window at a real depth.
- Head tracking from the glasses' own motion sensor, paced to their display.

CONTROLS
- The phone as a trackpad, with taps, two-finger scroll and pinch, and three-finger swipes.
- The Spatial Mouse: point the phone like a laser.
- Hand tracking on glasses with a camera (XREAL Aura).

WHAT IT NEEDS
- A phone whose USB-C port carries video (DisplayPort alt mode), on Android 11 or later.
- XREAL glasses: the Aura and the One are tested; the One Pro and 1S are in community testing.
- Shizuku, a free app, to create a window for each app.
- Optional: the cursor outside the Space and the Notifications page. They use Android's accessibility service and notification access; Pocket XR explains each one and asks before either is switched on, and you can switch either off in Android's settings at any time. Pocket XR is not an accessibility tool.

PRIVACY
Pocket XR has no accounts, no ads and no tracking of its own. Your apps, windows, notifications and camera frames stay on your phone. Hand tracking runs on Google's MediaPipe library, which sends Google anonymous usage statistics while it runs. Details are in the privacy policy.

Pocket XR is free and open source (GPL-3.0): github.com/pocket-xr/pocket-xr

Pocket XR is not made by or affiliated with XREAL. "XREAL" and the glasses' names are XREAL's trademarks, used only to say what the app works with.
```

## Graphics

| Asset | File | Size |
| --- | --- | --- |
| App icon | [images/icon-512.png](images/icon-512.png) | 512 x 512, 32-bit PNG |
| Feature graphic | [images/feature-graphic-1024x500.png](images/feature-graphic-1024x500.png) | 1024 x 500 |
| Phone screenshots (6) | [images/phone/](images/phone/) | 1920 x 1080 each (16:9), designed scenes (MANIFEST.md) |

Upload the phone screenshots in their numbered order. No tablet, Chromebook, TV, Wear or XR screenshots: Pocket XR is a
phone app.

## App content answers

### Privacy policy

`https://github.com/pocket-xr/pocket-xr/blob/main/PRIVACY.md` (live once this branch is merged to main).

### Ads

"No, my app does not contain ads."

### App access

"All functionality is available without special access." There is no login. Add this note for the reviewer:

```
Pocket XR needs XREAL glasses (Aura, One, One Pro or 1S) on a phone with USB-C video out (for example a Samsung Galaxy Z Flip 7) to show its Space. Without glasses, the phone screens (trackpad, settings, Hands, Spatial windows) open and work, and the demo videos linked in the declarations show the glasses side. Shizuku (free on Google Play) is needed for app windows in the Space; without it apps open as Samsung DeX windows.
```

### Content rating (IARC questionnaire)

- Category: **All other app types** (a utility, not a game, not social, not a store).
- Violence, blood, sexuality, nudity, profanity, crude humour, drugs, alcohol, tobacco, gambling, fear: **No** to all.
- Does the app let users interact or exchange content with other users? **No.**
- Does the app share the user's current physical location with other users? **No.**
- Does the app let users buy digital goods? **No.**
- Is the app a web browser or search engine? **No.** (Its launcher searches only the installed apps.)
- Does the app contain unrestricted internet access or user-generated content? **No.** It shows other installed apps'
  windows, which carry their own ratings.

Expected result: Everyone / PEGI 3 / USK 0 (the rating authorities decide).

### Target audience and content

- Target age groups: **18 and over** only.
- Could the store listing unintentionally appeal to children? **No.**

### News app

No.

### COVID-19 contact tracing and status apps

No.

### Government app

No.

### Financial features

None.

### Health

No health features.

### Accessibility API (App content, Sensitive app permissions, Accessibility API)

1.6.3: Pocket XR's own, as its Play build carries the pointer service (`com.hayeco.flipspaces.addon.PointerAccess` inside
`com.hayeco.flipspaces`). Before, this was meant for the add-on's listing.

Is the app an accessibility tool? **No** (`isAccessibilityTool` is not set).

Why the app uses the AccessibilityService API: **App functionality** only. Not analytics, advertising, personalisation,
account management, fraud prevention or developer communications.

Description to paste:

```
Pocket XR, a multi-window workspace for XREAL glasses, gives the glasses a cursor outside Pocket XR's own 3D Space, when the glasses show Samsung DeX. The user moves the cursor with their hands (glasses camera) or with the phone's screen as a trackpad. The AccessibilityService API is the only way Android lets an app do the following on the glasses' display: draw the cursor in an accessibility overlay window (TYPE_ACCESSIBILITY_OVERLAY) on the glasses' display; read where app windows are on that display (their bounds and window titles, from getWindowsOnAllDisplays) so a pinch or tap lands on the right window; read the labels of the buttons in DeX's window title bars on that display to find and press DeX's full screen button; and perform the taps and drags the user makes (dispatchGesture) and Back. It does nothing on the phone's own screen, does not read text the user types or the content of app windows, and takes no action on its own: every tap and drag comes from the user's hand or finger. Nothing it reads is stored or sent off the phone; it stays within the Pocket XR app on the phone.
```

Does the app collect or share personal or sensitive user data through the API? **No.**

Prominent disclosure (as shown by Pocket XR's setup screen, `SetupActivity`, from `core/Disclosure.kt`, Kind.POINTER):

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

### Notification access

Play has no separate form for a NotificationListenerService; it falls under the User Data policy (prominent disclosure
and consent, privacy policy, Data safety). Pocket XR's setup screen shows this before opening Android's notification
access page (`Disclosure.Kind.NOTES`; the Play build's title names Pocket XR, not the add-on):

```
Pocket XR uses notification access

Pocket XR can show your phone's notifications on the Space's Notifications page in your XR glasses.

It reads:
- Your notifications: the app, title and text of each, and when it came.
- Which apps are playing media, for the now playing controls.

It shows them in the glasses, opens a notification's app when you press it and dismisses the ones you clear.

Nothing it reads is stored, collected or sent off the phone. It is shared only with the Pocket XR app on this phone, and with no one else.

Without it, everything else in Pocket XR works; the Notifications page stays empty.

[Agree and turn on]  [No thanks]
```

### Data safety

These answers follow from the code and from PRIVACY.md. The one thing that leaves the phone automatically is the usage
statistics Google's MediaPipe library sends while hand tracking runs (its log source `COREML_ON_DEVICE_SOLUTIONS`:
package name and version, MediaPipe and Android versions, the task and mode, latencies, frame counts, error codes; no
frames, no hand data, no identifiers). Play counts data an SDK sends as collected by the app, so it is declared.

**Data collection and security**

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (MediaPipe sends it over HTTPS) |
| Do you provide a way for users to request that their data is deleted? | No (Haye & Co holds no user data; MediaPipe's statistics go to Google and carry no identifiers) |

**Data types**

| Data type | Collected | Shared | Processed ephemerally | Required or optional | Purpose |
| --- | --- | --- | --- | --- | --- |
| App info and performance: Diagnostics | Yes | No | No | Optional (only while hand tracking is on) | Analytics |
| App info and performance: Other app performance data | Yes | No | No | Optional (only while hand tracking is on) | Analytics |

Every other data type: **not collected**. In particular, none of these leave the phone: photos and videos (the glasses'
camera frames for hand tracking, recordings saved to Movies/Pocket XR), audio (never recorded), app activity and
installed apps (the launcher's list), messages and notifications (the Notifications page), device or other IDs,
location, contacts, files.

Why "Shared: No": Google receives the statistics as the provider of the MediaPipe library that runs inside Pocket XR,
not as a third party Pocket XR passes data to. If Mike prefers the most cautious answer, mark both rows as also shared
with Google; nothing else changes.

### Permissions Play will show and why (for any reviewer question)

| Permission | Why |
| --- | --- |
| CAMERA | The glasses' camera for hand tracking; Android also requires it before granting USB access to a camera device |
| RECORD_AUDIO | Never records. Android shows "Always open Pocket XR" for USB glasses with a microphone only to apps holding it |
| INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE | The glasses' USB network (head tracking stream), the one-time hand model download |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA | Hand tracking on the glasses' camera while the user has started it |
| FOREGROUND_SERVICE_CONNECTED_DEVICE, RECEIVE_BOOT_COMPLETED | Keeping Pocket XR ready for the glasses' USB plug-in |
| POST_NOTIFICATIONS | The foreground services' notifications and setup prompts |
| BLUETOOTH_CONNECT | The Control Center's Bluetooth page (paired and connected devices) |
| VIBRATE | Trackpad taps |
| WAKE_LOCK | Added by a library (WorkManager) |
| moe.shizuku.manager.permission.API_V23 | Shizuku, which the user installs and starts |
| ADDON_LINK (signature) | Pocket XR's own link to its pointer and notification services (1.6.3). In the GitHub build it links the separate add-on, signed by the same key |
| BIND_ACCESSIBILITY_SERVICE, BIND_NOTIFICATION_LISTENER_SERVICE | Not held by Pocket XR: they guard its pointer service and notification listener (1.6.3), so only Android can bind them. The user switches each on in Android's settings after Pocket XR's disclosure |

Not in the release: SYSTEM_ALERT_WINDOW (debug builds only), QUERY_ALL_PACKAGES (not used; a `<queries>` element asks
only for launcher apps, DeX, Shizuku and the add-on), REQUEST_INSTALL_PACKAGES, any location permission,
FOREGROUND_SERVICE_DATA_SYNC (a library's unused service, removed from the manifest).

---

# Google Play listing: Pocket XR add-on

Not needed since 1.6.3: the Play build of Pocket XR carries the add-on's services itself, so the add-on does not go on
Play (docs/PLAY.md, "Two apps, one on Play"). Its Accessibility API declaration and disclosures are now Pocket XR's
(above). Kept for reference only.

| Field | Answer |
| --- | --- |
| App name (30 max) | Pocket XR Add-on |
| Category | Productivity |
| Contact email | connect@mikehaye.com |
| Privacy policy | https://github.com/pocket-xr/pocket-xr/blob/main/PRIVACY.md |
| Ads | No |
| Target audience | 18 and over only |
| Content rating | As Pocket XR (All other app types, No to everything) |

Short description:

```
The cursor and Notifications page for Pocket XR on XREAL glasses
```

Full description:

```
The Pocket XR add-on adds two optional features to Pocket XR, the multi-window workspace for XREAL glasses:

- The Pocket XR pointer: a cursor on the glasses outside Pocket XR's Space, moved with your hands or your phone's screen, which clicks, moves and resizes windows in Samsung DeX on the glasses.
- The Notifications page: your phone's notifications in the glasses, which you can open and clear.

Pocket XR is not an accessibility tool. The pointer uses Android's AccessibilityService API only to draw the cursor on the glasses, read where windows are on the glasses display, and perform the taps and drags you make there. The Notifications page uses notification access to read your notifications and show them in the glasses. The add-on explains each one and asks before either is switched on, and you can switch either off in Android's settings at any time.

Nothing the add-on reads is stored, collected or sent off the phone. It is shared only with the Pocket XR app on the same phone.

It works only with Pocket XR installed. Pocket XR is not made by or affiliated with XREAL. "XREAL" is XREAL's trademark.
```

Graphics: the same icon and feature graphic as Pocket XR, and screenshots 04 and 05 from [images/addon/](images/addon/)
(the Hands card and the pointer's disclosure) plus a screenshot of the notification disclosure if Mike wants a third.

Data safety: collects nothing, shares nothing ("No" to the first question). Its permissions: none beyond the two
services' bind permissions and the ADDON_LINK signature permission.
