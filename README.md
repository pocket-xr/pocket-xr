<p align="center">
  <img src="docs/brand/pocket-xr-logo-512.png" width="128" alt="Pocket XR icon">
</p>

<h1 align="center">Pocket XR</h1>

<p align="center">
  <b>A spatial desktop for AR glasses, run from your Android phone.</b><br>
  Free, open source, no ads, no accounts.
</p>

<p align="center">
  <a href="https://github.com/pocket-xr/pocket-xr/releases/latest"><img src="https://img.shields.io/github/v/release/pocket-xr/pocket-xr?label=download&color=5A93FF" alt="Latest release"></a>
  <a href="https://github.com/pocket-xr/pocket-xr/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/pocket-xr/pocket-xr/ci.yml?branch=main&label=build" alt="Build status"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0-blue" alt="GPL-3.0"></a>
</p>

<p align="center">
  <a href="https://github.com/pocket-xr/pocket-xr/releases/latest"><b>Download</b></a> ·
  <a href="https://youtu.be/H8dBKm-I6zw"><b>Watch the demo</b></a> ·
  <a href="#setup">Setup</a> ·
  <a href="#supported-glasses-and-phones">Glasses and phones</a> ·
  <a href="https://github.com/pocket-xr/pocket-xr/discussions">Discussions</a> ·
  <a href="https://github.com/pocket-xr/pocket-xr/issues/11">Roadmap</a>
</p>

<p align="center"><img src="docs/media/demo.gif" width="720" alt="Pocket XR on XREAL glasses: windows all around you, looking around, opening an app, moving and resizing a window, and the Control Center"></p>

<p align="center"><a href="https://youtu.be/H8dBKm-I6zw">▶ Watch the full demo video (46 s)</a></p>

Pocket XR turns a phone and a pair of AR glasses into a multi-window workspace. It started on XREAL glasses, and
thanks to community testers it now runs on more XREAL models and on VITURE's Beast too. Your apps float around you as
windows you can move, resize, curve and push back. Head tracking keeps them in place as you look around, and the
phone becomes a trackpad, a Spatial Mouse you point, or you use your hands.

> [!NOTE]
> Pocket XR is a community project. It is not made by or affiliated with XREAL or VITURE. Thanks to XREAL for
> approving its release.

## Supported glasses and phones

This list only says what someone has actually seen working. Thanks to everyone who tested and posted.

### Glasses

**Tested by Mike**

| Glasses | Head tracking | Hand tracking | Notes |
| --- | --- | --- | --- |
| XREAL Aura | Yes | Yes, through its camera | The glasses Pocket XR was built on. |
| XREAL One | Yes | Not yet: possible with the XREAL Eye add-on (help wanted) | Use **Follow mode** with **Stabilizer off** ([Setup](#set-up-your-glasses)). |

**Confirmed by community testers**

| Glasses | Tested by | Head tracking | Notes |
| --- | --- | --- | --- |
| XREAL One Pro | @thejoeba (Retroid Pocket Nova, Galaxy S24+), @Tayohka (Galaxy Z Flip 7) | Yes | Follow mode, Stabilizer off. thejoeba: "this has become my primary xr app". |
| XREAL 1S | @WhiteNoise (XREAL Beam Pro) | Yes | Works once the glasses are set up as in [Setup](#set-up-your-glasses). |
| VITURE Beast | @Tayohka (Galaxy Z Flip 7) | Yes, on by default since 1.2.12 | 3D works once it's on in the glasses' menu. 32:9 ultrawide works as one wide screen. |
| XREAL Air 2 | @ctdf (OnePlus 15) | Yes, since 1.1.12 | Windows work. A level horizon fix (1.2.3) is waiting on a retest. Pocket XR can't switch the Air to 3D by itself yet ([#54](https://github.com/pocket-xr/pocket-xr/issues/54)). |

**Early or experimental support**

| Glasses | Where it stands |
| --- | --- |
| XREAL One Pro+ | @gegonut ran it on a Galaxy Z Fold 6. Windows showed, but after a recenter they sat below the view. 1.2.13 recognises the One Pro+, and recenter now follows head tilt, so windows land where you look even lying back. A retest by @gegonut is pending ([Discussion #81](https://github.com/pocket-xr/pocket-xr/discussions/81)). |
| XREAL Air, Air 2 Pro, Air 2 Ultra | Same head tracking as the Air 2 since 1.1.12. No tester yet. |
| Other VITURE glasses | Not tried yet. Experimental VITURE head tracking is on the head tracking screen, off by default ([Discussion #24](https://github.com/pocket-xr/pocket-xr/discussions/24)). |
| Other glasses | Help wanted. Windows are drawn with plain fallbacks; head tracking works only if they send it the way a known model does. |

Hand tracking works on the XREAL Aura only for now. Details and settings for each model:
[docs/GLASSES.md](docs/GLASSES.md).

### Phones

Pocket XR needs a phone or handheld whose USB-C port carries video (DisplayPort alt mode), running Android 11 or
later. These are known to work:

| Phone | Tested by | Notes |
| --- | --- | --- |
| Samsung Galaxy Z Flip 7 | Mike, @Tayohka | The phone Pocket XR is developed on. The trackpad also works on the cover screen with the phone closed. Hand tracking runs on the Exynos 2500's NPU (GitHub builds; the Play build uses the GPU). |
| Samsung Galaxy Z Fold 6 | @gegonut | With the XREAL One Pro+. |
| Samsung Galaxy S24+ | @thejoeba | Android 16, with the XREAL One Pro. |
| Samsung Galaxy S20 FE | John Davis | Android 11, with the XREAL One. Runs since 1.1.1. Head tracking over its second USB link came in 1.1.9 and is waiting on a retest ([findings](docs/findings-john-s20fe-android11.md)). Chrome opens one window at a time on Android 11. |
| OnePlus 15 | @ctdf | With the XREAL Air 2. OnePlus keeps three-finger swipes for itself, so open the Control Center from the Menu. |
| Retroid Pocket Nova | @thejoeba | Android 13 handheld, with the XREAL One Pro. Starts on the Horizontal Trackpad. |
| XREAL Beam Pro | @WhiteNoise | With the XREAL 1S. Close XREAL's own spatial view before opening the Space. |

Other phones with video out should work. Own glasses or a phone not marked as confirmed? A Glasses report helps a lot
(Menu, then Pocket XR Settings, then Advanced, then Glasses report). Post it in
[Discussions](https://github.com/pocket-xr/pocket-xr/discussions) or [the testers issue](https://github.com/pocket-xr/pocket-xr/issues/12).

## What it does

- **Windows in space.** Any Android app opens as a window around you, at its own size and distance. You can drag a
  window by its edge, snap it next to others, curve it, or push it further and pull it closer.
- **Lock screens.** One tap on the Lock button by the window keys, or in the Menu, keeps every window where it is:
  nothing moves or resizes, while clicks, scrolls and typing still reach the apps. A small lock shows on a locked
  window, and the setting is kept after a restart. Unlocked, a window resizes only from a clear drag on the blue handle
  at its bottom right corner.
- **All windows at once.** Hold both hands up, or use Adjust all, to move, push, pull or resize every window together.
- **Head tracking.** Windows stay where you put them as you turn your head. It reads the glasses' own motion sensor
  (1000 times a second on XREAL's One family and the Aura), paced to the glasses' display. This is 3DoF (turning, not walking around); 6DoF is open for the
  community to build (see below).
- **3D side by side.** Each eye gets its own view, so windows sit at a real depth. Pocket XR switches the glasses
  into 3D by itself where they allow it.
- **Your phone as the controller.** A trackpad, with taps, two-finger scroll and pinch and three-finger swipes; a
  Spatial Mouse you point like a laser; and a scroll strip. On a Galaxy Z Flip it also works on the cover screen.
  A Bluetooth or USB mouse, on the phone or a Beam Pro, moves the same pointer: left click clicks, right click is a
  long press, the wheel scrolls the window under the pointer, and dragging a window's edge or handle moves or resizes
  it (unless Lock screens is on). A hardware keyboard types into the window you last clicked.
- **Hand tracking**, on glasses with a camera: point, pinch and drag, with each hand separately. It works on the
  XREAL Aura today. The One and One Pro can get it through the XREAL Eye camera add-on, which is next (help wanted).
- **An app launcher, a Control Center** (arrange layouts, presets, Wi-Fi, Bluetooth, quick settings) and **Wide
  view**, which gives phone-shaped apps a landscape screen.

## Where the community can take it

I wanted to get 1.0 into people's hands rather than wait until everything was done, so some big pieces are left open
on purpose. These are the ones I'm most excited to see the community build:

- **6DoF tracking.** Pocket XR follows your head turning (3DoF) today. With the cameras on the XREAL Aura and the
  XREAL Eye, 6DoF is possible, so you could lean in and walk around your windows.
- **Hand tracking on the One and One Pro**, through the XREAL Eye camera add-on.
- **More glasses and phones.** Testing and Glasses reports from anything under "Early or experimental support", and
  from glasses and phones not on the list yet.

The [Roadmap](https://github.com/pocket-xr/pocket-xr/issues/11) has the full list, and
[the testers issue](https://github.com/pocket-xr/pocket-xr/issues/12) is the easiest place to start.

## Setup

1. **Install Pocket XR** from the [Releases](https://github.com/pocket-xr/pocket-xr/releases/latest) page.
   You can check it's the official build with the signing fingerprint under [Updates](#updates), and all the code is
   here to read.
2. **Install and start [Shizuku](https://shizuku.rikka.app/)**, and allow Pocket XR in it. Pocket XR uses it to
   create a window for each app and to start the Space on the glasses. Without Shizuku, apps open as ordinary desktop
   windows instead.
3. **Optional: install [the add-on](#the-add-on)** for your phone's notifications in the glasses and the cursor outside
   the Space. The trackpad's first tutorial and the glasses setup check have an **Install add-on** button: it downloads
   the newest add-on and opens Android's install prompt. The first time, Android asks you to allow Pocket XR to install
   apps; tap **Allow installs**, switch it on, and come back. Once it is installed, the same place has a button for the
   add-on's notification access. You can skip it and install it later.
4. **Plug in the glasses.** The Space opens on them, and the phone shows the trackpad.
5. **On Samsung phones**, if touches at the screen's edges go missing (the scroll strip), turn on Good Lock's Edge Touch
   for the trackpad.
6. **Run the glasses setup check.** It opens by itself the first time a new glasses model connects, and any time from
   Pocket XR Settings or the trackpad's Menu ("Glasses setup check"). It shows only the steps your glasses need, then
   asks you to turn your head for 10 seconds and say what the windows did. Once it passes it never comes up again.

### Set up your glasses

Pocket XR holds the windows in place as you turn your head. Glasses that can do this on their own must leave it to
Pocket XR, or the windows move twice.

- **XREAL One, One Pro and 1S:**
  1. Press the **X** button on the glasses once to switch display mode. Pick **Follow** mode, not Anchor.
  2. Press **X** twice to open the glasses' menu. Go to **Spatial Screen**, then **Stabilizer**, and turn it **off**
     (on older firmware Stabilizer is under **Display**). In the menu, **+** and **-** move, **X** picks, and **X**
     twice goes back.
- **XREAL Air, Air 2, Air 2 Pro and Air 2 Ultra:** nothing to set on the glasses.
- **XREAL Aura:** nothing to set; Pocket XR sets the Aura's display itself.
- **VITURE Beast:** nothing to set. For 3D, turn it on in the glasses' menu first, then use the Depth button.
- **On a Beam Pro:** XREAL's own spatial view can also turn the picture with your head. Close it, then open the Space
  from Pocket XR. The Beam Pro may switch One series glasses back to Anchor, so check Follow again if windows misbehave.
- **XREAL's app on a phone** (Nebula and the like) does no harm while closed. If it is open, close it from Recents
  before you open the Space.

### Windows drift or get cut off

If windows move faster than your head, slide back after you stop turning, or get cut off past a certain angle, the
glasses (or XREAL's software on the phone) are moving the picture as well as Pocket XR:

- Moving faster than your head, or cut off at the sides: the glasses are in **Anchor** mode. Press **X** once for
  **Follow** mode.
- Sliding back slowly after each turn: **Stabilizer** is on. Turn it off as in step 2 above.
- On a Beam Pro, or with XREAL's app open: close XREAL's spatial view or app as above.

The glasses setup check walks through this and points to the one setting to change.

### The add-on

Some features need an accessibility service or notification access. In some countries Google Play Protect blocks
installing any sideloaded app that asks for these ("This app can request access to sensitive data"). So they live in a
second small app, the **Pocket XR add-on**, and Pocket XR itself installs everywhere. The Space, the trackpad and hand
tracking in the Space all work without it.

The add-on gives you:

- **The Pocket XR pointer** (an accessibility service): the cursor on the glasses outside the Space, in flat DeX mode.
  It draws the cursor and taps where you point. It does nothing on the phone's own screen and sends nothing off the
  phone. Pocket XR's Hands screen has a button for it: the add-on says what the service reads and why, and once you
  agree it opens Android's page to switch it on.
- **Notification access**: your phone's notifications on the Space's Notifications page, and smooth video for windows
  playing media. Press Allow on that page, read what the add-on reads and why on the phone, agree, and switch it on in
  Android's page.

What both read and what never leaves the phone is in the [privacy policy](PRIVACY.md).

Tap **Install add-on** in the trackpad's tutorial or at the end of the glasses setup check, or get it yourself from the
[add-on releases](https://github.com/pocket-xr/pocket-xr/releases?q=addon&expanded=true)
(`pocketxr-addon-x.y.apk`). If the one-tap install does not work, it says why and offers that page. It is signed with the same key as Pocket XR, and Pocket XR only talks to an add-on signed
with that key. If Play Protect blocks the add-on, install it from a computer with `adb install pocketxr-addon-x.y.apk`,
which the block does not cover, or turn off "Scan apps with Play Protect" in the Play Store for the install.

## Help get Pocket XR on Google Play

Pocket XR needs 12 testers in its Google Play test for 14 days before it can go public on Play. If you'd like to
help, it takes two taps:

1. Join the testers group: [groups.google.com/a/mikehaye.com/g/pocketxr-testers](https://groups.google.com/a/mikehaye.com/g/pocketxr-testers).
2. Open the Play invite and tap Become a tester:
   [play.google.com/apps/testing/com.hayeco.flipspaces](https://play.google.com/apps/testing/com.hayeco.flipspaces).

Then install Pocket XR from Play. You'll need to uninstall the copy you have first, as the Play version is signed with
a different key. That resets your Pocket XR settings. The trackpad's Menu has a card with both links.

## Updates

Every update is free. There are three ways to get them:

- **In the app.** Pocket XR checks GitHub Releases when it starts and every 12 hours in the background. When a newer
  version is out, "Update available" shows across the top of the trackpad and of Pocket XR Settings, with a line in the
  Menu and on the glasses' Control Center, and one notification says "Pocket XR x.y.z is ready". Tap Update to download
  it, or Later to hide it until the next version. On Android 13 and later, Pocket XR asks once to allow notifications
  when it first finds an update; if you say no, the banner still shows. "Check for updates", with the version you have,
  is at the top of Pocket XR Settings, where you can turn the check off or check now. The check sends nothing about you
  or your phone: it is a plain request to GitHub's public API. The Google Play version never checks GitHub; Play
  updates it.
- **[Obtainium](https://github.com/ImranR98/Obtainium)**, which updates apps straight from their GitHub releases. In
  Obtainium, tap Add App and paste `https://github.com/pocket-xr/pocket-xr`. It then shows each new release and
  installs it with a tap.
- **F-Droid**: a listing is planned.

Updates install over the version you have and keep your windows and settings, as long as they are signed with the same
key. Every official release is, and you can check it: the release key's SHA-256 certificate fingerprint is

```
33:EA:D4:CD:FA:C9:57:16:96:AB:CB:2A:EA:44:65:89:1C:30:ED:1D:82:57:12:78:A4:A5:4B:87:0B:A2:93:AD
```

(`apksigner verify --print-certs pocketxr-x.y.z.apk` shows it; Obtainium and AppVerifier can check it for you).

## Building from source

You need JDK 17, the Android SDK (compile SDK 37) with NDK and CMake 3.22.1, and Gradle 9.8.2.

```bash
gradle :core:test
gradle :app:assembleDebug
```

`core/` is plain Kotlin with no Android dependencies: the window maths, head tracking fusion, gestures and protocol
parsing, with over 1,600 unit tests. `app/` is the Android app. [CONTRIBUTING.md](CONTRIBUTING.md) has the details.

## Contributing

Bug reports, glasses reports and pull requests are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md), and look for
issues labelled `good first issue`. Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md). How the
project is run is in [GOVERNANCE.md](GOVERNANCE.md).

## About

Pocket XR is made by **Mike Haye**, an XREAL supporter since the original Nreal Air, as a free project for everyone who
loves these glasses. I built it for myself with my Aura glasses and the references I could find, then developed it
with Claude, an AI assistant, to the point where the community can run with it. It is written from scratch, and XREAL
has approved its release; it is not made by or affiliated with XREAL or VITURE. "XREAL", "VITURE" and the glasses'
names are their makers' trademarks, used here only to say what the app works with.

- **Need something built?** I'm available for XR and Android work: connect@mikehaye.com.

## Support

[![Buy me a beer on Ko-fi](https://img.shields.io/badge/Buy%20me%20a%20beer-Ko--fi-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/mikehaye)

Enjoying Pocket XR? You can buy me a beer on [Ko-fi](https://ko-fi.com/mikehaye). It's never required; a star or a
glasses report helps just as much. In the app, it's the "Buy me a beer" row at the top of the trackpad's Menu (left
out of the Google Play version).

## Licence

Pocket XR is free software under the [GNU General Public License v3.0](LICENSE). Third-party credits are in
[docs/LICENSES.md](docs/LICENSES.md). The name "Pocket XR" and its icon are covered by [TRADEMARK.md](TRADEMARK.md):
forks are welcome under another name.
