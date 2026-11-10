# OpenXR and ALVR: the plan to finish

Private until Mike says otherwise. Written 7 November 2026 from three studies: the ALVR and Monado source, Joy-Con sources
and our own code, and the behaviour of another glasses app. No timing below has been measured on this rig yet.

## What is wrong today, most important first

| # | Finding | Effect | Evidence |
| --- | --- | --- | --- |
| 1 | The last ALVR session sent 1280 x 608 per eye: 2144 x 1184 with default foveation | Soft picture | Read from session.json. Conflicts with an earlier retest, so repeat on fine text |
| 2 | Monado runs on a made-up clock with no feedback from the glasses' real refresh | Repeating stutter, wrong timewarp times | Read in source. The other app replaced this with real present times |
| 3 | A slow client can fall to 30 fps on a 60 Hz display | Stutter | One Monado option, `U_PACING_APP_USE_MIN_FRAME_PERIOD` |
| 4 | Clients are told the phone's refresh rates, not the glasses' | Wrong pacing | Read in source |
| 5 | Stereo is never switched for a game | Needs the right mode by luck | docs/OPENXR.md |
| 6 | 1080 rounds down to 1056 in ALVR | Softer | Read in source. Use 1920 x 1088 |
| 7 | The PC is on Wi-Fi too, so frames cross the air twice | Latency and jitter | Read from the PC |
| 8 | Joy-Con samples come in bursts of three, weighted by arrival time | About 66 Hz, not 200 Hz, and uneven | `OpenXrPads.kt` |
| 9 | Joy-Con poses are stamped on arrival and have no linear velocity | No true prediction | `OpenXrPads.kt` and the driver |
| 10 | Turning the head moves both hands | Wrong arm model | `JoyConPose.kt` |
| 11 | The hand camera may keep running during a game | Heat | To check |

Point 2 was thought risky because it needs Android's real present times on the glasses display. Pocket XR already reads
those for its own frame clock, so that risk looks small.

## Testing without the glasses

An emulator alone can't do this: it has no Samsung decoder, Xclipse GPU or Joy-Con path. The rig uses the real phone:

1. **Test copy:** package `com.hayeco.flipspaces.dev`, debug key, installed beside the real app, so no password per build.
2. **Fake glasses:** a 3840 x 1080 virtual display at 60 Hz from the panel service.
3. **Fake wearer:** recorded head and Joy-Con motion replayed through the same feeds.
4. **PC:** SteamVR and ALVR with a test scene with fine text, moving bars and a frame counter.
5. **Measuring:** frames read back from the virtual display and compared with the source for sharpness; ALVR's per-frame
   statistics from its event socket; Monado's timing logs; thermal status.

The Android XR emulator stays useful for launcher and flow checks. It can't judge quality or latency. Mike's eyes are still
needed last for feel, the glasses' mode changes and real Bluetooth.

## Rig results, 7 November 2026

Wi-Fi, SteamVR Home, ALVR p50s, "doubled" is frames the rig's display got twice out of 1200. Identical runs differ by up
to 20 ms total and 11 to 139 doubled, so only large or repeated changes count.

| Change | Result | Kept |
| --- | --- | --- |
| Foveation off, 1920 x 1088 | Text clearly sharper | Yes |
| `max_buffering_frames` 1 | Decoder queue 24 to 12 ms, but by throwing frames away: see below | No, back to 2 |
| Decoder `low-latency`, Samsung `vendor.rtc-ext-dec-low-latency.enable` | Decode stays about 15 ms. The decoder reports output delay 8 from the stream | No |
| Without `operating-rate` and `priority` | Decode 15 to 20 ms | No |
| Monado `U_PACING_APP_MIN_MARGIN_MS` 6 | Doubled 11, 59, 95 against 76, 98, 105 at 2. Not clear | No |
| Monado min frame period | No change | No |
| Monado real display timing | Worse on the rig. Needs the real glasses | Off |
| Bitrate 60 to 150 Mbps | The encoder sends at most about 58 Mbps on this scene | Needs a fast scene |

What the numbers mean:

- Monado logs most ALVR frames as "late". That is not a miss: ALVR stamps a frame with the time its pose was predicted
  for (`stream.rs`, `openxr_display_time`), not with Monado's refresh. The rig's doubled count is the real measure.
- Doubled frames come from 60 fps frames arriving on either side of a 60 Hz deadline. ALVR times the PC from the vsync it
  is told, and Monado's vsync is made up, so this is the glasses test for the real display timing.

**Frames thrown away, found and fixed later on 7 November.** ALVR's `GraphStatistics` sends one row per new frame the
phone shows, so rows a second is the real frame rate. At `max_buffering_frames` 1 the phone showed 38 to 41 of the 60
frames the PC sent; at 2, ALVR's default, 57.4 to 57.5, with the same delay (63 to 78 ms either way). The cause is in
`video_decoder/android.rs`, `dequeue_frame`: a frame is dropped whenever the running average of waiting frames goes over
the setting. A steady 60 in, 60 out stream sits at exactly one waiting frame, so at 1 any jitter drops a frame. The rig's
own gaps agree: 53 to 112 of 1200 over 25 ms at 1, 9 to 12 at 2. Changing it needs a SteamVR restart (`bufrun.sh`).
Keep 2. The earlier "doubled frames" counts were real, not only the rig's reader.

Fixed: every game start was taken by the Space's watchdog for a frozen Space, and 4 s later it closed all the windows.
`SpaceSession.gameOwnsGlasses` now tells the watchdog the pause is on purpose and rests the hand model during the game.

## Joy-Cons, 7 November 2026

Done, in `core` with tests (`JoyConPoseTest.kt`), and in the Monado driver:

- `JoyConClock`: each sample at its true time. One read brings a report's three samples; they are spread a third of a
  report apart and set back 8 ms for the radio (a starting value, to be measured). Monado now gets the sample's own time.
- `JoyConPose`: drift learned at rest and kept per hand (`joycons` prefs), gravity pull that fades with turn rate and
  with readings away from 1 g, exact integration, a hold after a clipped reading, lining up by itself when first held
  level and still, and a slow pull back from drift past 15 degrees.
- `JoyConArm`: Daydream's arm. The body follows the head only while the arm moves. Velocity goes to Monado.
- `JoyConStick`: a 0.12 round dead zone, rescaled. Touches for A, B, X, Y, trigger, stick and thumb rest.
- Monado: linear velocity, prediction full to 30 ms then easing to at most 70 ms, untracked after 150 ms silent.

Measured on the Flip with both Joy-Cons (no glasses): a report every 14.9 ms, 0.1 to 0.6 % lost, 1.7 ms from the panel
service to the app, drift 0.65 and 0.78 degrees a second, learned and kept.

Still to measure with someone holding them: the sample order inside a report (waving, `getevent -lt`), the link delay,
the arm's feel and height in Beat Saber, a saber angle setting, and rumble.

## Glasses session, 7 November 2026 (evening)

Buffering 2, SteamVR Home then Beat Saber, phone at heat 2 (skin 43 C), swap full, not charging.

| Run | New frames a second | Total p50 | Notes |
| --- | --- | --- | --- |
| Display timing off | 32.3 | 102 ms | ALVR client at 30 fps (`client_fps`), decoder queue 37 ms |
| Display timing on | 33.1 | 100 ms | No better; `vsync_queue` reads 0. Left off |

The rig gave 57.5 at heat 0 to 1. On the glasses the ALVR client itself runs at half rate, so frames pile up and are
dropped. To separate next: heat, memory, and the real head tracking path (the rig's head is a sine).

Mike, Beat Saber: "felt good". Problems, in his words:

- The sabers aren't in his actual hands (arm model offset; direction to ask).
- The picture is "a bit shaky ... like a small earthquake", though motion felt smooth. Suspect the head pose feed to
  Monado: no smoothing and prediction from the first degree. Not seen on the rig.
- Sabers sometimes point a different way from the Joy-Con. Capture or Home re-centres and worked. Likely cause: the
  automatic line-up fires whenever a Joy-Con is first level and still, even when it isn't pointing at the gaze.
- Wants a "zoom out" like a wider FPS field of view: the game's view is the glasses' true field of view, so the world
  feels close. A wider game view than the glasses show makes it look further, at the cost of world motion not matching
  head turns.

Joy-Cons while streaming: left loses 0.7 % of reports, right 5 % (0.6 % on the table): Bluetooth and Wi-Fi share the
radio. Drift learned 0.8 left, 0.9 to 1.9 right degrees a second.

Wave test, right Joy-Con, 793 moving reports: joins fit slightly better if each report's samples are newest first
(roughness 11.3 M against 11.8 M; join 1.15 M against 1.29 M). Too weak to change the code on; repeat with harder waving.

### After the session: half rate, shake and hands

- **Half rate is heat.** At heat 3 the phone caps the GPU at 240 to 269 MHz of 999 and the CPU near a third: big cores
  1.15 GHz of 2.7, the prime core 1.06 of 3.3. The GPU stays only 24 to 34 % busy, so the CPU is what runs out. With
  the glasses, Pocket XR also reads and fuses the glasses' motion and runs the link, which the rig doesn't.
- Monado's log (`U_PACING_APP_LOG` debug, which itself costs frames) shows ALVR's begin-to-end time at 14 ms p50 and
  25 ms p90 on the glasses. Over a refresh, Monado moves it to every other refresh (`calc_app_period`), so 30 a second.
  `U_PACING_APP_USE_MIN_FRAME_PERIOD` gave 29 to 35 on the glasses in one run each. Retry when cool.
- Not the cause: the head feed (the rig's sine head on the real glasses: 33 against 30), display timing, buffering.
- **Shake:** the game head now goes through its own One Euro filter (cutoff 1 Hz, beta 0.08, under 2 degrees behind
  at 100 degrees a second) and Monado's look-ahead rate fades out below 15 degrees a second. Debug: RIG `smooth --ez on`.
  Untested on the glasses.
- **Hands:** Mike, both sabers about a foot below his hands and to the right. `JoyConArm.offset` now raises both hands
  0.25 m by default and is kept in the "joycons" preferences; RIG `arm --ef x --ef y --ef z` moves them live. The
  "right" part isn't fixed yet: to tune with him wearing them.
- Debug, no rig needed: RIG `wearer` and `nowearer` swap the glasses' head for the rig's.

### Cutting load, 7 November 2026 (late)

- The head thread reads the glasses 1000 times a second and costs 44 to 57 % of a core. A profile put a quarter of it
  in the tremor predictor's learning. It now pauses while a game has the glasses (`HeadPredictor.learning`). With the
  glasses lying still it was already off, so no saving showed on the desk; it should save while worn.
- The Space's vsync thread (3.5 %) now stops while a game runs.
- Test setup faults found, both now fixed in `session.sh`: ALVR's own window opened on the glasses as a see-through DeX
  window with a white title bar (Mike saw it), now `am start --display 0`; and the installed 1.2.2 reopened its own
  Space on plug-in and ran its hand model behind the game (about 37 % of a core plus the NPU). Earlier glasses numbers
  include both.
- Glasses frame rate after that: 42 to 58 new frames a second in identical runs, cool or warm. Slow runs show the PC's
  game time p95 rising (up to 75 ms) together with the phone's decode and compositor times. Single runs can't rank fixes
  any more; use 60 s runs, three each, alternated.
- Product note: a Space that is hidden behind another still runs the hand model. Only matters with two copies installed.

### Worn test, 7 November 2026 (evening)

- **Shake:** blind A/B in Beat Saber's menu, Mike picked A, the new game head smoothing, as steadier. Kept on.
- **Hands:** tuned live until "Yes, good": 0.15 m up, 0.4 m back, 60 % of Daydream's shoulder width. Now the defaults
  (`JoyConArm.DEFAULT_OFFSET`, `DEFAULT_SPREAD`), and RIG `arm --ef x/y/z --ef spread` still moves them live.
- **Choppy in Beat Saber:** same stream, glasses 37 to 46 new frames a second, rig 55 to 56; on the glasses decode
  16 against 12 ms and the client compositor 7 against 4 ms. Ruled out: heat (phone cool), bitrate (100 to 60 Mbps,
  no change, left at 60), Monado full rate pacing (33 to 37, left on), the head tracker (same 54 % on the rig), GPU
  composition (the game is one DEVICE overlay on the glasses), mailbox present mode (37 and 44 against FIFO's 39 and
  46; back to FIFO).
- **Lead:** Monado runs the "fake" pacer at 16.667 ms; SurfaceFlinger measures the glasses at 16.66 ms. The phase
  drifts about a frame every 30 to 40 s, and with FIFO a bad phase holds frames back. Runs look good or bad for longer
  than a 20 s window, which fits. Next: a pacer locked to the glasses' real present times (`vkGetPastPresentationTimingGOOGLE`
  is there; the earlier display timing try read vsync_queue 0, so check what it predicts), measured over 60 s runs.

### A pacer locked to the glasses, 7 November 2026 (night)

- Why Monado's display timing was broken: on this phone `vkGetPastPresentationTimingGOOGLE` returns one frozen
  actual present time for every frame (904 in a row, about 19 s in the past), so that pacer plans against a time long
  gone (ALVR's vsync_queue read 0).
- Built: Monado's fake pacer takes an outside vblank (`u_pc_fake_external_vblank`, fed through
  `pocketxr_feed_vblank` and `PocketFeed.nativeVblank`; used while under 0.5 s old). Pocket XR's paused Space draws one
  pixel a refresh under the game and reads when each was shown (EGL frame timestamps, which work here); `VblankLock`
  (tested) turns those into the phase and the period. Measured 16.6564 ms; Monado logs "pacing to the display's real
  vblank". Debug: RIG `gameclock --ez on`.
- Result, one stream switched live every 40 s: on 40.1 and 37.5, off 36.5 and 36.6 new frames a second. A small gain,
  kept on, but drift is not the main cause.
- Still unexplained: with the same Beat Saber stream, the glasses run decode 16 against 12 ms and the client
  compositor 7 against 3 ms compared with the rig. Next: a Perfetto trace of ALVR's render thread and Monado's
  compositor, glasses against rig, to see where the frame waits.
- Popping sound (Mike): ALVR's game audio buffer was 50 ms against network spikes near 50 ms. Raised to 100 ms, to
  hear in the next session (costs about 50 ms of sound against picture).

### Popping sound, 7 November 2026 (night)

Not Pocket XR and not ALVR: Mike hears the pops in YouTube with Pocket XR closed. Ruled out on the way: Wi-Fi, ALVR's
phone buffer (no packet loss, recovery or overflow with client Info logs on), its 50 to 100 ms buffer, the phone's
output path (USB headset MMAP and DisplayPort mixer both pop), the PC capture (Pebble V3 and Virtual Audio Cable both
pop). A Perfetto trace (YouTube in the Space, 5 or 6 pops in 30 s) shows the mixer waking every 20 ms, longest gap
30 ms, and AudioFlinger counted no underruns. Left: the glasses' audio, the cable, the phone's USB audio driver.
ALVR audio buffer and client log level are back to 50 ms and Error.

The same trace explains the short lags: SurfaceFlinger missed about 40 deadlines in 30 s, most of them GPU deadlines.
Each Pocket XR window is a virtual display that SurfaceFlinger composes on the GPU every frame. Worth measuring next.

### Short lags in the Space, 7 November 2026 (night)

Perfetto, 15 s each, YouTube playing in the 1.2.2 Space, phone cool (heat 0): folded, 0 missed SurfaceFlinger deadlines
(YouTube window composed on the GPU 23 times a second, cover screen 20); open, 0 missed (inner screen on the GPU 38 a
second). The earlier 30 s at heat 2 had about 40, with the GPU at its 356 MHz floor and capped at 428 to 480 MHz. So the
lags come with heat. Window sizing to the glasses would save only about 25 % (panels are already 44 px a degree,
about the glasses' own), so it was not built; branch fix/window-size-to-glasses in the public clone is empty.

Correction, same night: with the phone cool Mike has no lags and no popping, in the Space and in Beat Saber's place
alike. The "Pocket XR closed still pops" test ran while the phone was still hot, so the pops were heat too (most
likely the throttled phone starving USB audio), not the glasses. Next: Pocket XR's heat in normal use (head tracking's
tremor learning at 1000 samples a second, hand detection).

### Head thread heat, 7 November 2026 (night)

Public branch perf/cut-heat (from 1.2.2, uncommitted): the worn tremor fit learns from every 4th sample, weighted 4
(`TremorPredictor.learnEvery`, tests pin the old path to the bit and keep the new one within 5 %). The installed 1.2.2 is
release-signed, so measured on the test copy (debug), worn: head thread 77, 88, 84 % before, 84, 72, 78 % after:
within noise. simpleperf after (worn): socket reads 39 % (poll plus recvfrom per 1 kHz report: the socket's timeout
makes each read two system calls), HeadPredictor.add 20 %, HeadFusion.update 18 % (staticStep 13 %), TremorPredictor
12 % (learning 8 %). Next: read without the socket timeout (watch for stalls another way), then staticStep.

Reads, same night: reads with no socket timeout (one system call a report, a silence watch thread instead) saved
nothing: the poll's time moved into recvfrom, so the cost is the 1000 wake-ups, not the extra call. Undone. Then
SO_RCVLOWAT of one report plus a byte (`GlassesNetwork.Link.wakeAfter`, `HeadTracker.WAKE_REPORTS` 2): 495 reads a
second of 2 reports, newest report 0.1 ms in the pipe, longest gap 7.5 ms (6 before); head thread worn 52, 51, 40 %
against 81, 76, 75 % (test copy, debug). The kernel holds the read, so no late wake like 0.5.49's rests. Both changes on
perf/cut-heat (public, uncommitted, builds) and in the test copy.

## Build order

1. **Rig** as above, then baseline numbers.
2. **Settings:** foveation off, 1920 x 1088, HEVC CBR at 100 then 150 Mbps, UDP, `max_buffering_frames` 2 (1 drops a third of frames), then decoder
   low latency keys one at a time. PC on Ethernet.
3. **Timing:** Monado pacer on real present times, minimum frame period, glasses' refresh only, then check for the extra
   16.7 ms wait on frame delivery.
4. **Glasses:** full SBS on game open, restored on close, follow checked (`keepFollow`), Spatial Anchor off, Steady mode out.
5. **Joy-Cons:** true per-sample timing, VQF at full rate with saved gyro bias, honest timestamps and velocity, clamped
   extrapolation, Daydream style arm model, slow yaw correction instead of the alignment press, all Touch inputs, dead zones.
6. **Rumble:** try `BluetoothHidHost.sendData()`. The kernel has no force feedback on this phone.
7. **Heat:** stop the hand camera and what else a game doesn't need, ADPF hint session in the client, phone panel refresh.
8. **One button:** a Play VR entry that handles glasses, Joy-Cons and the stream. Decide then whether to embed
   `alvr_client_core` (MIT), which removes the broker and second APK and one GPU pass, at a cost of weeks. Monado stays for
   native OpenXR apps.
9. **Hands as an anchor:** the glasses' camera as slow position and yaw evidence for the Joy-Cons, if a held Joy-Con can be
   seen at all. Needs a short recording.
