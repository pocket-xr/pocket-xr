# Pocket XR privacy policy

Last updated: 5 November 2026

This policy covers two Android apps published by Haye & Co (Mike Haye): **Pocket XR** (`com.hayeco.flipspaces`) and the
**Pocket XR add-on** (`com.hayeco.flipspaces.addon`). It says what they access on your phone and what, if anything,
leaves it.

## The short version

Pocket XR and the add-on collect no personal data. There are no accounts, no ads, no analytics of Haye & Co's own and no
crash reporting services. Nothing they read on your phone (your apps, notifications, windows, camera frames or settings)
is sent to Haye & Co or to anyone else. What does go over the internet is listed under
[What goes over the internet](#what-goes-over-the-internet): two downloads that send nothing about you, and the usage
statistics that Google's MediaPipe library, which runs hand tracking, sends to Google.

## What Pocket XR accesses on the phone

Everything below is used on the phone only, while you use the feature, and is not sent anywhere.

- **Your XR glasses over USB**: their display, motion sensor stream (for head tracking) and camera.
- **Camera**: the glasses' own camera, for hand tracking. Each frame is processed on the phone to find your hands and
  then discarded. Frames are never saved or sent. Android also needs this permission before it grants USB access to a
  camera device.
- **Microphone permission**: held only because Android offers "Always open Pocket XR" for USB glasses with a microphone
  only to apps that hold it. Pocket XR never records audio.
- **The list of installed apps** with a home screen icon, to show them in the launcher on the glasses.
- **Battery, cell signal and Wi-Fi state**, for the status row on the glasses.
- **Wi-Fi network names and paired Bluetooth devices**, for the Wi-Fi and Bluetooth pages of the Control Center, and to
  switch Wi-Fi or Bluetooth on or off, or forget a network, when you ask.
- **Shizuku** (optional, installed and started by you): lets Pocket XR act as the phone's shell to create a window for
  each app on the glasses and run the Control Center's switches. Pocket XR uses it only for those features.
- **Notifications** that Pocket XR itself posts (for example the one that keeps it ready for your glasses).

## What the Pocket XR add-on accesses

The add-on is optional. Each of its two services stays off until you read its explanation in the add-on, tap "Agree
and turn on", and switch it on in Android's settings. You can switch either off in Android's settings at any time.

- **The Pocket XR pointer (Android's AccessibilityService API).** Pocket XR is not an accessibility tool; the service
  gives your glasses a cursor outside the Space. It reads where app windows are on the glasses display (their position,
  size and window title) and the labels of the buttons in Samsung DeX's window title bars there, to find DeX's full
  screen button. It draws the cursor on the glasses and performs the taps and drags you make there. It does nothing on
  the phone's own screen and does not read what you type or what is inside app windows.
- **Notification access.** It reads your notifications (the app, title and text of each, and when it came) and which
  apps are playing media, to show them on the Space's Notifications page in your glasses, open a notification's app when
  you press it, and dismiss the ones you clear.

What the add-on reads is passed only to the Pocket XR app on the same phone, through a link that only an app signed by
the same developer can use. It is not stored and is not sent off the phone.

## What is saved on the phone

- Your settings, window layouts, presets and hand calibration, in Pocket XR's private storage.
- A diagnostic log of Pocket XR's own messages, in Pocket XR's folder on the phone's storage
  (`Android/data/com.hayeco.flipspaces/files/logs`), at most 64 MB, overwritten as it fills. It stays on the phone. It
  leaves only if you share a glasses report yourself, with the app and to the people you choose, or send it with "Send
  logs to Mike" ([below](#send-logs-to-mike)).
- **Recordings** you start with "Record the glasses": a video of what the glasses show, without audio, saved to
  `Movies/Pocket XR` on your phone. They stay there until you delete or share them.

Uninstalling an app removes its private storage. Recordings in `Movies/Pocket XR` stay until you delete them.

## What goes over the internet

- **The hand tracking model**: once, Pocket XR downloads Google's MediaPipe hand landmark model from
  `storage.googleapis.com`. The request carries nothing about you; like any web request, it shows Google your IP
  address. See [Google's privacy policy](https://policies.google.com/privacy).
- **The update check** (only in copies installed from GitHub, never in a copy from Google Play): at most once a day,
  Pocket XR asks GitHub's public API for the latest release. It sends nothing about you; GitHub sees your IP address, as
  with any web request. You can turn it off with "Check for updates". See
  [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **The add-on install** (only when you tap Install add-on, and never in a copy from Google Play): Pocket XR asks GitHub's
  public API for the list of releases and downloads the newest add-on APK from GitHub. It sends nothing about you; GitHub
  sees your IP address, as with any web request.

- **MediaPipe usage statistics**: hand tracking runs on Google's MediaPipe library, which itself sends Google usage
  statistics while hand tracking runs: the app's package name and version, the MediaPipe and Android versions, which
  MediaPipe task ran and in which mode, how long it took, how many frames it handled or dropped, and error codes. It
  sends no camera frames, no hand positions and no identifiers of you or your phone, over an encrypted connection.
  Haye & Co does not receive these statistics. They are sent only while hand tracking is on; Pocket XR works without
  hand tracking (the trackpad and the Spatial Mouse do not use it). See
  [Google's privacy policy](https://policies.google.com/privacy).

Pocket XR also talks to your glasses over USB: the USB network some glasses create, or on the XREAL Air family their
motion sensor's USB interface. That traffic stays between the phone and the glasses.

## Send logs to Mike

When you tap "Send logs to Mike" in Settings or on the head tracking screen, Pocket XR puts its own log, the head
tracking diagnostics and a note of your app version, phone model, Android version and glasses into one zip. It takes out
email addresses, account names, Wi-Fi names, Bluetooth device names, MAC addresses and IP addresses (other than the
glasses' own link) first. Then your email app opens with the zip attached and addressed to me at connect@mikehaye.com.
Nothing is sent until you tap send there. I read what you send only to fix the problem you tell me about, and I don't
share it with anyone.

## Sharing

Haye & Co does not receive, sell or share any data from these apps, because the apps send it none. The only data that
leaves the phone automatically is MediaPipe's usage statistics above, which go to Google.

## Children

Pocket XR is meant for adults and is not directed at children.

## Changes

Changes to this policy are published in this file, with the date above. The history is in the repository's commits.

## Contact

Haye & Co, Mike Haye: [connect@mikehaye.com](mailto:connect@mikehaye.com)
