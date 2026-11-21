# Play listing images

The icon, the feature graphic and the add-on's two screenshots come from Pocket XR itself. The phone screenshots
(21 Nov 2026) are designed scenes of the Space in the glasses, not captures; see "The phone screenshots" below.

| File | Size | Source | SHA-256 |
| --- | --- | --- | --- |
| images/icon-512.png | 512 x 512 | The launcher icon's own vectors (app/src/main/res/drawable/ic_launcher_background.xml and ic_launcher_foreground.xml), cropped to the brand square's 90 unit view (docs/brand/pocket-xr-logo-square.svg), drawn at 4096 x 4096 and Lanczos-downsampled | cd02c73da2f966351de6c5fe34e195f722596711aa0475f894af5d83b280cb98 |
| images/feature-graphic-1024x500.png | 1024 x 500 | The icon's three panes and the app's Inter font (app/src/main/res/font) on the app's page colour (core Look.PAGE, #0C0D10) with an ACCENT_DEEP glow, composed at 4096 x 2000 and Lanczos-downsampled | 2e156a07b1ee4ba5bfcc93751ed50319e3e253081b62487597f669c0155b3efc |
| images/phone/01-apps-all-around-you.png | 1920 x 1080 | Designed: Android apps as windows in the Space | 26df4f32af9b21931f8bcb168f34ce84af50281af6060a0be6726df58c773a20 |
| images/phone/02-your-computer.png | 1920 x 1080 | Designed: Windows, Mac and Linux PC screens | b2e5035984ecf39545cfdb8cbfde35273730d88708ebbfbcf3037231d1f64d5e |
| images/phone/03-phone-trackpad.png | 1920 x 1080 | Designed: the phone as the trackpad, its screen redrawn from a capture | 8d321a1feb96f6190d9e6f2088ab38c32a58e756c59d433718e0c520485bbbb7 |
| images/phone/04-immersive.png | 1920 x 1080 | Designed: Immersive | 3ac77ef991330d9909b2a21524d694c0e13333db20fbcc417273c4f81a51a220 |
| images/phone/05-hands.png | 1920 x 1080 | Designed: moving a window with hands | 0d22bdfdf471d4af662cd8578c877f5a08d8717c3733ffe15fcbef8b4a56eb7b |
| images/phone/06-free-open-source.png | 1920 x 1080 | Designed: free and open source | 93f6a8aeb0fe63a28c1a16617328dcc8f05a7ccbb0ff496c02ec4ff4346453d5 |
| images/addon/04-hands.png | 1200 x 2400 | The Hands screen and the pointer card (the add-on's listing) | a314953a88de8c8d3819efdc3adafb36d835d2e8eaa348de17c64e62419c56ae |
| images/addon/05-disclosure-pointer.png | 1200 x 2400 | The add-on's accessibility disclosure and consent | 54a16cc2d96ccdb05f648d908f67f6d64d9de41081522048728975d87ed4ed84 |

## The phone screenshots

Pocket XR's Play listing used phone settings screens until 21 Nov 2026; Mike found they did not show Pocket XR at all.
The six in images/phone replace them. They are Remotion stills from the pocket-xr-demo project (src/play/, built on the
same glasses UI components as the 1.6 promo video), rendered at 1920 x 1080. They are designed, not captured:

- The app windows' contents (YouTube, Chrome, chat, Spotify) and the desktops are made up.
- The view is wider than the glasses' real field of view, so neighbouring windows show.
- The phone in 03 is a generic phone; its screen is redrawn from the earlier 02-trackpad capture.
- In 04 the faded side windows sit closer than the real Immersive puts them.
- In 05 the light ray to the pointer and the ghosts of the moving window are design cues; the app draws neither.

On Play they were marked "Don't label assets" in the AI asset declaration (Mike's choice, 21 Nov 2026). The earlier
phone screenshots (01 to 06 before 21 Nov) are in Git history; 04 and 05 stay in images/addon for the add-on's listing.

## How the earlier screenshots were taken (the add-on's two still in use)

The Galaxy Z Flip 7 was reachable over adb, but it was running another XR session (the ALVR client) and its Pocket XR
is signed with the release key, so this build could not be installed over it without Mike's approval. The screenshots
were therefore taken with `adb exec-out screencap` on the Android emulator (Medium_Phone_API_35, 1080 x 2400, with a
1920 x 1080 secondary display standing in for the glasses), running the R8-shrunk release build of Pocket XR 1.1.11
(142) and the add-on 1.1 (2) from release/play. The status bar is the emulator's.

Play allows at most a 2:1 aspect ratio, and the emulator's screen is 9:20. Each screenshot is padded at the sides to
1200 x 2400 with its own edge colour (the page background); the app's pixels are untouched and nothing is cropped.

The Space itself (the windows in the glasses) can only be captured on the glasses. Pocket XR's own "Record the glasses"
gives a clean 1080p video; a still from such a recording would be a video frame, so none is used here.
