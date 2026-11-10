# Changelog

Each release lists what changed for people using Pocket XR. Versions follow [semantic versioning](https://semver.org/).

## 1.2.12: VITURE Beast tracking by default, and a mouse on Spatial Mouse

Thanks to the tester in GitHub Discussion #24 for confirming 1.2.10 on the VITURE Beast and for the two things left to
fix.

- **VITURE head tracking is on by default on the Beast.** No more turning it on at first. Other VITURE models still
  start with it off. If you turn it off on the Beast, it stays off.
- **Beast tracking comes back after a replug.** Android drops Pocket XR's USB access to the glasses when they are
  unplugged, and the access prompt could come up out of sight while the Space opened, so tracking waited until the
  switch was flipped again. Now Android offers to open Pocket XR when the Beast is plugged in, and its OK gives the
  access back (tick Always and it won't ask again). If a prompt is still missed, it is asked again when the Space or the
  head tracking screen opens.
- **The switch is called VITURE head tracking on the Beast.** It keeps Experimental in its name on other VITURE models.
  With it off on the Beast, the status says it's turned off instead of saying VITURE isn't supported.
- **A mouse works on Spatial Mouse.** With the trackpad left on Spatial Mouse, the phone's aim took the pointer back
  on every move, so a Bluetooth mouse looked dead. Now the mouse wins: while it's moving, it moves the pointer the
  same as on the other trackpad pages, and clicks land where it points. About a second and a half after it stops, the
  phone aims again from where the pointer is, with no jump back. Your settings stay as they were.

## 1.2.11: Record works on the Flip7

- **Record works on the Galaxy Z Flip7.** Tapping Record in the Menu did nothing there: the recording stopped a tenth of
  a second after it started and nothing was saved. Pocket XR asked the phone's graphics for a video surface that didn't
  match the Space's own, and the Flip7 refuses that. It now picks one that matches. If a recording still can't start,
  the glasses say so instead of saying nothing.

## 1.2.10: VITURE tracking comes back

Thanks to the tester in GitHub Discussion #24 for a second round of VITURE Beast logs, the motion recording and the
steps that brought tracking back.

- **VITURE head tracking comes back.** Turning Experimental VITURE head tracking off and on, unplugging and plugging the
  glasses back in, and the Space restarting all bring tracking back without a force stop. The glasses kept an answer
  waiting after each stop and took no new start until it was read; Pocket XR now reads it first. It also closes and
  releases the glasses cleanly when tracking stops.
- **The USB prompt asks again.** It shows for each plug-in and each time you turn the switch on, if Pocket XR doesn't
  have access, and asks for the glasses, not the VITURE Microphone they also show as.
- **The axis switches apply at once.** Flip up and down and Swap turn and tilt change the view straight away, without
  restarting tracking.
- **VITURE Beast starts the right way up.** Its sensor axis layout starts at x right, y down, z front with both switches
  off. That is the same as the setup that worked for the tester (Swap turn and tilt on with x right, y front, z up). A
  layout you already picked is kept.
- **Launcher icons in one shape.** Every app icon is drawn in your phone's own icon shape. Icon pack and older icons fill
  that shape too, instead of sitting in a white circle, and stay as sharp as before.

## 1.2.9: The right testers group

- **The testers card's Join button opens the right group.** It went to a Google Group that didn't exist. It now opens
  the Pocket XR testers group, so you can join and then take the Play invite.

## 1.2.8: VITURE glasses, a first step

Thanks to the tester in GitHub Discussion #24 for VITURE Beast logs and a clear report.

- **VITURE glasses say so plainly.** Head tracking isn't supported yet on VITURE glasses, and the head tracking screen
  now says that instead of blaming a missing USB network. The Space works head locked.
- **Experimental VITURE head tracking.** Off by default, on the head tracking screen. It's untested on VITURE glasses, so
  your model may not answer. If the view moves the wrong way, try Flip up and down or Swap turn and tilt.
- **Record motion for 20 seconds.** On the head tracking screen, for glasses that aren't XREAL's. It saves what the
  glasses' motion sensor sends, and Send logs takes it along, so support for them can be added.
- **Send logs lists every glasses device.** Not only XREAL's: the USB IDs, interfaces, report descriptors and the display
  modes Android reports.
- **No more two copies in 32:9.** On glasses other than XREAL's, the wide 3840 x 1080 mode is one wide screen in 2D, and
  side by side only with 3D on.
- **The Depth button tells you what to do.** On glasses Pocket XR can't switch to 3D itself, it says "Turn on 3D in your
  glasses menu first" on the glasses.
- **Smoother launcher icons.** Icon edges are smooth with any icon pack, drawn at the glasses' own pixel size.

## 1.2.7: Text size for the window you clicked

- **Help Pocket XR get on Google Play.** The trackpad's Menu has a card asking you to join the Play testers. Tap Join
  for the testers group, then Open Play invite. Installing from Play means uninstalling this copy first, and that resets
  your Pocket XR settings. Not now hides the card for 7 days. I already joined hides it for good. The Play version
  doesn't show it.
- **Text size shows for the window you clicked.** The Menu's This window now keeps up with the window you focus, by
  trackpad, mouse, pinch or gaze, even while the Menu is open. The window keeps its focus when the glasses switch
  between 2D and 3D, and a window you just opened has it too. The same goes for Side by side 3D and the Game
  controller. Thanks to John Davis for the report.
- **Text size waits for the window.** After you tap smaller, larger or the reset, the three keys dim and say Resizing
  until the window in the glasses has redrawn at the new size, 3 seconds at most. Taps while they're dimmed are ignored,
  so quick taps no longer pile up and keep reloading the window long after you stop.

## 1.2.6: a lighter Menu

- **A tidier Menu.** The trackpad's Menu is about half as long. The things used every session are tiles at the top:
  Steady mode, Pocket mode, Game keys, Open Control Center, Record, Phone home and Pocket XR Settings. Unlock phone
  joins them while the phone is locked.
- **This window.** Text size and Side by side 3D for the window you are using sit together under This window, right
  under the tiles.
- **Sections.** The Space (tracking, Lock screens, Arrange and presets) is open. Trackpad and controls, Windows and
  glasses, and Help and more start closed: tap a heading to open it, and the Menu remembers. Exit Pocket XR stays last.
- **Only what you can use.** Rows that need the Space, such as Pocket mode, Record and Arrange, show while it's open,
  and greyed out rows are gone. Every setting keeps its value.
- **Ko-fi in Pocket XR blue.** The Buy me a beer pill uses the same blue as the window glow, softly.

## 1.2.5: a smoother hand cursor

- **Smoother hand cursor.** The dot follows your hand closely and glides on every refresh, with no sticking and hopping
  on slow moves. It steadies while you pinch, so a click lands where you aimed.
- **Cooler while you wear the glasses.** Head tracking uses about a third less of the phone's processor, with the same
  steady picture.

## 1.2.4: the game controller

- **Game controller.** The trackpad's Menu has a Game controller with a stick and buttons on the phone: L2, L1 and Select
  over a left stick, and Start, R1 and R2 over a right stick and A, B, X and Y. Tap the row to go from Off to Gamepad to
  Touch.
- **Gamepad.** Games see the phone as a real game controller, so they use their own controller support. It needs
  Shizuku. Click the game's window first if it doesn't answer.
- **Touch.** For games that only have on-screen controls. Aim the dot at the game's own button and hold the matching
  control on the phone for a second to set it, once for each game. Then the stick and buttons press the game's own
  controls, several at once. The Menu can set the spots again or clear them.
- **Side by side 3D switches to 3D for you.** Turning on Side by side 3D with the glasses in 2D now switches them to 3D,
  once, when you turn it on.
- **2D while moving.** A new option under Side by side 3D, off by default. Side by side videos show flat while your head
  or the cursor moves, and go back to 3D when you're still.

## 1.2.3: pocket mode, game keys and text size

- **Pocket mode.** The trackpad's Menu has Pocket mode for walking around with the phone in your pocket. The phone's
  screen goes black at its lowest brightness and blocks touches, while the glasses keep running. Hold the lock to leave.
- **Game keys.** Turn on Game keys in the Menu for two sets of keys next to the trackpad: Q W E, A S D, Shift, Ctrl and
  Tab on the left, and 1 to 4, R, F, Esc and Space on the right. They send real key presses, so a key you hold stays held
  in a game. Tap Done to put the window keys back.
- **Text size for each app.** Focus a window and use Text size in the Menu to make its text smaller or larger, from
  40 % to 130 %. Smaller fits more on the window, such as a browser's desktop layout. It's remembered for each app.
- **Side by side 3D.** For a video split into two halves, turn on Side by side 3D in the Menu. Each eye sees its half,
  so the video looks 3D with the glasses in 3D. It's remembered for each app.
- **Open Control Center from the Menu.** On phones that keep three finger swipes for themselves, such as OnePlus, the
  Menu now opens the Control Center. The tutorial says what to use instead of the swipes.
- **Resizing is easier to find.** Rest the dot on a window's corner handle and hold a finger still: the dot fills up,
  then you can drag. The tutorial now says how.
- **Scroll the windows with a mouse.** With the cursor off the windows, the mouse wheel and a two finger swipe up or down
  now slide the row of windows round, as a sideways swipe already did.
- **Air glasses: level horizon.** On the Air, Air 2, Air 2 Pro and Air 2 Ultra, gravity was never used, so the horizon
  could sit in the floor and drift, and Recentre couldn't level it. This is fixed. We don't have Air glasses to test
  on, so please tell us how it works for you.
- **Air glasses: display mode (untested).** With Air glasses plugged in, the Menu has an Air display mode switch, off by
  default. Turned on, it shows the glasses' mode and switches between 2D and 3D side by side. It has never been tried
  on Air glasses, so please open an issue if it doesn't work.
- **Fixes.** The launcher's Search apps opens the keyboard without a tap. The Menu shows Sideways trackpad direction only
  on the sideways trackpad, the one it changes.

## 1.2.2: the window glow

- **Turn down the window glow.** Spatial windows in Settings has a new Window glow slider for the blue glow round the
  window you are using. Slide it lower for a softer glow, or all the way down to turn it off. It changes the Space at
  once, and it starts at 100 %, so the glow looks as before unless you change it.
- **Pick the glow's colour.** Next to the slider, Glow colour has swatches for blue, white, purple, pink, green, amber
  and red, plus Custom, which opens a hue slider and a colour slider for any bright colour you like. The colour and
  the slider work together and change the Space at once. Blue stays the default, and Reset puts both back.

## 1.2.1: the add-on during setup

- **New users hear about the add-on.** The free Pocket XR add-on now has its own page in the first-run tutorial, and
  the Glasses setup check's All set screen shows it as the next step.
- **One tap to install it.** The Install add-on button downloads the newest add-on and opens Android's install prompt.
  If Android asks, Allow installs opens "Install unknown apps", and the install carries on when you come back. If the
  download fails, it says why and offers the add-on's release page.
- **Already have it?** If the add-on is installed, the button opens its notification access setting instead.

## 1.2.0: Steady mode

- **Steady mode.** A new button on the trackpad and in the launcher gives you the stillest picture Pocket XR has had.
  One tap puts your glasses in Ultrawide at their largest screen size, and the glasses hold one wide flat canvas
  themselves. Your windows move and resize to fill it side by side, so nothing is cut off. Tap again
  for 3D, and your windows go back exactly where you had them. There's no depth in Steady mode.
  Tested on the XREAL Aura. It should work on the One, One Pro and 1S too, but hasn't been tried on them yet, so please
  open an issue on GitHub if it doesn't, and we'll get it working.
- **Steadier on the Aura.** Pocket XR now turns Spatial Anchor off for you. It was fighting head tracking, and with it off
  the Space is far steadier, with your windows all around you.
- **Pocket XR leaves your glasses' mode alone.** It asks for 3D when you plug in, and asks again if the glasses drop it
  straight away. After that, 3D, 2D and Ultrawide are your choice.
- **Four apps pinned to start with.** The launcher's top row now starts with four apps, so it's clear what the row is for.
  You can change them or remove them.
- **The Glasses setup check knows the Aura.** It no longer asks you to change settings Pocket XR now sets, and it tells you
  to leave Steady mode before it starts measuring.
- **A crash can't leave the glasses stuck.** If the app dies while the glasses are holding the picture, the next start
  clears it.

## 1.1.17: a real mouse in the Space

- **A real mouse works in the Space.** Asked by tester John Davis. Before this, a Bluetooth or USB mouse did nothing
  in the Space: Android drew its own arrow, the Space ignored it, and on the phone a click on the trackpad counted as
  a finger's tap. Now a mouse connected to the phone or a Beam Pro moves Pocket XR's own pointer, the way the
  trackpad does. Left click clicks, right click is the app's long press (the trackpad's two finger tap), and the
  wheel scrolls the window under the pointer. Click and drag on a window's edge to move it, or on the blue handle to
  resize it; with Lock screens on, neither moves. The mouse's Back button is Back. While the Space is up I capture
  the mouse, so Android's arrow is hidden and can't wander off to another screen; when the Space closes, or you leave
  Pocket XR, the mouse goes back to Android. The trackpad's cursor speed sets the mouse's speed too. The
  trackpad and Spatial Mouse work as before.
- **With Cursor follows head off, the mouse's pointer stays where you put it.** On glasses with a camera, your hands
  on the keyboard were taken for hand input: a hand cursor took over from the mouse's pointer and partly turned with
  your head, so looking at another window seemed to move the pointer there, and a stray pinch could click that
  window and take your typing. Now, while a mouse is in use and Cursor follows head is off, hand cursors and pinches
  are set aside: only the mouse or the trackpad moves the pointer, and your keys stay with the window you clicked.
  Turn Cursor follows head on, or disconnect the mouse, and hands work as before.

## 1.1.16: a glasses setup check, Lock screens, and Send logs keeps Bluetooth names private

Reported by tester John Davis (his report was not posted on GitHub, so there is no issue to link).

- **Lock screens.** A Lock button sits at the end of the window keys on the open phone (Move, Resize, Further,
  Closer, Curve, Lock), and a "Lock screens" switch is in the Menu, which is also how the cover screen reaches it.
  While it is on, no window moves or resizes: press and hold, dragging by the edge, the resize handle, the window
  keys, a two hand stretch, Reset, Arrange and Adjust all: none of them shift a window. The blue resize
  handle is hidden, and a small lock shows at a window's corner instead, so it is clear why the window won't move.
  Clicks, scrolls, zooms and typing still reach the apps, and Close, Back, Refresh, Switch app, Wide view and Fit
  video still work. The setting is kept after Pocket XR or the phone restarts. The Lock button is 48 dp or larger
  and a screen reader reads it as a switch with its state.
- **The resize handle no longer covers app buttons.** The blue handle at a window's bottom right now catches the
  pointer only outside the window and on its very edge (0.3 degrees in), where before it took a block about 12 % of
  the window inside the corner, over the app. Hovering over it does nothing. A resize starts only after the handle
  is held for 150 ms and dragged at least 1 degree; a plain click or a long press there without a drag goes to the
  app, at the window's edge. A long press inside a window never resizes it.
- **Compose in Gmail and Outlook stays in the window.** These apps open the email editor as a new screen in a task
  of its own, started in a way Android does not tie to the window it came from, so Android put it on the phone's
  screen, where the phone last had focus. Pocket XR cannot choose the display for a screen another app starts, so
  for 6 seconds after a press in a window it watches the phone's screen, and a new screen of that same app that
  shows up there is moved back onto the window's display (as `am display move-stack` does, through Shizuku). Screens
  that were already open on the phone, and other apps, are never moved.
  Where Android still gets in the way: the editor can flash on the phone for a moment before it moves, since Android
  shows it there first. Without Shizuku running there is no way to move it, so it stays on the phone. If Android
  refuses the move, or sends the screen back to the phone, Pocket XR leaves it there rather than fight Android, and
  writes why in its log.

From [the testers issue](https://github.com/pocket-xr/pocket-xr/issues/12): on an XREAL 1S plugged into a Beam Pro,
windows turned faster than the room and were cut off past a certain angle. thejoeba spotted the cause: the glasses' own
Anchor or Follow setting.

- **Glasses setup check.** The first time a new glasses model connects, Pocket XR opens one short screen with only the
  steps those glasses need, then a 10 second check: turn your head left and right, and say whether the windows stayed
  put. Pocket XR measures its own head pose during the turn, so a yes with no real turn does not count. If the check
  fails, it names the one setting to change: Anchor mode on the glasses, their Stabilizer, XREAL's own software on the
  host, or Pocket XR's own Smooth Follow. It can be skipped, and once it passes it never comes up by itself again. It is
  also in Pocket XR Settings and the trackpad's Menu as "Glasses setup check", at any time. Buttons are 52 dp tall and a
  screen reader reads them as buttons, and reads the countdown and the result.
- **Steps for each model, in XREAL's words.** One, One Pro and 1S: press X on the glasses once and pick Follow mode, not
  Anchor; press X twice to open the menu and turn Stabilizer off under Spatial Screen (under Display on older
  firmware). The Air family, the Aura and glasses Pocket XR does not know get no steps for the glasses, since they have
  nothing to set. The One series is now also recognised by its USB product when its name says nothing.
- **Beam Pro and XREAL's apps.** On a Beam Pro the check says plainly that XREAL's spatial view also turns the picture
  with your head and should be closed before opening the Space, and that the Beam Pro may put One series glasses back in
  Anchor. On a phone with XREAL's app installed, it names the app and says it does no harm while closed, but to close it
  from Recents if it is open.
- The first run tutorial no longer starts with the glasses page; the check opens after it instead. The Anchor card
  (glasses that may be in Anchor mode) opens the check, and no longer shows once the check has passed.
- README: per model setup steps and a "Windows drift or get cut off" item. The Glasses report issue form says the same.

Privacy and John's Android 11 phone:

- **Send logs keeps Bluetooth names private.** "Send logs to Mike" already took out the names of the phone's paired
  devices. It now also takes out names it finds in the logs by their shape: "device name:", alias=, "connected to ...",
  and the device list and Name lines Android prints for Bluetooth, and it scrubs about.txt too. Each device becomes
  `<bt-device-1>`, `<bt-device-2>` and so on, the same marker in every file of the zip, so a log can still show that the
  same headset dropped twice without saying whose headset it is.
- **John's Android 11 head tracking fix, written up.** Since 1.1.9, head tracking and the control link also try the
  glasses at 169.254.1.1 on their second USB link when no network reaches 169.254.2.1, as on John's Galaxy S20 FE, and
  the "No route" line names the interface's addresses. What was found and what to check next is now in
  [docs/findings-john-s20fe-android11.md](docs/findings-john-s20fe-android11.md).

## 1.1.15: updates show up on their own, the beer row moves to the Menu

- New versions are easier to find. Pocket XR checks GitHub Releases when it starts and every 12 hours in the
  background, instead of only when Pocket XR Settings opens. Before, the check almost never ran: the app's icon opens
  the trackpad, and the check and its card lived only on the Settings screen behind the Menu.
- A newer version shows as "Update available: x.y.z" across the top of the trackpad and of Pocket XR Settings, with
  Update and Later buttons. It also shows as the first line of the trackpad's Menu and as a line on the glasses'
  Control Center. Later hides it until the next version. Update downloads it as before.
- One notification per new version, "Pocket XR x.y.z is ready", which opens the trackpad with the banner. On Android 13
  and later, Pocket XR asks once to allow notifications, when it first finds an update. If you say no, the banner is
  still there.
- "Check for updates" and the installed version are now at the top of Pocket XR Settings, not at the end of the list.
- The Google Play version is unchanged: it never checks GitHub and shows none of this.
- "Buy me a beer" moved from the bottom of Pocket XR Settings to the top of the trackpad's Menu. It is now one slim
  row with a small beer mug that opens Mike's Ko-fi page in the browser, with "Update available" right under it when
  there is one. The card in Settings is gone. The Google Play version still leaves it out.
- "Hands on the NPU" in the Developer card now applies right away. Before, turning it off only took effect the next
  time hand tracking started, which in a long session never happened, so the NPU kept running. Off now means no NPU
  work at all from the next camera frame. On builds the NPU models in the background while tracking carries on, so
  switching either way never pauses tracking.
- The hand tracking log no longer looks like the glasses camera restarts every 30 seconds. Its repeated camera line
  now says "Camera still open" with how long the stream has run, and the Tracking line says NPU when the NPU did the
  work instead of CPU.

## 1.1.14: NPU hand tracking is back on Exynos phones

- Hand tracking runs on the NPU again on phones with the Exynos 2500, like the Galaxy Z Flip 7. The 1.1.13 download
  left out the hand models built for that chip, so hands ran on the GPU or CPU there, slower and using more battery.
  This version puts them back. Other phones and the Google Play version are unchanged.

## 1.1.13: a Head tracking switch, Send logs to Mike

- A small "Buy me a beer" card at the bottom of Pocket XR Settings opens Mike's Ko-fi page in the browser. It's never
  required, and nothing pops up or reminds you. The Google Play build leaves it out.
- A Head tracking switch in Settings. Off, Pocket XR stops reading the glasses' motion sensor and windows stay in front
  of you and turn with your head; on, they stay put as you turn. The choice is kept.
- "Send logs to Mike" in Settings and on the head tracking diagnostics screen. It puts Pocket XR's own logs, the head
  tracking diagnostics, the app version, your phone model, Android version and glasses into one zip and opens an email
  to connect@mikehaye.com with it attached, for you to add a few words to and send. Email addresses, account names,
  Wi-Fi names, Bluetooth device names, MAC addresses and IP addresses (other than the glasses' own link) are taken out
  first. Nothing is sent until you tap send in your email app.

## 1.1.12: head tracking on XREAL Air glasses, ready for Google Play

- Head tracking (Body Anchor, 3DoF) now works on the XREAL Air, Air 2, Air 2 Pro and Air 2 Ultra. These models send
  their motion sensor over USB HID, not over the USB network the One family uses, so before this version Body Anchor
  still said 3DoF but the Space stayed head locked and windows turned with your head. Pocket XR now reads their sensor
  directly, and Android asks once for USB access when the glasses are plugged in. (Not yet tested by the project on
  these glasses.)
- A `PocketXR-Tracking` log line every second while head tracking runs: the glasses found, where the head pose comes
  from, the tracking mode, the sensor rate, the pose, and in plain words what is wrong when there is no pose. The
  Glasses report includes the newest line.
- Pocket XR and the add-on are prepared for Google Play (docs/PLAY.md).
- The add-on has its own screen. Before its accessibility service or notification access is switched on, it says what
  each one reads, what it is used for and that nothing leaves the phone, and it goes on to Android's page only when you
  tap "Agree and turn on". Pocket XR's buttons for both open that screen.
- Notification access is switched on by you in Android's own page, after the add-on's explanation, instead of silently
  through Shizuku. An add-on from before this version is still turned on through Shizuku.
- A copy of Pocket XR from Google Play gets its updates from Google Play: it hides "Check for updates" and links to the
  add-on's Play page.
- A privacy policy: [PRIVACY.md](PRIVACY.md).
- The add-on is version 1.1.

## 1.1.10: one-finger scroll, Exit, clearer head tracking diagnostics

- One-finger scroll on Spatial Mouse: slide one finger on the pad to scroll the window under the dot, and the dot holds
  still while you scroll. A tap still clicks and press and hold still drags. It's on by default, with a switch in the
  Menu, and the tutorial and the hint mention it. (#35)
- Exit Pocket XR: a new Exit action on the Pocket XR notification and an Exit Pocket XR row at the end of the trackpad
  Menu. It closes the Space, hand and head tracking and the notification, and nothing opens by itself again until you
  open Pocket XR. The tutorial's last page says where it is.
- Head tracking diagnostics now has Copy all and Share buttons that copy or share the whole screen as text, not just
  one line.
- Glasses that send head tracking over USB HID (like XREAL's Air family, and the newer XREAL-made glasses on USB
  3318:0442) are now named as such in the diagnostics and the Glasses report, instead of blaming a missing USB network.
  Pocket XR does not read head tracking over HID yet, so on these glasses the Space stays head locked.
- The "no USB network" line names the phone's own system: One UI only on Samsung phones, Android on others.
- The Glasses report lists each USB interface with its type (HID, CDC NCM, audio and so on), and says which head
  tracking link the glasses use.
- When a second window of an app (like Chrome) takes the app from your first window while Menu, Open a new instance
  is off, the first window now says so and offers Turn on and reopen, instead of saying the app can only show one
  window. That message now shows only when the switch is already on. The Menu row explains what the switch is for.

## 1.1.9: easier setup, Spatial Mouse on the Retroid Nova, head tracking on Android 11

- Spatial Mouse now aims with the top edge of the screen as you hold it, whichever way the system turned the screen.
  On the Retroid Nova, 1.1.8 still went portrait and tilting the left side moved the dot up and down. Now it stays
  landscape and tilting the top edge does. (#33)
- Head tracking now also works on phones that reach the glasses over their other USB network link, as some Android 11
  phones do.
- New users now get set up as they go. On XREAL One glasses (One, One Pro, 1S), the first-run tutorial starts with a
  short Glasses setup page: use Follow mode, turn Stabilizer off, then look left and right to check. It also shows the
  first time a new model is plugged in, and any time from Menu, Glasses setup. If the glasses look like they're in
  Anchor mode, a card offers that page in one tap. (#31)
- The tutorial has a Spatial Mouse page that shows how to hold your device: upright on a phone, sideways like a
  controller on a landscape handheld. (#33)
- Apps that can only show one window, like Chrome on Android 11, no longer leave a window saying Reopen / Close. It now
  says the app moved to your newest window, with one button to close the old one.

## 1.1.8: Follow mode on the One, Spatial Mouse on landscape handhelds

- On XREAL One glasses, the hint now says to use Follow mode (not Anchor) with Stabilizer off. In Anchor mode the
  glasses hold the picture still themselves, on top of Pocket XR's head tracking, so windows moved the wrong way on
  each turn. (#31)
- On handhelds whose screen is landscape by nature, like the Retroid Nova, Spatial Mouse now stays landscape and aims
  with the top edge. Before, it turned the screen to portrait and aimed with the side edge. Phones work as before. (#33)

## 1.1.7: level calibration for the One Pro

- The level calibration now takes glasses tilted up to 40 degrees. The XREAL One Pro's is steeper than the Aura's, so
  it was refused, and its windows sat too high or too low. Run the level calibration once after updating.

## 1.1.6: Developer settings you can find

- The trackpad's Menu ends with a "Developer settings" switch. Before, the Developer card only opened with a hidden
  long press on the Menu's title.

## 1.1.5: recording and reports on more phones

- Recording the glasses could save an empty video on some phones (seen on a Retroid). It now uses a video surface
  those phones accept, and if nothing was recorded it says so instead of "Saved".
- The Glasses report is shared as a file too, so a file manager can save it to Downloads.

## 1.1.4: One Pro windows stay level

- On the XREAL One Pro, windows rolled as you turned your head. Its factory calibration was applied in a way only
  checked on the Aura; Pocket XR now uses its own level calibration there, as on the One.
- The Glasses report shows the factory calibration's numbers.

## 1.1.3: allow in Shizuku with one tap

- When Shizuku is running but hasn't allowed Pocket XR yet, the trackpad now says so, and one tap opens Shizuku's
  allow dialog. Before, the button was only under Menu, Pocket XR Settings, Spatial windows.

## 1.1.2: landscape handhelds

- On a device whose screen is landscape by nature, such as a Retroid, Pocket XR starts on the Horizontal Trackpad.
  Before, it started on the Vertical one, which turned the screen on its side. A page you chose is kept.
- The first tutorial fits short screens.
- Pocket XR no longer closes at start when Android refuses its log folder.

## 1.1.1: Android 11 to 13

- On Android 11, Pocket XR closed as soon as the glasses were plugged in (seen on a Galaxy S20 FE). It now runs there.
- App windows in the Space now open on Android 11 to 13 too. Before, they needed Android 14, and the Space opened
  empty on older phones. Tested on Android 11 and 13; there, windows run at the refresh rate Android gives them.

## 1.1.0: the add-on, so Pocket XR installs everywhere

- In some countries Google Play Protect blocks installing a sideloaded app that uses an accessibility service or
  notification access. Pocket XR now uses neither. Both moved to a second, optional app: the Pocket XR add-on. Pocket XR
  itself installs everywhere, and the Space, the trackpad and hand tracking in the Space work without the add-on.
- The add-on gives you the cursor outside the Space (flat DeX mode) and the Notifications page. If you use either,
  install the add-on from its own release and turn it on again: its pointer under Accessibility, and notification
  access from the Notifications page. Pocket XR's Hands screen shows what's missing.
- Glasses whose display name the phone shows differently (seen on an Honor Magic V5 with an XREAL One Pro) were taken
  for unknown glasses, and the picture could be upside down. Pocket XR now also uses the glasses' USB id.
- Updated build tools and libraries (Android Gradle Plugin 9.5, Gradle 9.8.2, Compose 1.12).

## 1.0.1: crash reports

- If Pocket XR closes unexpectedly, the next time you open it, it offers to send a crash report: by email or as a
  GitHub bug, both filled in already. You see the report before it's sent, and nothing is sent unless you send it.
  It holds the error, the app version, your phone model and Android version.
- The README explains the Play Protect block some countries show at install ("This app can request access to
  sensitive data"), and how to install with adb instead.

## 1.0.0 (2 November 2026): the first public release

Pocket XR was developed privately through 107 test versions on a Galaxy Z Flip 7 with XREAL Aura and One glasses. This
is the first version shared with everyone.

### Windows in space

- Any Android app opens as a window around you, at its own size and distance. Windows can be dragged by their edges,
  resized from a corner, curved, and pushed further or pulled closer.
- Snapping, including into the space between two windows, resized to fit.
- Adjust all: move, push, pull or resize every window together, from the Menu, the Control Center, or by holding both
  hands up.
- Arrange layouts for any number of windows, and presets that reopen a set of windows exactly where they were.
- Window buttons: Back, Reopen, Switch app (keeps the window's place and size), Wide view, Close.
- Wide view gives phone-shaped apps a landscape screen.
- A launcher with search, the date and time, and the phone's battery, cell signal and Wi-Fi.
- A Control Center: layouts, presets, Wi-Fi, Bluetooth, notifications, quick settings and recentre.
- Record the glasses: a clean 1080p video of what you see, saved to Movies/Pocket XR.

### Seeing it

- 3D side by side, with each window at a real depth. Pocket XR switches the glasses to 3D by itself where they allow
  it.
- Head tracking from the glasses' own motion sensor, with prediction and paced to the glasses' display, so windows
  stay put.
- A design made for see-through glasses: dark glass, light rims, depth for what you're pointing at.

### Controls

- A phone trackpad with tap, two-finger scroll and pinch, three-finger swipes and a scroll strip, plus buttons for
  the keyboard, recentre, and moving, resizing, pushing, pulling and curving a window. It also works on a Galaxy Z
  Flip's cover screen.
- The Spatial Mouse: point the phone like a laser.
- Hand tracking on glasses with a camera (XREAL Aura): point, pinch, drag, two-handed resize.
- Typing from the phone's keyboard into the focused window.

### Glasses

- Tested on the XREAL Aura and the XREAL One. The One Pro and 1S are expected to work and are in community testing.
- A Glasses report (Pocket XR Settings, Advanced), for reporting how your glasses work.
- Each model's own field of view, sensor axes and calibration.

### The app

- Settings for spatial windows, hands and the trackpad, each with a reset to Pocket XR's defaults.
- An update check against GitHub Releases, at most once a day, which can be turned off. Updates also come through
  Obtainium.
