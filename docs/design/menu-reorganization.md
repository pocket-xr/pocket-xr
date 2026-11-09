# The trackpad Menu, reorganized (1.2.6)

Mike: the trackpad's Menu "has become congested and feels like too much for users". By 1.2.5 it was one long card of
up to 17 mixed rows, then seven more headed cards, about 50 rows in all when everything showed, with greyed rows for
things that could not be used. This page is the audit, the new structure and the reasons.

The order and the visibility rules live in one place, `core/.../pointer/TrackpadMenu.kt`, and are unit tested
(`core/.../pointer/TrackpadMenu126Test.kt`, `app/.../TrackpadMenu126Test.kt`). `PadScreen.buildDrawer` only draws what
the model returns. No stored key, label or action changed; only where a row sits.

## 1. Audit (1.2.5)

Use: **S** every session, **O** often, **R** rarely (set once, or for testing).

| # | Row (1.2.5 label) | What it does | Kind | Use | Shows when |
|---|---|---|---|---|---|
| 1 | Buy me a beer / Ko-fi | Opens ko-fi.com | Action | R | Not the Play build |
| 2 | Pocket XR x.y.z is ready | Downloads the update | Action | R | An update is found |
| 3 | Open Control Center | The glasses' Control Center, without three fingers | Action | O | Always (only works with the Space open) |
| 4 | Go to the phone's home screen | Phone's launcher | Action | O | Always |
| 5 | Pocket XR Settings | The settings home | Action | O | Always |
| 6 | Text size in (app) | A-, 100 %, A+ for the focused window's app | Stepper | S | A window is focused |
| 7 | Glasses setup check | The glasses' settings and a 10 second check | Action | R | Glasses known |
| 8 | Side by side 3D in (app) | SBS 3D for the focused window's app | Toggle | O | A window is focused |
| 9 | 2D while moving | SBS shows flat while moving | Toggle | R | SBS 3D on |
| 10 | Record the glasses / Stop recording | Records the left eye | Toggle | O | Always (only works with the Space open) |
| 11 | Air display mode (untested) | Switch to read and set an Air's mode | Toggle | R | Air glasses plugged in |
| 12 | Glasses display: (mode) | Switches the Air 2D / 3D | Action | R | Air switch on |
| 13 | Game keys | Key clusters beside the trackpad | Toggle | S (gamers) | Open phone, not the cover |
| 14 | Game controller: Off / Gamepad / Touch | Virtual controller | Cycle | O (gamers) | Open phone |
| 15 | Set touch spots | Touch mode's spot setting | Toggle | R | Controller in Touch |
| 16 | Clear touch spots for this game | Forgets the spots | Action | R | Controller in Touch |
| 17 | Unlock phone | Asks the keyguard to unlock | Action | O | Always (only useful when locked) |
| 18 | Pocket mode | Black locked phone, glasses keep running | Action | S | Always (only works with the Space open) |
| 19 | Cursor Speed | Slider | Slider | R | Always |
| 20 | Two-finger scroll speed | Slider | Slider | R | Always |
| 21 | Scroll strip speed | Slider | Slider | R | Always |
| 22 | One-finger scroll on Spatial Mouse | Toggle | Toggle | R | Always |
| 23 | Scroll strip (Left / Right) | Strip side | Choice | R | Open phone |
| 24 | Window buttons on the cover screen | Rail side | Choice | R | Cover screen |
| 25 | Scroll strip on the cover screen | Strip side | Choice | R | Cover screen |
| 26 | Sideways trackpad direction | Default / Inverted | Choice | R | Sideways trackpad, open phone |
| 27 | Cursor follows head | Toggle | Toggle | R | Always |
| 28 | Trackpad keeps screen on | Toggle | Toggle | R | Always |
| 29 | How to use the trackpad | The tutorial | Action | R | Always |
| 30 | Smooth Follow (0DoF) | Tracking mode | Radio | O | Always |
| 31 | Body Anchor (3DoF) | Tracking mode | Radio | O | Always |
| 32 | Space locked (6DoF) | Tracking mode | Radio | never | Always, greyed (no glasses have it) |
| 33 | Lock screens | Freezes every window | Toggle | O | Always |
| 34 | Snap windows | Toggle | Toggle | R | Always |
| 35 | Open a new instance if the app is already running | Toggle | Toggle | R | Always |
| 36 | Arrange N windows: layouts | Arranges the windows | Action list | O | Always; "Open a window first" greyed with none |
| 37 | Adjust all windows together | Holds every window | Action | O | Windows open |
| 38 | Window presets: each preset | Open, rename, delete | Action list | O | Saved presets |
| 39 | Save current windows as a preset | Saves | Action | O | Always; greyed with no windows |
| 40 | New windows curve | Slider | Slider | R | Always |
| 41 | Reset these settings to Pocket XR defaults | Asks, then resets | Action | R | Always |
| 42 | Developer settings | Shows the Developer card | Toggle | R | Always |
| 43 | Developer card (9 switches) | Tests and A/Bs | Toggles | R | Developer settings on |
| 44 | Exit Pocket XR | Quits | Action | R | Always, last |

Problems: the every-session things (Pocket mode, Game keys, Text size) were mixed in one long card with rarely used
ones (Glasses check, Air display); sliders set once sat high up and pushed the Space down; four rows needed the Space
but showed without it; three rows showed greyed; headings were a flat list of seven with no hierarchy.

## 2. The new structure

Top down. Collapsible sections remember open or closed in the `trackpad` preferences (`menu_open_<section>`, new keys).

1. **POCKET XR** wordmark, **Buy me a beer** pill (Ko-fi), and the **update** row in its own card when there is one.
2. **Quick tiles**, four across, one tap each, lit in the accent while on:
   Steady mode, Pocket mode, Game keys, Open Control Center, Record, Unlock phone, Phone home, Pocket XR Settings.
3. **This window** (only with a focused window, always open): Text size in (app), Side by side 3D in (app), 2D while moving.
4. **Space** (open by default): Tracking (Smooth Follow / Body Anchor side by side), Lock screens; *Arrange N windows*
   with its layouts and Adjust all windows together; *Window presets*.
5. **Trackpad and controls** (closed by default): Cursor Speed, Two-finger scroll speed, Scroll strip speed, One-finger
   scroll on Spatial Mouse, Scroll strip side (or the cover's two choices), Sideways trackpad direction, Cursor follows
   head, Trackpad keeps screen on; *Games*: Game controller, Set touch spots, Clear touch spots.
6. **Windows and glasses** (closed): Snap windows, Open a new instance, New windows curve; *Glasses*: Glasses setup
   check, Air display mode, Glasses display.
7. **Help and more** (closed): How to use the trackpad, Reset these settings, Developer settings; *Developer* card.
8. **Exit Pocket XR**, always last (the tutorial says so).

A closed section shows its title and one quiet line of what is inside, with a chevron. Settings' Trackpad row opens
the Menu with Trackpad and controls open and scrolled to.

### Why this order

- The quick tiles are the things Mike named as every session (Steady, Pocket mode, Game keys) plus the navigation that
  was in the first card. Eight rows of 60 dp became two rows of 76 dp tiles.
- Steady mode was only on the trackpad's bar (portrait) or a key (landscape); it is now also a tile for glasses that
  have it, as Mike listed it with the every-session things.
- This window comes next because it is about what you are looking at right now.
- The Space is open by default: tracking, Lock screens, Arrange and presets are used during a session, and the glasses
  check tells users to "open the Menu and pick Body Anchor".
- Everything set once (speeds, sides, curve, snap, new instances) moves into closed sections.
- Game controller sits with input in Trackpad and controls; Game keys, its every-session sibling, is a tile.

### Hidden instead of greyed

| Row | Rule |
|---|---|
| Pocket mode, Open Control Center | Only with the Space open (they only said "Open the Space first" without it) |
| Game keys, Game controller | Open phone, and the Space open or the mode already on (so it can be turned off) |
| Record | The Space open, or recording now |
| Unlock phone | Only while the phone is locked |
| Steady mode | Glasses that have it |
| Arrange, Adjust all | Windows open (was a greyed "Open a window first") |
| Save current windows | Windows open (was greyed); the hint line says to open some first |
| Space locked (6DoF) | Left out (no glasses have it; was greyed) |
| Glasses setup check, Air rows, cover and sideways rows, touch spots | Same rules as 1.2.5 |

### Labels

Unchanged, except the tiles' short forms where a row label does not fit a tile: "Record" / "Stop recording" (TalkBack
still reads "Record the glasses"), "Phone home" (reads "Go to the phone's home screen"). "Tracking" heads the two mode
buttons. Section titles are new.

### Rows and spacing

One row style (60 dp, 20 icon, 14 sp, chevron or tick), one tile style, one header style (14 sp Medium, 48 dp touch
target), 24 dp above each section, 8 dp from a heading to its card, hairlines between rows, every section's cards on
the Menu's page colour.

## 3. Ko-fi colour

The pill now uses the theme blue (`Look.ACCENT`, #5A93FF, the window glow and handles' blue):

| Part | Value |
|---|---|
| Fill | #5A93FF at 18 % (`0x2E5A93FF`), #1A253B over the page (#0C0D10) |
| Rim | #5A93FF at 40 % (`0x665A93FF`), 1 dp |
| "Buy me a beer" | `Look.INK` (white at 92 %), about 15 to 1 on the fill |
| "Ko-fi" and the beer | #5A93FF, about 5 to 1 on the fill (WCAG AA is 4.5) |

The same faint fill marks a lit tile and the chosen tracking mode, so the accent means "on" across the Menu.

## 4. Pictures

Drawn by Robolectric's native graphics (`app/.../MenuShotsTest.kt`) with a typical session: the Space open, three
windows, Chrome focused, glasses known.

- Before: `menu-before-session.png`, `menu-before-no-space.png`
- After: `menu-after-session.png` (sections as they start), `menu-after-all-open.png`, `menu-after-no-space.png`

The session Menu went from 9621 px to 4830 px tall (412 dp wide at xxhdpi), about half.
