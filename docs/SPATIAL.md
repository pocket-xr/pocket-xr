# Spatial windows in Pocket XR

This is the research behind the Space, written in October 2026 for versions 0.3 and 0.4, with notes on what each of
those versions built. It is kept because it explains why the Space works the way it does: head tracking from the
glasses' sensor stream, app windows on Shizuku-created virtual displays, and input routed back by display id. Items
marked **Verify** were open when written; sections 5a to 5d record what the phone showed. The code has moved on since
(see the README for what the app does now), so treat version numbers and plans here as history.

Goal at the time: an app on a Galaxy Z Flip 7 (Android 17, One UI 9) with XREAL Aura glasses plugged into USB-C
(display "XREAL 1 Ultra", 1920x1080 at 120 Hz; USB "XREAL Aura" 0x3318:0x043A) where app windows float anywhere around
the user and stay put as the head turns, instead of sitting inside DeX's flat monitor, with hand tracking as good as a
single camera allows.

Everything here comes from public documentation, AOSP source, public open source projects and our own tests on the
phone. No code from proprietary apps or SDKs was used.

Status markers: **Public API** (a public Android API), **Adb** (needs an adb or shell command), **Shizuku** (needs
Shizuku's shell identity), **Verify** (to be checked on the phone).

How this was researched: web search plus direct reads of GitHub sources. The research sandbox
could not fetch developer.android.com, source.android.com, android.googlesource.com,
docs.xreal.com, tutorials.xreal.com, GitLab or arxiv.org (proxy refused), so:

- Framework behaviour was read from GitHub mirrors of the framework source: LineageOS
  `lineage-23.1` (Android 16 based, called **LOS16** below) and `aosp-mirror` `main` (frozen in
  March 2025). Android 17 and Samsung's One UI changes could not be read. **Verify** on the phone.
- Facts from pages that could not be fetched come from search result summaries and are marked
  "(snippet)".

## 0. Summary

1. **Head tracking.** Nothing public supports 0x3318:0x043A (Aura / "XREAL 1 Ultra"). But its
   USB layout (1024 byte HID control interface, UVC, NCM and ECM network, button HID) matches the
   XREAL One family, whose IMU is streamed over TCP on the glasses' USB network link
   (169.254.2.1, port 52998), not over HID. Several MIT licensed projects document that stream,
   including an Android library (`Skarian/one-xr`). It gives gyro, accelerometer and
   magnetometer at up to about 1 kHz: enough for good 3DoF. Nothing public gives 6DoF on this
   family. Whether the Aura firmware serves the same stream is the first thing to test.
2. **Onboard anchoring.** The One series anchors the picture in its own X1 chip (button click
   toggles Follow / Anchor). No documented setting or public command lets a phone app switch it
   on. Whether Aura's X1S anchors on its own when plugged into a phone (not its puck) is not
   documented. For app-side spatial rendering the glasses must be in Follow mode anyway.
3. **3D windows.** A normal app cannot run other apps on its own VirtualDisplays (they are
   untrusted, so only `allowEmbedded` activities launch there). **With Shizuku it can**: the
   shell identity creates trusted virtual displays, launches any app onto them, injects touches
   by display id and sets the IME policy. Each display renders into a `SurfaceTexture` that
   Pocket XR draws as a panel in an OpenGL ES scene on the glasses. scrcpy, ShadowScreen and
   android-display-extend prove each piece. DRM video (FLAG_SECURE) will be black on these
   panels whatever we do.
4. **Hands.** MediaPipe on one RGB camera is a different class from the stereo mono tracking in
   Quest, Galaxy XR and Aura's own Android XR. Realistic: 45 to 80 ms hand to cursor at 60 fps
   (60 to 110 ms at 30 fps), centimetre error, good for pointing at and grabbing large targets,
   poor for fine text selection and fast flicks. The biggest wins are 60 fps with short
   exposure, head-pose compensation of hand rays, a body-anchored ray, pinch from world
   landmarks with a cursor freeze, One Euro filter plus one frame of prediction.
5. **Recommended design** (section 5): a full screen "Space" surface on the
   glasses with a GLES 3 renderer, a `HeadTracker` reading the One-family IMU stream, panels on
   Shizuku-created virtual displays, a world-space pointer driven by head gaze, trackpad and
   hands, and the current DeX flat mode as the fallback when Shizuku or the IMU is missing.

One hardware correction: the Galaxy Z Flip 7 is reported to use Samsung's **Exynos 2500**
(Xclipse 950 GPU, AMD RDNA based), not a Snapdragon 8 Elite. Check `adb shell getprop
ro.soc.model`. The performance reasoning below holds for either; Adreno specific numbers do not.
([Android Authority](https://www.androidauthority.com/galaxy-z-flip-7-exynos-2500-3575603/),
[PhoneArena](https://www.phonearena.com/news/galaxy-z-flip-7-could-be-powered-by-samsung-chipset_id170626))

## 1. Head tracking

### 1.1 Two protocol families

**Air family (HID).** Air, Air 2, Air 2 Pro and Air 2 Ultra stream the IMU on a HID interface
(interface 3; 2 on the Air 2 Ultra). Packets start with 0xAA, a 4 byte checksum, a 2 byte length
and a command id; command 0x19 with payload `[1]` starts the stream; gyro and accelerometer are
24 bit integers with a multiplier and divisor.
([ar-drivers-rs nreal_air.rs](https://raw.githubusercontent.com/badicsalex/ar-drivers-rs/master/src/nreal_air.rs))

**1.1.10: HID glasses in the app.** `HeadImuTransport.choose` (core) sends the One family and the Aura (0x0435 to
0x043A, 0x043D, 0x043E, and any XREAL id with a CDC NCM or ECM interface) to the network path, and the Air family,
Helen and Helen Pro (0x0423 to 0x0432, 0x043F to 0x0442) and any XREAL id with HID but no CDC network to "HID". The
app has no HID IMU reader: for HID glasses `HeadTracker` opens nothing on them and reports
`HeadTrackingStatus.HidGlasses`. A reader needs, per model, the HID interface and endpoint, the start command (the Air
family's 0x19 above is not confirmed for Helen, which may use its own IMU format), the checksum
and the sample layout, checked on real glasses before anything is sent to them.

**VITURE (HID, 1.2.8, experimental, off by default).** VITURE's glasses use their own vendor id, 0x35CA, and no USB
network. The one public, MIT licensed description of their newer protocol
([elasticjava/viture-v2 PROTOCOL.md](https://github.com/elasticjava/viture-v2/blob/master/PROTOCOL.md), measured on a
Pro 2, 35CA:1301): one HID interface with 64 byte interrupt IN and OUT endpoints; little endian frames of preamble
0x0010, message id, payload length and a plain byte sum checksum, then the payload; 0x0301 `[streams, rate]` starts or
stops the IMU (0x02 raw, rate index 2 is 120 Hz); 0x7309 carries a sample counter, gyro in rad/s and accelerometer in
g as float32. Axes are not described. `VitureImuProtocol` (core) and `VitureImuLink` (app) read it only with the head
tracking screen's switch on; otherwise `HeadTrackingStatus.VitureNotYet` says plainly that it isn't supported yet. No
VITURE SDK (closed) and no code under a licence that forbids it is used. Other public notes describe an older format
with Euler angles; they carry no licence, so nothing from them is used. See docs/GLASSES.md, "VITURE glasses".

**One family (network).** One, One Pro and 1S appear to the host as a USB network device
(NCM / ECM) with the glasses at `169.254.2.1`:

- TCP **52998** streams sensor reports; TCP **52999** takes control commands.
- Each frame: 2 byte magic, 4 byte big endian length, payload. Sensor reports use magic
  `0x28 0x36`, 128 bytes: device id (u64), timestamp in ns (u64), report type at 0x18 (0x0B IMU,
  0x04 magnetometer), gyro float32 x3 at 0x1C, accelerometer float32 x3 at 0x28, magnetometer
  float32 x3 at 0x34, temperature at 0x40.
  ([xr-tools reports.rs](https://raw.githubusercontent.com/0xcaff/xr-tools/main/packages/xreal_one_driver/src/proto/net/reports.rs),
  [xreal-one-cli README](https://raw.githubusercontent.com/georgehorrell/xreal-one-cli/main/README.md))
- Rate about 1000 Hz ([One-Pro-IMU-Retriever-Demo](https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo)).
- A separate USB HID control channel (header 0xFD, CRC32, length, request id, 2 byte command,
  **1024 byte buffer**) handles firmware info, updates and the camera, not the IMU. The 1024 byte
  size matches Aura's If0.
  ([xr-tools endpoint.rs](https://raw.githubusercontent.com/0xcaff/xr-tools/main/packages/xreal_one_driver/src/proto/usb/endpoint.rs))

### 1.2 Projects, licences, models

| Project | Licence | Models (PIDs from source) | Transport | Android | 6DoF |
|---|---|---|---|---|---|
| [badicsalex/ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs) | MIT | Air 0x0424, Air 2 0x0428, Air 2 Pro 0x0432, Air 2 Ultra 0x0426; also XREAL Light, Rokid and others | HID | Not stated | No |
| [wheaney/XRLinuxDriver](https://github.com/wheaney/XRLinuxDriver) ([PID list](https://raw.githubusercontent.com/wheaney/XRLinuxDriver/main/src/devices/xreal.c)) | GPL-3.0 | Air family on HID; One Pro 0x0435/0x0436, One 0x0437/0x0438, 1S 0x043D/0x043E on the network protocol | Both | No (Linux) | No |
| [wheaney/xrealOneDeviceKit](https://github.com/wheaney/xrealOneDeviceKit), [xrealAirDeviceKit](https://github.com/wheaney/xrealAirDeviceKit) | No licence file | One family / Air family | Network / HID | No | No |
| [TheJackiMonster/nrealAirLinuxDriver](https://gitlab.com/TheJackiMonster/nrealAirLinuxDriver) | Not confirmed (GitLab unreachable) | Air family | HID | No | No |
| [wheaney/breezy-desktop](https://github.com/wheaney/breezy-desktop) | GPL-3.0 | Via XRLinuxDriver | | Linux only | No |
| [Monado drv_xreal_air](https://monado.pages.freedesktop.org/monado/group__drv__xreal__air.html) | BSL-1.0 | Air family (snippet) | HID | No | 3DoF only; no Monado One driver found |
| [rohitsangwan01/xreal_one_driver](https://github.com/rohitsangwan01/xreal_one_driver) | MIT | One | TCP 52998 | No | No |
| [SamiMitwalli/One-Pro-IMU-Retriever-Demo](https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo) | MIT (asks for credit) | One Pro | TCP 52998, about 1 kHz | No | No (complementary filter) |
| [0xcaff/xr-tools](https://github.com/0xcaff/xr-tools) | No licence file | One Pro 0x0436, One 0x0438 (recovery 0x0435 / 0x0437) | HID control plus TCP | No | No |
| **[Skarian/one-xr](https://github.com/Skarian/one-xr)** | **MIT** | One / One Pro | TCP 52998/52999 through Android `Network.socketFactory` | **Yes, Kotlin library and demo, minSdk 26** | No; 3DoF from IMU and magnetometer |
| [georgehorrell/xreal-one-cli](https://github.com/georgehorrell/xreal-one-cli) | MIT | One / One Pro (firmware 1.8.1) | TCP | macOS | No |
| [kotXio/xreald](https://github.com/kotXio/xreald) | Apache-2.0 | 1S 0x043E only | USB network plus input | Linux | No |
| [kotXio/xreal-1s-lab](https://github.com/kotXio/xreal-1s-lab) | MIT | 1S | | | No |
| [BladeRunner28/XrealBeam](https://github.com/BladeRunner28/XrealBeam) | No repo licence (protocol ported from an MIT project) | Air family | HID, interface 3 (2 on Ultra) | **Yes, Android USB host, Android 13+** | No |

6DoF: none of these give 6DoF on the One family. The only public 6DoF work is camera based on the
Air 2 Ultra (for example [mazeasdamien/myXreal](https://github.com/mazeasdamien/myXreal)).

Licence rule for Pocket XR: protocol facts (ports, offsets, magic numbers) can be reimplemented
from these descriptions. Code can be reused only from MIT / Apache / BSL projects with
attribution (`one-xr` is the obvious candidate). GPL-3.0 projects (XRLinuxDriver, Breezy) are
references only unless Pocket XR becomes GPL. Projects with no licence file grant no rights.

### 1.3 Does anything cover the Aura (0x043A)?

**No.** 0x043A is not in XRLinuxDriver's PID list, xr-tools, xreald or ar-drivers-rs, and GitHub
issue search for `043a xreal`, `xreal aura imu` and Aura in XRLinuxDriver returned nothing
([search](https://api.github.com/search/issues?q=043a+xreal)). "XREAL 1 Ultra" is not a public
product name; XREAL sells the 1S, 1 Pro+, One and One Pro ([1S](https://www.xreal.com/1s),
[1 Pro+](https://www.xreal.com/1-pro-plus)). It is the display name the Aura glasses report to the phone.

Why the One-family network protocol is the best bet (inference, **Verify**): the 1S (0x043E, the
newest X1S-era sibling) uses it; Aura has the same NCM / ECM network interfaces and the same
1024 byte HID control interface. XREAL documents a One series OSD setting, Developer > TCP/IP,
needed for Beam Pro and firmware updates (reached from Other > Serial Number, hold X 5 s)
([tutorials.xreal.com TCP/IP](https://tutorials.xreal.com/docs/glasses/one-series/osd/tcp-ip/), snippet).

Also unknown: whether XREAL supports the Aura glasses plugged straight into a phone at all. All
public material describes glasses into puck, with DisplayPort input on the puck's second USB-C
port ([GSMArena](https://www.gsmarena.com/xreal_aura_glasses_unveiled_with_android_xr_and_the_new_snapdragon_reality_elite-news-73315.php),
[vr.org](https://vr.org/articles/xreal-aura-1279-price-95-grams-x1s-coprocessor-2026)). Our own
tests show the glasses do enumerate and display directly from the Flip, so the hardware path
works; firmware behaviour in that mode is undocumented.

### 1.4 Onboard 3DoF and head locking

- XREAL One series: the X1 chip does native 3DoF in the glasses with any DisplayPort source,
  phones included, no app needed
  ([TechNode](https://technode.com/2024/12/11/xreal-launches-new-ar-glasses-xreal-one-with-native-3dof-spatial-tracking/)).
  The X button toggles Follow / Anchor, a long press recentres, a double press opens the menu;
  Stabilizer is under Display
  ([buttons](https://tutorials.xreal.com/docs/glasses/one-series/first-use/buttons/),
  [user guide](https://us.shop.xreal.com/blogs/buying-guide/user-guide_xreal-one-series), snippets).
- **No documented setting or public command lets a phone app switch Anchor on.** In public code,
  `one-xr`'s `SET_SCENE_MODE` (0x2829) only enables or disables the buttons
  ([Models.kt](https://raw.githubusercontent.com/Skarian/one-xr/main/onexr/src/main/java/io/onexr/Models.kt));
  xr-tools has `space_screen_set_eis_enable` (magic 0x28 0xA7), probably the Stabilizer toggle
  (unverified, [source](https://raw.githubusercontent.com/0xcaff/xr-tools/main/packages/xreal_one_driver/src/proto/net/space_screen_set_eis_enable.rs));
  display mode commands are rejected with status 10001
  ([UNRESOLVED.md](https://raw.githubusercontent.com/Skarian/one-xr/main/docs/UNRESOLVED.md)).
- Onboard Anchor mode pins the **whole 1920x1080 picture** to one spot. That is a cheap first
  step (DeX's monitor stays put in space), but it is not "windows anywhere around you": the
  canvas is one flat virtual screen. For Pocket XR's own spatial rendering, Anchor and
  Stabilizer must be **off** (Follow mode), otherwise the glasses and the app both compensate for
  head motion. XRLinuxDriver and `one-xr` both require this.
- Aura plugged into a phone: whether its X1S anchors on its own without the puck is undocumented.
  **Verify** by pressing the glasses' buttons with DeX showing and turning the head.

### 1.5 Android's standard head tracker sensor

Android 13+ turns a HID device on the Sensors page (0x20) with usage 0xE1 and the description
`#AndroidHeadTracker#1.0` (2.0 on Android 15+) into a dynamic `Sensor.TYPE_HEAD_TRACKER`, meant for
spatial audio
([AOSP head tracker HID protocol](https://source.android.com/docs/core/sensors/head-tracker-hid-protocol)).
No XREAL device is documented to expose it and Aura's HID interfaces do not look like it.
Cheap check: `SensorManager.getDynamicSensorList(Sensor.TYPE_ALL)` with the glasses attached.
In our tests no glasses sensors appeared in the sensor framework.

### 1.6 What Pocket XR would do

1. Find the glasses' network: `ConnectivityManager` networks with a 169.254.x.x link address on a
   USB Ethernet interface. **Verify** that One UI brings up the NCM / ECM interface at all
   (`adb shell ip addr`, `adb shell dumpsys ethernet`). If Android does not configure it, there is
   no route without root.
2. Open `network.socketFactory.createSocket("169.254.2.1", 52998)`; parse `0x28 0x36` frames;
   keep IMU (0x0B) and magnetometer (0x04) reports. Needs only `INTERNET` and
   `ACCESS_NETWORK_STATE`. **Public API**
3. Fuse in `core` (pure Kotlin, JVM tested): gyro integration into a quaternion, accelerometer
   tilt correction (Mahony or Madgwick, or a complementary filter as in the One Pro demo), gyro
   bias estimation while still, optional magnetometer yaw (often poor indoors; keep off by
   default and offer recentre instead).
4. Predict the pose to display time: rotate by the angular velocity times the motion to photon
   estimate (start at 20 ms, tune). This is the single most important thing for windows that
   "stay put".
5. Leave the HID control interface alone; the IMU does not need it.

Policy decision: this stream is not documented by XREAL. Implementing it from the descriptions in
public MIT projects keeps to the project's "public sources only" rule, and
the probe in milestone S0 is harmless (one TCP connection, read only). The credits are in
`docs/LICENSES.md`.

### 1.7 The USB network on One UI (Ethernet switched off)

On the Flip 7 (One UI 9) with the Aura plugged in, the glasses' USB Ethernet functions show up
as eth0 and eth1, but `dumpsys ethernet` reports "Ethernet State: disabled", so both stay down,
no IpClient runs and no ETHERNET Network appears. GlassesEthernetLink fixes this through
Shizuku: an `app_process` helper (EthernetShell) running as the shell user calls the hidden
IEthernetManager (AIDL unchanged in android16-release, android16-qpr2-release and main for the
calls used):

- `setEthernetEnabled(true)`: needs NETWORK_SETTINGS, which the shell holds.
- `releaseTetheredInterface`: only when the interface is in tethering server mode (mode 2);
  mode 1 is client mode and blocks nothing.
- `enableInterface` and `updateConfiguration` (static 169.254.2.2/24, no gateway, when DHCP
  gives nothing in 6 s): need MANAGE_ETHERNET_NETWORKS, which the shell does not hold, so they
  work only with Shizuku running as root; `setConfiguration` needs NETWORK_STACK.

The bring-up tries eth0 then eth1, checks 169.254.2.1:52998 answers, and puts every change
back (interface configuration, then Ethernet off if it was off) when the Space and the
diagnostics screen close. Each step's result is listed on the head tracking screen.

### 1.8 The app may not bind to the glasses' network (0.5.5)

With Ethernet on, the Flip 7 can list eth0 and eth1 as Networks while `Network.bindSocket` from
the app fails with EPERM (seen in a 0.5.4 test log). netd gives an app EPERM for a physical
network when a VPN it cannot bypass covers the app; a restricted network would be EACCES. The shell user
holds CONNECTIVITY_USE_RESTRICTED_NETWORKS, so netd treats it as PERMISSION_SYSTEM and lets it
bind anywhere, VPN or not. Since 0.5.5 a helper run as the shell user through Shizuku
(HeadStreamShell) binds, connects to 169.254.2.1:52998 and pipes the stream to the app; the app's
own socket is still tried first.

## 2. Rendering other apps' windows in 3D

### 2.1 Virtual display flags and permissions

From `DisplayManagerService.createVirtualDisplayInternal`
([DMS](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/display/DisplayManagerService.java))
and [DisplayManager.java](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/core/java/android/hardware/display/DisplayManager.java) (LOS16):

| Flag (bit) | Needs |
|---|---|
| PUBLIC (0) | Without OWN_CONTENT_ONLY it becomes AUTO_MIRROR, which needs CAPTURE_VIDEO_OUTPUT, CAPTURE_SECURE_VIDEO_OUTPUT, ADD_MIRROR_DISPLAY or a MediaProjection. **PUBLIC + OWN_CONTENT_ONLY needs nothing.** |
| PRESENTATION (1), OWN_CONTENT_ONLY (3) | Nothing |
| SECURE (2) | CAPTURE_SECURE_VIDEO_OUTPUT or a MediaProjection allowing secure video |
| SUPPORTS_TOUCH (6), ROTATES_WITH_CONTENT (7), DESTROY_CONTENT_ON_REMOVAL (8), TOUCH_FEEDBACK_DISABLED (13) | Nothing (hidden constants, the int values work) |
| TRUSTED (10), OWN_DISPLAY_GROUP (11) | ADD_TRUSTED_DISPLAY |
| ALWAYS_UNLOCKED (12) | ADD_ALWAYS_UNLOCKED_DISPLAY |
| SHOULD_SHOW_SYSTEM_DECORATIONS (9) | Dropped unless TRUSTED |
| OWN_FOCUS (14) | No check, but documented as needing a trusted display |

Permission levels ([core manifest](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/core/res/AndroidManifest.xml)):
CAPTURE_VIDEO_OUTPUT and INJECT_EVENTS are signature; CAPTURE_SECURE_VIDEO_OUTPUT,
ADD_TRUSTED_DISPLAY and ADD_ALWAYS_UNLOCKED_DISPLAY are signature|role; CREATE_VIRTUAL_DEVICE and
ADD_MIRROR_DISPLAY are internal|role.

The shell uid (what Shizuku runs as) holds INTERNAL_SYSTEM_WINDOW, INJECT_EVENTS,
CAPTURE_VIDEO_OUTPUT, READ_FRAME_BUFFER, MANAGE_ACTIVITY_TASKS, START_ACTIVITIES_FROM_BACKGROUND,
ACTIVITY_EMBEDDING, ADD_TRUSTED_DISPLAY and ADD_ALWAYS_UNLOCKED_DISPLAY, but **not**
CAPTURE_SECURE_VIDEO_OUTPUT
([Shell manifest](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/packages/Shell/AndroidManifest.xml), LOS16).

### 2.2 Who may launch an activity on a virtual display

`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`
([ATS](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java), LOS16), in order:

1. A caller with INTERNAL_SYSTEM_WINDOW may launch anywhere (shell has it).
2. On an **untrusted** display the target activity must declare `android:allowEmbedded="true"`,
   and the caller needs ACTIVITY_EMBEDDING or an activity already on that display.
3. Public display: allowed if the user is visible there.
4. Private display: allowed for the display owner or a caller already on it.

Consequences:

| Route | What it can do |
|---|---|
| **Normal app** | Create PUBLIC + OWN_CONTENT_ONLY or private displays and texture them, but only launch `allowEmbedded` activities there (almost no app). Its own Compose UI, WebViews and video players render fine in its own panels. No useful way to host Chrome or YouTube. |
| **MediaProjection** | One mirror display per consented session (Android 14+), not a host for apps ([docs](https://developer.android.com/media/grow/media-projection), [blog](https://android-developers.googleblog.com/2024/03/enhanced-screen-sharing-capabilities-in-android-14.html)). |
| **Shizuku (shell)** | Trusted, own group, always unlocked, own focus displays; launch any app there (`am start --display N`, or `IActivityTaskManager` as shell); inject input by display id; set IME policy and system decorations; `IWindowManager.mirrorDisplay` (needs READ_FRAME_BUFFER). |
| **System only** | SECURE displays (protected content) and `VirtualDeviceManager` (CREATE_VIRTUAL_DEVICE via the companion app streaming role, [AOSP](https://source.android.com/docs/core/permissions/app-streaming)). |

[ShadowScreen](https://github.com/li-junlei/ShadowScreen) (GPL-3.0, tested on XREAL One, Android
15+) confirms this from practice: on an untrusted display later activities "jump back to the main
screen", so it creates a trusted display through Shizuku. Google's multi display guidance says
the same (snippet, [recommended practices](https://source.android.com/docs/core/display/multi_display/recommended-practices)).

### 2.3 Prior art

- **scrcpy** `--new-display` (Apache-2.0, v3.0+): flags PUBLIC | PRESENTATION | OWN_CONTENT_ONLY |
  SUPPORTS_TOUCH | ROTATES_WITH_CONTENT, optional DESTROY_CONTENT_ON_REMOVAL and
  SHOULD_SHOW_SYSTEM_DECORATIONS; Android 13+ adds TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED |
  TOUCH_FEEDBACK_DISABLED; 14+ adds OWN_FOCUS | DEVICE_DISPLAY_GROUP; then sets the IME policy
  ([NewDisplayCapture.java](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java)).
  Input through `InputManager.injectInputEvent` with `InputEvent.setDisplayId` by reflection
  ([InputManager.java](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/wrappers/InputManager.java)).
  The closest reference and permissively licensed.
- [li-junlei/ShadowScreen](https://github.com/li-junlei/ShadowScreen) (GPL-3.0): Shizuku trusted
  virtual display shown on XREAL glasses through a `Presentation`, input forwarded by display id.
- [jqssun/android-display-extend](https://github.com/jqssun/android-display-extend) and
  [android-display-mirror](https://github.com/jqssun/android-display-mirror) (GPL-3.0): Shizuku
  calls into `IWindowManager`, `IDisplayManager`, `IInputManager`;
  `ActivityManager.isActivityStartAllowedOnDisplay` to check launches.
- [kangrio/SimpleVirtualDisplay](https://github.com/kangrio/SimpleVirtualDisplay) (Apache-2.0),
  [Aloim/DroidOS](https://github.com/aloim/droidos) (no licence),
  [prespic/android-desktop-touchpad](https://github.com/prespic/android-desktop-touchpad) (GPL-3.0).
- Glasses makers' own closed apps do the full job (for example VITURE SpaceWalker,
  [VITURE](https://www.viture.com/store/android)). **No open source phone app was found that
  draws other apps as 3D panels**; Pocket XR combines known pieces in a new way.
- One UI: DeX reportedly runs on a scrcpy virtual display on One UI 8
  ([Android Authority](https://www.androidauthority.com/samsung-dex-on-pc-one-ui-8-3582695));
  nothing found says One UI blocks launches on shell-created displays. **Verify** on One UI 9.

### 2.4 Getting frames into the scene

- **OpenGL ES:** `SurfaceTexture(texName)` with `setDefaultBufferSize(w, h)`, wrap in a `Surface`,
  pass to `createVirtualDisplay`. On `onFrameAvailable`, `updateTexImage()` on the GL thread and
  sample as `GL_TEXTURE_EXTERNAL_OES` with `getTransformMatrix`
  ([AOSP SurfaceTexture](https://source.android.com/docs/core/graphics/arch-st)). **Public API**
- **Vulkan:** `ImageReader` with `HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE`, import each buffer with
  `VK_ANDROID_external_memory_android_hardware_buffer`; may need a Y'CbCr sampler conversion for
  an implementation defined external format
  ([Khronos](https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VK_ANDROID_external_memory_android_hardware_buffer.html)).
  More work for little gain at 4 to 6 panels; start with GLES.
- **Panel refresh:** `VirtualDisplayConfig.Builder.setRequestedRefreshRate` (API 34) rounds to a
  divisor of the physical rate, e.g. 60 or 30 on 120 Hz
  ([reference](https://developer.android.com/reference/android/hardware/display/VirtualDisplayConfig.Builder)).
- **Text quality:** panels are minified when far away, which shimmers. External OES textures have
  no mipmaps, so copy each new frame into a normal RGBA texture and `glGenerateMipmap`, or
  render with 4x supersampled panel quads. Do it only when a frame arrives.
- **Protected content:** FLAG_SECURE windows are blanked on non-secure displays and the shell
  lacks CAPTURE_SECURE_VIDEO_OUTPUT, so Netflix, banking apps and similar show black panels even
  with Shizuku. Those apps stay in flat DeX mode.
- **Flat shortcut (unverified):** `IWindowManager.mirrorDisplay` hands back a SurfaceControl of
  another display's layers that the system composites for free, but SurfaceControl only does 2D
  affine transforms: usable for a flat "cinema" panel, not for perspective.

### 2.5 Showing the scene on the glasses

- With DeX on, display 7 hosts tasks, so a full screen Pocket XR activity (or the existing
  `GlassesHomeActivity` switched to a GL mode) can own the whole glasses picture. **Verify** that
  DeX lets an activity cover the taskbar (immersive mode). A `Presentation` is the fallback
  (ShadowScreen uses one) but dies with its owning activity.
- Render with a `SurfaceView` and EGL on a dedicated thread at the display's 120 Hz, paced by
  `Choreographer` frame callbacks, or the
  [Android Frame Pacing library](https://developer.android.com/games/sdk/frame-pacing). Sample the
  head pose as late as possible each frame. 120 Hz halves judder when turning the head and is
  worth the cost; drop to 60 Hz when hot.
- Optical see-through: black is transparent. Clear to black, no skybox; panels get a thin
  outline and a subtle shadow only.
- Projection: Aura's FOV is reported as 70 degrees
  ([xreal.com/aura](https://www.xreal.com/us/aura)); diagonal vs horizontal is unclear, so make
  FOV a calibration setting (match a virtual ruler to a real object). At about 1920 px over
  roughly 60 degrees horizontal that is about 30 pixels per degree, so a 1280x720 panel spanning
  about 40 degrees is close to 1:1.
- [GLFrontBufferedRenderer](https://developer.android.com/reference/androidx/graphics/lowlatency/GLFrontBufferedRenderer)
  exists for low latency but tears on a moving scene; not recommended here.

### 2.6 Routing input back

- **Shizuku injection (primary):** `InputManager.injectInputEvent` with `MotionEvent.setDisplayId`
  (hidden, by reflection), as shell, which holds INJECT_EVENTS
  ([InputManagerService](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/services/core/java/com/android/server/input/InputManagerService.java)).
  Map pointer ray, panel hit, panel UV, virtual display pixel, then send DOWN / MOVE / UP with
  `SOURCE_TOUCHSCREEN` (or `SOURCE_MOUSE` for hover and scroll wheel).
- **Accessibility gestures:** `AccessibilityManagerService.isValidDisplay` ignores private virtual
  displays not owned by SystemUI, and `dispatchGesture` fails where no injector exists
  ([AMS](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)).
  So `GestureDescription.setDisplayId` should work on public virtual displays and fail on private
  ones (inferred). Make the displays PUBLIC + OWN_CONTENT_ONLY to keep the existing
  `GestureInjector` as a backup. **Verify**.
- **Keyboard:** untrusted displays force the IME to the phone; on trusted ones
  `IWindowManager.setDisplayImePolicy(id, FALLBACK_DISPLAY)` as shell keeps the keyboard on the
  Flip, which is what we want (Flip as keyboard).
- **Focus:** each panel display needs its own focus (OWN_FOCUS, trusted) so typing goes to the
  panel the user last pointed at; `DisplayContent.hasOwnFocus()` is true with OWN_FOCUS or
  `config_perDisplayFocusEnabled`
  ([DisplayContent](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/services/core/java/com/android/server/wm/DisplayContent.java)).
- Android 17 stops large screen apps from refusing resize and orientation changes, which helps
  apps behave in panels of any size
  ([blog](https://android-developers.googleblog.com/2026/02/prepare-your-app-for-resizability-and.html)).

### 2.7 Performance, 4 to 6 panels

No published benchmarks exist for several app-hosting virtual displays. Reasoning from the
pipeline ([HWC](https://source.android.com/docs/core/graphics/implement-hwc),
[graphics architecture](https://source.android.com/docs/core/graphics/architecture)):

- Each app renders its own frames into its display, then SurfaceFlinger composites that display
  into its output buffer, usually with the GPU (vendor HWC support for virtual displays is
  optional), driven by the physical display's VSYNC. Then Pocket XR samples it once more.
- Bandwidth: a 1280x720 RGBA panel is 3.7 MB; six at 60 fps with SurfaceFlinger write plus our
  read is under 3 GB/s, small next to an LPDDR5X phone's tens of GB/s. Fill rate for one
  1920x1080 scene at 120 Hz is trivial. The real costs are six apps rendering at once, the extra
  GPU composition passes, memory (six foreground processes) and **heat** in a Flip's small body.
- Budget rules:
  - Panels default to 1280x720 at 60 Hz; idle panels drop to 30 Hz; a "focused" panel may go
    1920x1080.
  - Only `updateTexImage` and re-mip panels that got a new frame and are in view.
  - Panels far outside the view: lower their refresh rate to 30 or below (detaching the surface
    likely blanks the display and pauses the app; **Verify** side effects).
  - Scene at 120 Hz while cool, 60 Hz when `PowerManager.getThermalHeadroom` passes a threshold.
  - Measure with `dumpsys SurfaceFlinger`, `dumpsys gfxinfo`, `dumpsys thermalservice` and
    Perfetto.
- Expectation (estimate): 4 panels with one video at 60 fps and a 120 Hz scene is realistic;
  6 is plausible with idle panels throttled; sustained 6 active panels will throttle in tens of
  minutes. **Verify**.

## 3. Hand tracking quality

### 3.1 Limits of MediaPipe with one camera

- Two models: a palm detector on the whole frame (192x192 input), then a landmark model on the
  hand crop (224x224) giving 21 landmarks
  ([hands.md](https://github.com/google/mediapipe/blob/master/docs/solutions/hands.md),
  [palm input](https://huggingface.co/unity/sentis-blaze-palm),
  [landmark input](https://models.luxonis.com/luxonis/mediapipe-hand-landmarker/aim_RzL7sdeEnsZ9Kbi1XDbXqb)).
  In VIDEO / LIVE_STREAM mode the detector reruns only when presence drops below
  `min_tracking_confidence`; otherwise the crop comes from last frame's landmarks.
- Latency of the shipped full model on Pixel 6: 17.1 ms CPU, 12.3 ms GPU
  ([Hand Landmarker](https://ai.google.dev/mediapipe/solutions/vision/hand_landmarker)). Paper
  variants on Pixel 3: lite 6.6 ms, full 16.1 ms, heavy 36.9 ms
  ([arXiv 2006.10214](https://arxiv.org/pdf/2006.10214)). On Snapdragon NPUs the MediaPipe hand
  models run in about a millisecond
  ([Qualcomm AI Hub](https://huggingface.co/qualcomm/MediaPipe-Hand-Detection)), so inference is
  not the bottleneck; camera, transfer and decode are.
- Failure modes: motion blur is the worst (at least half of hands missed under diagonal blur,
  [arXiv 2303.04566](https://arxiv.org/pdf/2303.04566)); occlusion; dim light; z is a learned
  relative guess, and world landmarks are approximate
  ([arXiv 2308.01088](https://arxiv.org/pdf/2308.01088)). Small, far hands are hard for the
  192 px detector.
- No public Tasks API takes a GPU texture or HardwareBuffer; `ByteBufferImageBuilder` /
  `BitmapImageBuilder` / `MediaImageBuilder` are the inputs
  ([docs](https://developers.google.com/edge/mediapipe/solutions/vision/image_classifier/android)).
  The LiteRT Qualcomm NPU accelerator ([blog](https://developers.googleblog.com/en/unlocking-peak-performance-on-qualcomm-npu-with-litert/))
  is not usable from MediaPipe Tasks, and the Flip 7 is Exynos anyway. (0.5.43 later ran
  MediaPipe's two hand models on the Exynos 2500's NPU through LiteRT directly, about 3 ms each;
  see `NpuHandEngine` and `docs/HAND-TRACKING.md`.)

### 3.2 What Android XR, XREAL and Meta do differently

- Quest's MEgATrack: four monochrome fisheye cameras, 60 Hz on PC and 30 Hz on device, a
  keypoint network that uses tracking history for low jitter
  ([Meta research](https://ai.meta.com/research/publications/megatrack-monochrome-egocentric-articulated-hand-tracking-for-virtual-reality/)).
  Later versions improved occlusion and fast motion
  ([2.0](https://roadtovr.com/oculus-quest-2-hand-tracking-2-0-upgrade/),
  [2.1](https://developers.meta.com/horizon/blog/hand-tracking-improvements-v2-1),
  [2.2: up to 40% lower latency, 75% in fast motion](https://developers.meta.com/horizon/blog/hand-tracking-22-response-time-meta-quest-developers/));
  60 Hz mode cut end-to-end latency about 10%
  ([UploadVR](https://uploadvr.com/quest-2-high-freqency-hand-tracking/)).
- Measured hand latency: Quest 3 about 70 ms, Vision Pro (visionOS 1.1) about 128 ms
  ([Road to VR](https://roadtovr.com/apple-vision-pro-meta-quest-3-hand-tracking-latency-comparison/));
  visionOS 2 went to 90 Hz hand updates with prediction
  ([Road to VR](https://roadtovr.com/visionos-2-hand-tracking-latency-prediction/)).
- Galaxy XR: six world facing cameras for head and hands
  ([Wikipedia](https://en.wikipedia.org/wiki/Samsung_Galaxy_XR)). Aura: one RGB camera in the
  bridge and two "3D environment sensors" for 6DoF and hands
  ([arcompare](https://www.arcompare.com/ar-glasses/xreal-project-aura/),
  [Geeky Gadgets](https://www.geeky-gadgets.com/xreal-aura-review/)). The UVC camera Pocket XR
  reads is very likely the RGB one, not the tracking pair (inference).
- The decisive differences: stereo gives real depth; wide FOV mono global shutter sensors at
  high rate cut blur; IR illumination works in the dark; the hand is fused with 6DoF head pose
  in world space; models are trained on egocentric data with temporal history; 26 joint OpenXR
  skeletons with pinch strength and aim poses
  ([XR_EXT_hand_interaction](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XR_EXT_hand_interaction.html)).

### 3.3 Concrete improvements for Pocket XR

State at the time (0.3.1): UVC frames near 640 wide at up to 60 fps, MediaPipe
LIVE_STREAM on one hand, index knuckle aim through an air box, pinch ratio with hysteresis
(0.25 / 0.40), One Euro filter, 70 ms press rewind and 120 ms freeze (see the Done lists
below for what changed since).

In order of value:

1. **60 fps and short exposure.** Pick the 60 fps format if the UVC descriptors offer one. Turn
   off auto exposure priority so dim light cannot drop the frame rate
   ([KS auto exposure priority](https://learn.microsoft.com/en-us/windows-hardware/drivers/stream/ksproperty-cameracontrol-auto-exposure-priority));
   if the camera exposes `CT_EXPOSURE_TIME_ABSOLUTE`, cap exposure around 4 to 8 ms and raise
   gain. Both are standard UVC camera terminal controls (`SET_CUR` on the VideoControl
   interface), so they stay inside the "UVC standard only" rule. Blur is MediaPipe's worst case.
2. **Head-pose compensation (new with spatial).** The camera is on the head. Turn each landmark
   into a ray with the camera intrinsics (FOV calibration), then rotate it by the head pose at
   the frame's capture time (UVC PTS mapped to the IMU clock). Without this the cursor drifts
   whenever the user turns the head while the hand is still.
3. **Body-anchored ray.** Aim along a ray from an estimated shoulder (a fixed offset below and to
   the side of the head, rotated by yaw only) through the index knuckle, as Meta and Ultraleap do
   ([Meta raycasting](https://developers.meta.com/horizon/design/raycasting_bp/),
   [Ultraleap far field ray](https://docs.ultraleap.com/ultralab/far-field-ray-blog.html)).
   Steadier than raw image position and works anywhere around the user.
4. **Pinch.** Compute from `worldLandmarks` (metres) normalised by a stable bone (wrist to index
   knuckle), keep hysteresis (Ultraleap: in at 2.5 cm, out at 3.0 cm,
   [docs](https://docs.ultraleap.com/xr-and-tabletop/xr/unity/plugin/features/pinch-detector.html)),
   expose a 0 to 1 pinch strength for cursor feedback, keep the existing press rewind and freeze
   (Meta does the same, [hands UI](https://developers.meta.com/horizon/design/hands-ui-best-practices/)).
5. **One Euro tuning** ([Casiez](https://cristal.univ-lille.fr/~casiez/1euro)): beta 0, min cutoff
   1 Hz; lower min cutoff until still-hand jitter is gone; then raise beta from 0.001 in 10x steps
   until fast moves stop lagging; derivative cutoff 1 Hz. Filter the ray angles, not pixels.
6. **Prediction.** One frame of constant-velocity extrapolation on the filtered ray (Apple does
   display-time prediction). Clamp it so overshoot never exceeds a few pixels at stop.
7. **GPU vs CPU.** Benchmark both on the Flip; on Pixel 6 the GPU path was slower for the gesture
   recognizer ([docs](https://ai.google.dev/mediapipe/solutions/vision/gesture_recognizer)).
   Feed RGBA `ByteBuffer`s straight from the decoder, skipping `Bitmap` when possible.
8. **ROI and resolution.** LIVE_STREAM already tracks the ROI between detections; a lower
   `min_tracking_confidence` (around 0.4) means fewer re-detections. 640x480 is enough unless
   hands are small in frame; above that, crop around the last hand box at full resolution
   instead of scaling the whole frame (needs the raw models, so only if measured necessary).
9. **Head gaze plus pinch.** With head tracking, the centre of view is a free, stable pointer.
   Offer "look at it, pinch anywhere to click" as a mode: the hand only has to be seen pinching,
   which is the most robust thing MediaPipe does
   ([gaze plus pinch](https://research.lancaster-university.uk/en/publications/gaze-pinch-interaction-in-virtual-reality/)).
10. **Design for it.** Targets at least 2.5 to 3 degrees (Meta's guidance above), magnetic
    snapping to panel edges and title bars, generous grab zones in 3D.

**Done in 0.3.2-spatial** (after Mike's first Aura test: sluggish, pinch drifts, head turns move
the cursor):

- One Euro at 1.2 Hz, beta 0.05, derivative cut off 2.5 Hz (was 1.0 / 0.03 / 1.2): the speed
  estimate that opens the filter rises twice as fast, so moves no longer start late.
- Prediction by the measured capture to result latency plus 10 ms (about 70 ms on the Aura),
  ramped in by speed, at most 100 ms and 64 px ahead.
- Detection: each search view holds its 6 frames, a hand needs two hits in a row and is lost
  only after three misses (0.3.1 flipped full and centre on single false palms); once tracked
  the view stops cycling and becomes a crop around the hand at full camera resolution that moves
  only near its edge; bitmaps to MediaPipe are at most 640 px; the next frame is decoded and cut
  while MediaPipe works; after 1.5 s without a hand, frames go at most every 50 ms.
- Pinch on `worldLandmarks` (thumb to index over wrist to index knuckle, 0.25 in / 0.42 out),
  aim from a weighted palm point, press rewound to where the fingers started closing (50 to
  150 ms), cursor frozen while pinched until the hand moves 36 px, release rewound the same way.
  Each pinch down and up is logged at INFO under FlipSpacesHands.
- Head-pose compensation: hand signals carry their frame time; the Space places the ray in the
  world with the head pose of that moment (HeadPoseHistory, 20 ms camera delay), so a still hand
  keeps the cursor on the panel while the head turns. Without head tracking, as before. The air
  box still maps the camera to the display, so if its gain is far from the camera's true angle
  a little head motion remains; a camera FOV calibration would remove it.
- "Look and pinch" is a pointer mode on the Spatial windows screen.

**Done in 0.3.4-spatial** (Open Space showed the Space as a small window on the DeX monitor):

- AppLauncher.openSpace starts SpaceActivity the same way as the Spaces home: fullscreen
  windowing via reflection, no launch bounds, and a retry with only the display id if Android
  refuses the windowing mode (DesktopFullscreen.fullscreenAttempts).
- The manifest gives SpaceActivity the DeX launchwidth and launchheight 0 meta-data and a 100%
  `<layout>`.
- On the DeX or glasses display SpaceActivity maximizes itself (requestFullscreenMode on
  Android 14+) and hides the system and DeX bars on resume, focus and every resize.
- The renderer rebuilds its GL viewport, projection and pointer mapping from SpaceViewport after
  each surface or display size change; hand positions (display pixels) are mapped onto the
  surface, so nothing stays at the size the window opened with. Phone behaviour is unchanged.

**Done in 0.3.5-spatial** (Mike on 0.3.3: no more lag, but pinch-selecting is hard because the
cursor moves when he pinches; from a logged test session on the glasses):

- What the log showed: tracking was solid (30 fps, about 37 ms latency, confidence 0.96 to 0.99,
  hand view). Pinch 1 went down at 1140,802 and up at 1160,804 after 127 ms; pinch 2 stayed put
  over 195 ms. Press fired at a ratio of 0.23 to 0.24 and release at 0.58 to 0.69. Pinches are
  quick, so most of the drift happens while the fingers close, before the press, and the
  36 px freeze still let the hand's wobble turn pinch 1 into a small drag.
- Aim from the wrist and the middle, ring and pinky knuckles (`HandFrame.steadyPoint`,
  `AimPoint.STEADY`). The 0.3.2 palm point weighed the index knuckle, which the index finger
  pulls as it closes; the thumb and index finger now play no part in the aim. PALM and
  INDEX_KNUCKLE remain as options.
- Closing onset (`PinchLockSettings`): once the thumb to index ratio is below 0.45 and has
  fallen by 0.08 within 100 ms, the aim locks where the cursor was shown before the fingers
  started closing (cursor history, at most 250 ms back) and the cursor stops. The press goes
  to that spot. A slow pinch that never trips the onset locks the same way at the press. A lock
  that does not become a press within 400 ms, or whose ratio rises back above 0.52, is dropped.
- While pinched the cursor does not move at all until a deliberate drag: 48 px from where the
  hand was at the press after at least 220 ms, or 140 px at once, and never while the fingers are
  opening. The release goes to the locked spot, so release jitter cannot move the click.
- While the gap is closing (fallen 0.04 within 100 ms, below 0.8) the One Euro cut off drops to
  0.3 of normal and prediction is off; normal pointing keeps the 0.3.2 filter and prediction.
- After a lock ends (release, aborted closing, drag unlock) the cursor glides from the locked
  spot to the hand (90 ms time constant) instead of jumping.
- Logging at INFO under FlipSpacesHands: `Pinch closing at` (position, ratio and where it fell
  from), `Pinch aim locked at` (locked spot, rewind, hand position and drift), `Pinch down at`
  and `Pinch up at` (locked spot, raw hand position, drift, ratio, hold time),
  `Pinch drag unlocked` and `Pinch closing aborted`. The drift numbers show how far the hand
  went while the cursor held still.
- `PinchLockTest` replays the two logged pinches with synthetic drift during closing (about 40 px
  by the press, 10 to 14 px of wobble while held) and checks that the press and
  release land within 4 px of where the cursor was before closing and that the shared pointer
  sees a tap there, not a drag.

**Done in 0.3.6-spatial** (Mike on 0.3.5: the pinch lock holds the cursor still, but
pinch-selecting an app does not open it; from a second logged test session):

- What the log showed: 16 closing onsets and 16 locks but only 2 presses, no touch dispatched
  and no app started, with the pointer service enabled and bound. On the glasses home app list
  every pinch ended before a press: pinches 4 to 6 locked steadily near 1030,780, but the
  ratio only fell to 0.32 to 0.38 and each hit the 400 ms lock timeout (the two real presses came
  at 0.15 and 0.19), and the others aborted on the 0.52 rule within 134 to 366 ms. In the Space
  the two presses (pinch 10 at 1039,954, pinch 14 at 1643,553) rewound 201 and 134 ms to spots
  291 and 660 px from the hand, across fast moves, and hit no panel; a miss logged nothing.
  Locks 1, 7, 8 and 16 never ended, three of them as the hand was lost. The hiddenapi lines
  show Android 16 / One UI 8 denying `Display.canHostTasks` (74 times) and
  `ActivityOptions.setLaunchWindowingMode` to the app.
- Press matched to a natural pinch: `PinchCalibration` learns each hand's open ratio (where its
  closings start) and its tightest closes, and puts the press threshold half way between them
  (0.22 to 0.42, never within 0.08 of the open hand) and the release threshold further up (at
  least 0.06 above, at most 0.55). Before it has learned anything the press is at 0.42. The
  detector also releases once the ratio has risen 0.1 above the pinch's tightest point, for
  fingers that only reopen to 0.45 to 0.49.
- A lock always ends in a click or a clear abort: it presses at the threshold (one frame is
  enough while locked), or when the ratio stops closing (no new low for two frames after
  falling 0.08), or at 400 ms with the fingers still near their low. It aborts only when the
  fingers reopen (above 0.52, or 0.1 above the low) or stay half open past 400 ms, and it ends
  when the hand is lost for 100 ms (a press then completes as a click at the lock after 300 ms;
  a drag is cancelled). A hand that comes back after a gap without empty frames ends the old lock
  first.
- Rewind capped: the lock still goes to the cursor from before the closing, but never more than
  50 px from the hand at the onset and never back across a step faster than 1200 px/s; onsets
  while the hand moves faster than that are ignored (logged once) and the lock happens at the
  press instead.
- Space presses: every press logs what it hit (panel body, title bar, corner handle or nothing)
  and the nearest panel with how far outside it the ray passed; panels catch presses up to
  1.5 degrees outside their body and title bar.
- Glasses home: every tap logs its position, display and whether the Spaces home is on it, then
  the `dispatchGesture` result (completed, cancelled or refused). The tap stroke is 60 ms on a
  one point path (moveTo plus a zero length lineTo).
- DeX full screen: the windowing mode reflection is tried once per process and then skipped,
  and full screen starts fall back to launch bounds covering the whole display before the
  display id alone (manifest layout plus `requestFullscreenMode`). The launcher logs which path
  started the activity and the activity logs whether `requestFullscreenMode` took effect.
  `Display.canHostTasks` is likewise read at most once.
- Two hands: MediaPipe now reports up to two hands. The hand that continues the cursor stays the
  primary; when both pinch, pulling them apart or together scales the window or panel under the
  cursor (`HandsRecognizer`, `PointerSignal.Scale`), and the primary's press is cancelled so a
  stretch never clicks. While detection is cropped around one hand, a second hand outside the crop
  may be missed.
- Emulator testing: a debug only hand replay (`docs/EMULATOR_TESTING.md`) feeds scripted hands
  into the same `HandFeed` as the camera, with built-in scenarios for taps, the logged pinches,
  title bar drags, corner resizes and the two hand stretch, plus a debug command that opens the
  Spaces home or the Space on any display.
- `PinchClickTest` replays pinches 4 to 6 (each must click within 12 px of where it aimed, also
  with calibrated and with strict thresholds) and pinches 10 and 14 (the lock must stay within
  the 50 px cap) from hand frames through the recognizer, the lock and the pointer to the tap or
  touch, plus drags, resizes, the stretch and Space presses on, just outside and well away from a
  panel. `HandReplayTest` covers the replay format.

**Done in 0.3.7-spatial** (the emulator replay of 0.3.6: pinches 4, 5 and 6 are held 429 to
462 ms, past the pointer's 260 ms tap limit, so they became long touches instead of taps):

- A pinch press has its own tap limit (`PinchHold`): 650 ms until the hand has tapped, then
  200 ms past the longest of that hand's last 8 taps, never below 550 or above 900 ms, so a
  deliberate hold of a second still long-presses. Trackpad presses keep 260 ms. A press that
  moves past the tap slop is a drag however short it is.
- Every press logs what it became (tap, long press or drag) with its hold time and limit.
- `PinchHoldTest` replays the logged pinches in real time through the recognizer and the
  pointer, ticking it as the service does: pinches 4 to 6 must tap, 10 and 14 must end as drags
  (short pinches while the hand sweeps 146 and over 300 px; Mike chose to keep a short pinch
  during a fast sweep a drag, not a tap), and holds of 1 and 1.5 s must long-press, also after
  the limit is learnt.

### 3.4 Realistic expectations

Estimated hand to cursor latency (sum of parts, not measured):

| Stage | 30 fps | 60 fps |
|---|---|---|
| Exposure plus half a frame | 15 to 33 ms | 8 to 17 ms |
| USB transfer and MJPEG decode | 5 to 15 ms | 5 to 15 ms |
| Inference | 10 to 20 ms | 10 to 20 ms |
| Filter lag | 10 to 30 ms | 10 to 30 ms |
| Inject, composite, display | 10 to 20 ms | 10 to 20 ms |
| **Total** | **60 to 110 ms** | **45 to 80 ms** |

Works well: pointing at panels, grabbing and moving panels in space, resizing by corners, pinch
clicks on buttons of a few degrees, open palm for the dock, head gaze plus pinch.
Works poorly: fine text selection and small links, fast flicks, hands at the camera's edges, one
hand behind the other, dim rooms, anything depending on depth. The trackpad on the Flip stays the
precise input. If the Aura camera had turned out to be gated, the Flip's own camera route from
`docs/HAND-TRACKING.md` 5b would still apply, without head-pose compensation of the camera but
with the same head-gaze pointer. (It was not gated: the Aura camera is a standard UVC camera.)

## 4. Pieces the app already had (0.3)

- `DisplaySelector`, `HostingAssessor` (DeX vs mirror), `GlassesHomeActivity` on the glasses.
- `ShizukuBridge` (launch fallback today; becomes the panel host).
- `core/hands`: `OneEuroFilter`, `PinchDetector`, `HandGestureRecognizer`; `core/pointer`:
  `PointerController`; `core/uvc` full UVC pipeline.
- `PointerService` and `GestureInjector` for flat mode.

## 5. Recommended architecture for the Space

```
               glasses (display 7, DeX on)
   +-------------------------------------------------+
   | SpaceActivity (full screen, SurfaceView, GLES3) |
   |   SpaceRenderer 120 Hz: panels, cursor, dock     |
   +-------------------------------------------------+
        ^ head pose (predicted)      ^ panel textures (SurfaceTexture)
        |                            |
  HeadTracker                 PanelHost (Shizuku, shell uid)
  - OneFamilyImuSource          - create trusted VD per panel
    TCP 169.254.2.1:52998       - am start --display N
  - (onboard anchor: flat)      - inject MotionEvents by displayId
  - none -> flat DeX mode       - IME policy fallback (phone keyboard)
        ^                            ^
        |                            |
  core/spatial (JVM tested)   SpatialPointer (core)
  - fusion, prediction          - ray from head gaze / hand / trackpad
  - panel layout in world       - ray-panel hit, UV -> VD pixels
  - ray maths                   - reuses PointerController zones
```

- **Modes.** `SpatialMode` = Spatial (IMU + Shizuku), HeadLockedPanels (Shizuku, no IMU: panels
  in front, arranged like a carousel turned by trackpad), Flat (today's DeX windows, no
  Shizuku). Chosen at run time with the reason shown, like the hand providers.
- **core/spatial** (pure Kotlin): quaternion maths, Mahony filter, gyro bias, predictor,
  `PanelLayout` (panels on a cylinder of radius about 1.2 m around the user, yaw and pitch per panel,
  size in degrees), ray to panel intersection and UV, saved layouts. All JVM tested with
  recorded IMU traces.
- **HeadTracker** (app): finds the glasses network, reads the stream on its own thread, hands the
  latest sample to the fusion, exposes `poseAt(timeNanos)`. Recentre on a long press of the
  glasses button (the 3 byte HID keyboard, already a standard input device) or a Flip button.
- **PanelHost** (app, Shizuku user service so the virtual displays are owned by shell): one
  virtual display per panel with PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | TRUSTED |
  OWN_DISPLAY_GROUP | OWN_FOCUS | DESTROY_CONTENT_ON_REMOVAL, surface from Pocket XR'
  SurfaceTexture (passed over Binder), density about 240 dpi for 1280x720. Launch with
  `am start --display`. **Verify** that a Surface created in Pocket XR's process can be handed
  to the Shizuku user service (it is Parcelable, so it should).
- **SpaceRenderer**: late-latched head pose, panels as quads with mipmapped copies, cursor ray and
  reticle, grab handles drawn around the hovered panel, dock as a panel of Pocket XR's own UI.
- **SpatialPointer**: sources are head gaze (default), trackpad (moves a cursor on the hovered
  panel, or the ray in empty space), hands (section 3.3 ray). Press on a panel body injects
  touches into its display; press on the panel's own frame or handles moves or resizes the panel
  in world space (pure Pocket XR maths, no window injection needed, so it is smooth).
- **Fallback to DeX**: apps with FLAG_SECURE content or that misbehave on virtual displays get a
  "show in flat mode" action that moves them back to display 7, where DeX and the existing
  `PointerService` handle them.

### Milestones

| Step | Content | Done when |
|---|---|---|
| **S0 probes** (small, do first) | Setup screen tests: (a) list networks and interfaces, try `169.254.2.1:52998`, show report counts and live gyro numbers; (b) Shizuku: create one trusted virtual display, launch Chrome there, show its frames in a flat GL quad; (c) can a full screen activity cover DeX's taskbar; (d) `getDynamicSensorList`; (e) `ro.soc.model`; (f) do the glasses' buttons toggle any onboard anchoring. | The six answers are known. They decide everything below. |
| **S1 head tracking** | `core/spatial` fusion, bias, prediction, recentre; `HeadTracker`; a test scene of a grid and three dummy panels that stay put. | Turning the head 90 degrees and back returns panels within 1 degree; no visible swim at normal head speeds; drift under a few degrees per minute. |
| **S2 Space surface** | `SpaceActivity` + `SpaceRenderer` on the glasses, own UI panels (dock, clock, app list) as panels; head-gaze reticle; trackpad in Spatial mode. | Apps open from a floating dock by looking and tapping the Flip. |
| **S3 app panels** | `PanelHost` via Shizuku, up to 4 then 6 panels, panel move and resize in world, saved layouts, FLAG_SECURE detection and flat fallback. | Chrome, YouTube and a chat app float around the user and keep running; layout restores in one tap. |
| **S4 input into panels** | Shizuku touch and mouse injection by display id; IME on the phone; per-panel focus; scroll. | Typing into a floating Chrome with the Flip's keyboard and scrolling with two fingers work. |
| **S5 hands in space** | 60 fps short exposure, head-pose compensated rays, shoulder ray, world-landmark pinch strength, One Euro tuning, prediction, gaze plus pinch mode. | Grab and move a panel by hand with no stray clicks over 5 minutes; latency measured and shown. |
| **S6 performance and polish** | Per-panel refresh throttling, thermal headroom handling, perf overlay, mipmap quality, cover screen controls. | 4 panels with one video hold 120 Hz scene and 60 Hz panels for 20 minutes; 6 panels without throttling below 60. |

### Risks

| Risk | Effect | Fallback |
|---|---|---|
| Aura firmware does not serve the One-family IMU stream on 0x043A, or One UI does not bring up its NCM network | No head tracking | HeadLockedPanels mode; onboard Anchor if the glasses have it (flat but stable); ask XREAL for docs; the HID control channel is not a route to take without Mike's decision |
| Building on a protocol XREAL does not document | Head tracking blocked by policy | Same as above; decide in S0 |
| Gyro drift and yaw drift without magnetometer | Panels slowly rotate | Bias estimation when still, recentre gesture, optional magnetometer |
| Motion to photon latency too high (DisplayPort path plus 120 Hz) | Panels swim when turning | Prediction, late latching, 120 Hz; tune the prediction interval with a test pattern |
| Shizuku not running (it needs restarting after each reboot over wireless debugging) | No app panels | Flat DeX mode; clear setup card |
| One UI 9 blocks or misbehaves with shell-created trusted displays | No app panels | Untrusted public displays for `allowEmbedded` apps only; flat mode |
| DeX will not let an activity cover its taskbar | Taskbar visible in space | Presentation on display 7, or hide the taskbar in DeX settings |
| FLAG_SECURE / DRM apps | Black panels | Detect and offer flat mode for them |
| Apps behave badly on small or odd displays (density, rotation) | Broken layouts | Per-app panel size and density presets; Android 17 forced resizability helps |
| Thermal throttling with 4 to 6 live apps | Frame drops, then app kills | Throttle hidden panels, lower panel resolution, 60 Hz scene when hot |
| Hidden API changes (Shizuku calls by reflection) | Panel host breaks after an update | Use `am` and `cmd` shell commands where possible; feature checks; flat mode |
| Glasses camera gated or poor | No or weak hands | Head gaze plus trackpad; Flip camera route |
| Licence mixing | Legal | Reuse only MIT / Apache / BSL code with attribution; GPL projects as reading material only |

## 5a. 0.4.0-launcher: from a Space opened by hand to the glasses' launcher

A read-only check of a Flip 7 (SM-F766U, Android 17, One UI 9.0) on 0.3.7-spatial:

- Shizuku 13.6.0 runs with permission granted; `PanelHostService` connects as shell. So the
  Shizuku route of section 2 works on One UI 9.
- The glasses are display 8 ("XREAL 1 Ultra", 1920x1080, FLAG_TRUSTED,
  FLAG_EXTERNAL_DEX_HOSTING) inside One UI 9's desktop windowing DeX
  (`dex_on_external_display=1`). DeX owns the display; the Space is one full screen window on it.
- Yet no virtual display was ever created: plug-in always opened the flat home, the Space only
  opened by hand, and the phone list and glasses dock always opened DeX windows. The mode was
  never logged, and a panel that failed once stayed failed.
- `ActivityOptions.setLaunchWindowingMode` is not available to apps (NoSuchMethodException), so
  full screen still comes from launch bounds and `requestFullscreenMode` (`AppLauncher`).

What 0.4.0 changes, in the terms of section 5:

| Piece | 0.3.7-spatial | 0.4.0-launcher |
| --- | --- | --- |
| Glasses come up | Flat home | Space full screen unless flat; SECONDARY_HOME entry |
| App launches | DeX windows (Spatial windows screen only: panels) | Panels whenever the Space is possible |
| Space UI | Panels and an information panel | Plus a curved launcher, Apps button, recents strip |
| Panel failure | Never retried | Three tries with backoff, state on the panel, pinch to retry |
| Service restart | Dead display ids kept | Generations; panels recreated and relaunched |
| Display selection | Could take a panel display | Panels excluded by id and name |
| Logs | Panel creation only | `FlipSpaces/Mode`, `FlipSpaces/PanelHost`, `FlipSpaces/Panel` |

Headsets put the app launcher in the space itself (a home menu opened by a gesture, apps placed
where the user looks), and that is the model followed here. Still to **Verify** on the phone: whether One UI lets a third party app be
the secondary home of a DeX hosting display, and whether the full screen Space covers DeX's
taskbar on display 8.

## 5b. 0.4.1-launcher: input, rendering and protected apps

What 0.4.1 changes, in the terms of sections 2.6 and 2.7:

| Piece | 0.4.0-launcher | 0.4.1-launcher |
| --- | --- | --- |
| Input to panels | Single touches (tap, drag, held touch) | Plus mouse hover, mouse wheel scroll, two finger pinch zoom, long press, Back (button and three finger swipe), hardware keys |
| Focus | Whatever Android gave the display | `focusTopTask` as shell for the panel last pressed; the Space's display while the launcher is open |
| Panel texture | External OES, linear, no mipmaps | Copied into a mipmapped RGBA8 texture, trilinear plus anisotropic when shrunk |
| Panels out of view | Latched and drawn | Neither, until they come back |
| Heat | Not handled | Thermal status from moderate caps panel latching (30, 20, 10 fps; focused panel twice) and new displays ask for 30 Hz |
| Protected apps | Black panel, a note on the information panel | Detected (black while drawing, or FLAG_SECURE in `dumpsys window`); a banner offers a DeX window |
| Display callbacks | Ignored | Paused, resumed, stopped followed; stopped reopens the panel |
| Launcher palm | Any open palm | Palm toward the glasses camera only |

Section 2.6's focus point (OWN_FOCUS, per display focus) is what makes `focusTopTask` work: a
display with its own focus may become the top focused display, and the IME (on the phone, by
the fallback display policy) then serves its app. Section 2.7's budget rule "only latch and
re-mip panels that got a new frame and are in view" is now what the renderer does; detaching a
panel's Surface to pause its app was not used, because a display without a Surface turns off and
its activities go to sleep (a video would stop when the user looks away). Still to **Verify** on the
phone: `focusTopTask` on One UI 9, mouse hover and wheel from an injected device in Chrome and
YouTube, which display callbacks One UI sends on screen off, and MediaPipe's handedness on the
glasses camera for the palm check.

## 5c. 0.4.2-launcher: fixes from the 0.4.1 emulator test

The emulator run of 0.4.1 passed all six checks with no crash, and found nine issues. The
fixes:

| Piece | 0.4.1-launcher | 0.4.2-launcher |
| --- | --- | --- |
| New panel from the launcher | Centred on the gaze, even on top of an open panel | Stepped sideways along the arc to the nearest spot clear of every open panel |
| Draw and hit order | Drawn far to near, hit nearest; a tie hit the panel behind | One order for both: nearest, then most recently raised or focused |
| Mouse hover and wheel | At the pointer, even on the display's top rows (no window there) | Clamped inside the content area; every scroll logged with its result (`FlipSpaces/Input`) |
| Launcher | About 45 degrees tall, top row clipped; translucent over the panels; search kept | Fitted and centred in the view; opaque backdrop, no hover behind it; search cleared on close and on every app opened |
| App closed | Blank panel | App closed with Reopen and Close, from the display's task list; Back at the last activity asks first |
| Mode log | A line whenever the reason text changed | Mode changes at once; reason changes (numbers ignored) after holding 5 s |
| Trackpad focus | A touch made the phone the top focused display | Trackpad window not focusable while the Space is up; focus given back to the panel after each touch |
| Plug-in | Only with the phone's main screen in front | From any Pocket XR screen, the pointer service, or the shell through Shizuku |

Section 2.6's input rules gain one: a mouse event must land on a window. Android dispatches
hover and wheel events by the window under the pointer (`InputDispatcher` drops them with
"hovering pointer is not in any windows" otherwise); touches showed no such drop in the emulator
run, so only mouse events are clamped. Still to **Verify**:
`getAllRootTaskInfosOnDisplay` and `TaskInfo.isVisible` on One UI 9, whether an app that moves
itself to the back on a panel display is reported invisible, and whether `FLAG_NOT_FOCUSABLE`
alone keeps One UI's focus on the panel when the trackpad is touched.

## 5d. 0.4.3-launcher: fixes from the 0.4.2 retest

The 0.4.2 retest passed most checks and failed five. The fixes:

| Piece | 0.4.2-launcher | 0.4.3-launcher |
| --- | --- | --- |
| Draw and hit order | Drawn by each panel's distance, hit by the distance along the ray | One order: hits are tried in the draw order reversed, so the panel drawn in front wins |
| Launcher Close button | Drawn under the opaque backdrop while open | Drawn last, over the backdrop |
| Shizuku drop | Panel display removals ran the plug-in check; the flat home replaced the Space | Panel display events ignored; the Space kept with placeholders and restored when the service is back |
| Panel service bind | One try; stuck in Binding if Shizuku's starter exited | Retried after 1, 2, 4, 8 s, then every 30 s, each try logged |
| Background plug-in | Watcher only with a screen or service; silent returns; the shell started a trampoline | Watcher with the process, every branch logged, the shell starts the Space full screen itself |
| Unplug | The Space moved to the phone; panel displays kept until the service died | The Space closes and releases its panel displays; the trackpad takes focus again |

Section 2.6 gains a rule: Android's display listener reports our own virtual displays like any
other, so a process wide listener must recognise them by id even after they are gone (a removed
display has no name to read). Still to **Verify** on the phone: that the shell may start the
exported, permission guarded Space with `--windowingMode 1` on a DeX hosting display, and how
long Shizuku's user service takes to start on One UI 9 (a first retry after 1 s may come before
a slow but healthy start).

## 6. Sources

Head tracking: all links in sections 1.1 to 1.5, chiefly
[ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs),
[XRLinuxDriver](https://github.com/wheaney/XRLinuxDriver),
[Skarian/one-xr](https://github.com/Skarian/one-xr),
[0xcaff/xr-tools](https://github.com/0xcaff/xr-tools),
[One-Pro-IMU-Retriever-Demo](https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo),
[Monado Xreal Air driver](https://monado.pages.freedesktop.org/monado/group__drv__xreal__air.html),
[XREAL One buttons](https://tutorials.xreal.com/docs/glasses/one-series/first-use/buttons/),
[XREAL TCP/IP setting](https://tutorials.xreal.com/docs/glasses/one-series/osd/tcp-ip/),
[AOSP head tracker HID](https://source.android.com/docs/core/sensors/head-tracker-hid-protocol).

Virtual displays and rendering:
[DisplayManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/display/DisplayManagerService.java),
[ActivityTaskSupervisor](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java),
[Shell manifest](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.1/packages/Shell/AndroidManifest.xml),
[scrcpy NewDisplayCapture](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java),
[ShadowScreen](https://github.com/li-junlei/ShadowScreen),
[android-display-extend](https://github.com/jqssun/android-display-extend),
[SurfaceTexture](https://source.android.com/docs/core/graphics/arch-st),
[VirtualDisplayConfig.Builder](https://developer.android.com/reference/android/hardware/display/VirtualDisplayConfig.Builder),
[Frame pacing](https://developer.android.com/games/sdk/frame-pacing),
[HWC](https://source.android.com/docs/core/graphics/implement-hwc).

Hands: [MediaPipe Hand Landmarker](https://ai.google.dev/mediapipe/solutions/vision/hand_landmarker),
[MediaPipe Hands paper](https://arxiv.org/pdf/2006.10214),
[MEgATrack](https://ai.meta.com/research/publications/megatrack-monochrome-egocentric-articulated-hand-tracking-for-virtual-reality/),
[One Euro filter](https://cristal.univ-lille.fr/~casiez/1euro),
[Meta hands UI best practices](https://developers.meta.com/horizon/design/hands-ui-best-practices/),
[OpenXR hand interaction](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XR_EXT_hand_interaction.html),
[Qualcomm AI Hub MediaPipe Hand](https://huggingface.co/qualcomm/MediaPipe-Hand-Detection),
[Monado Mercury hand tracking](https://monado.freedesktop.org/handtracking).
