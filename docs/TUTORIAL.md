# The tour (1.5.0)

Mike, 15 November 2026: a tutorial new users see after installing, and can reopen whenever they want, "extremely well
designed", "above and beyond with visual design and motion design". This page is the concept and the motion plan. The
order of the steps and the first-run rules live in `core/.../tutorial/Tour.kt` and are unit tested; the screen is
`TourActivity` with `ui/tour/`. On an emulator, docs/EMULATOR_TESTING.md has the command that opens it at any step.

## What was there before

A card over the trackpad with four to six pages (Point and click, Scroll and zoom, Three fingers, Spatial Mouse, the
add-on, where Exit is), each a still sketch over three numbered lines. It taught the trackpad's gestures well, but it
said nothing about what Pocket XR is, Shizuku, plugging in, moving a window, Recentre, 3D or Steady mode, and it was
shown before the user had seen anything on the glasses. It stays as it is on the Flip's cover screen, which is too
small for the tour.

## What the tour teaches, in order

One idea per step, nine steps, about two minutes. Every step has Skip, Back and Next.

| # | Step | What it says | The picture |
|---|---|---|---|
| 1 | Welcome | Your apps float around you as windows; the phone is the controller. | Three glass windows in an arc, the view drifting as a head would. |
| 2 | Get set up | A live checklist: glasses, Shizuku, the add-on (optional). Each row says where it stands now and has the one button for the next thing to do. | A phone and glasses joined by a cable; light runs along the cable once the glasses are seen. |
| 3 | Point and click | Move one finger, tap, two fingers to scroll. | A practice pad: the user's own finger moves a dot in a small "glasses" view, a tap clicks a button there, two fingers scroll it. |
| 4 | Three fingers | Up: your apps. Down: Control Center. Sideways: Back. And what to use when the phone keeps those swipes. | Three finger dots swipe on a small pad; the launcher, the Control Center or Back answers above. |
| 5 | Move and resize | Click a window's Move pill, swipe, tap to drop. Resize from the blue corner handle: hold until the dot fills, then drag. The keys over the bar do the same. | The dot picks a window up, carries it, drops it, then fills on the handle and stretches the window. |
| 6 | Look around, and Recentre | Windows stay put as you turn your head; Recentre brings them back in front. | The view turns away, the windows slide off; a press on Recentre glides them back. |
| 7 | 3D and Steady mode | Depth gives each eye its own view. Steady mode lines the windows up and holds the picture still. Says which glasses have each. | Two eye views merge into layers at depth; then the windows line up in a row and the rim locks. |
| 8 | More ways to control | Spatial Mouse, hand tracking, PC VR, each with what it needs. | The phone tilts like a remote and a ray moves the dot. |
| 9 | You're set | What happens next (plug in, the glasses setup check), what is still missing if anything, where the tour and Exit are. | The windows settle; a tick draws. |

## Where it lives

On the phone, full screen, in Compose with the "Light on glass" tokens (`Look`). Not in the glasses: a new user holds
the phone while setting up and sees it through the lenses, the tour must work before any glasses are plugged in, and a
tour in the Space could only be checked on real glasses, which a contributor without an Aura cannot do. The glasses
keep what they have (the setup check, the notices).

## How it opens

- **New install:** the first time the trackpad opens on the main screen, the tour opens over it. Skipping it counts as
  seen. A trackpad that opened by itself (glasses plugged in with the phone closed) or on the cover screen waits for the
  next ordinary open.
- **Someone updating from an older version:** never by itself. Once, a card on the trackpad says there is a new tour,
  with "Take the tour" and "Not now". Either answer ends it for good.
- **Any time:** the trackpad's Menu, under Help and more, "Tour of Pocket XR"; and the first row of the settings list
  in Pocket XR Settings. The Menu keeps "How to use the trackpad" (the gesture cards) beside it.

## When something is missing

- **No glasses plugged in:** the checklist says so and that the tour works without them; the last step says to plug
  them in. Nothing waits for them.
- **No Shizuku, not running, or not allowed:** the row says which, what Pocket XR does without it (apps open as
  ordinary desktop windows), and has the one button: get it, open it, or allow Pocket XR. The last step repeats what is
  still open, with a button back to the checklist.
- **Glasses Pocket XR supports only partly:** the glasses row and steps 7 and 8 say what is known for that model, in
  the README's own tiers: tested by the project, confirmed by community testers, early, or not on the list. Hand
  tracking is only offered for glasses with a camera, and the Aura's firmware 16.1.04 note is said.
- **TalkBack:** every picture has a spoken description, the titles are headings, the checklist's states are read as
  they change, and the practice pad is described rather than required.
- **Animations turned off** (Android's "Remove animations"): each picture shows its finished state as a still, and
  steps change with a plain fade.

## Motion plan

Pocket XR already has one set of curves (`Look`): enter 280 ms on (0.2, 0, 0, 1), exit 160 ms on (0.3, 0, 1, 1), hover
120 ms, and "everything that appears fades and rises about 2 %; nothing pops". The tour keeps to them.

- **Feeling:** calm and exact. Glass does not bounce: no overshoot anywhere.
- **Signature easing:** `Look.EASE_ENTER` for everything that arrives or moves on screen; `Look.EASE_EXIT` for what
  leaves; a sine for the slow ambient drift. Nothing that moves in space is linear.
- **Three durations:** 120 ms (press, hover, a chip lighting), 280 ms (a step's words arriving, a tick drawing), 600 ms
  (a beat inside a picture: a window carried, the view recentring).
- **A step arriving:** the picture first, then the title 60 ms later, the lines 120 ms, the buttons 180 ms; each fades
  in and rises 12 dp. The leaving step fades and slides 16 dp the way you are going in 160 ms. Total under 500 ms.
- **Inside a picture, three layers:** the action the eye follows (the dot, a finger, the window being moved); what
  answers it (a rim brightening to the held colour, the focus halo, a tap's ripple); and slow life behind (the other
  windows drifting a degree or two, the halo breathing). Never more than one thing in the first layer at a time.
- **Loops** run 5 to 8 seconds with a rest at the end, so the picture reads as a sentence, not a spinner.
- **Meaning, not decoration:** every motion shows the thing the words say. The cable lights only when the glasses are
  really seen. A tick draws only when that row is really done. The practice pad's dot moves with the user's finger; until the first touch, a ghost finger
  shows what the pad is for, then goes away for good.
- **Touch:** the 10 ms tick the trackpad uses, on Next, Back and each practice goal reached.
