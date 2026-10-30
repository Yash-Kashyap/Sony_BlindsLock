# Blinds Lock

Brings back the Sony Xperia Z / Z1 "venetian blinds" unlock to Android 9 – 15+.
No root, no accessibility service, no internet permission.

## What's new in 1.7

- Sound volume slider for the lock/unlock clicks (plays a preview when you let go).

## 1.6

- Lock and unlock sounds: a click when the screen turns off, and another when the phone unlocks
  (after the PIN when you use one). They only play when the ringer is on, and can be turned off
  in settings.

## 1.5

- **Lock screen widgets**, Xperia-style: swipe left/right on the clock to flip between pages
  (clock, your widgets, "+"). Any home-screen widget works: music, weather, calendar, notes…
  Add, remove and reorder them in settings, or tap "+" on the lock screen. Widgets turn with the
  blinds: the visible page is stamped into the slats while they move. A vertical swipe that starts
  on a widget still unlocks.

## 1.4

- The navigation bar (Back / Home / Recents) is hidden while the blinds lock screen is showing
  (sticky immersive mode), so tapping Home can't skip the blinds. It only comes back after a
  deliberate swipe in from the bottom edge.

## 1.3 (Android 9 fixes)

- Android 9: blinds now render. Android 9's hardware renderer ignores `Canvas.drawVertices()`, so
  Android 9 uses a bitmap-slice renderer. Android 10+ keeps the smooth mesh.
- PIN / pattern / password: the blinds open and fade, then the app hands over to Android's own
  unlock screen (PIN pad, fingerprint, face). On some Android 9 phones the PIN pad used to open
  hidden behind the blinds, and touches were ignored.
- Swipe / None / trusted unlock waits for the system lock screen to leave before fading, so it no
  longer flashes through on Android 9.
- More robust haptics start-up on unusual firmware.
- Compatibility tests boot the app on Android 9, 11 and 14.

## 1.2

- The CRT TV effect has been removed, along with the screen-off helper (accessibility) it needed.
- New setting **Blinds that move with your finger** (default 6). Only that many slats turn while
  you swipe. Slats the finger leaves behind settle immediately. Unlocking opens just those slats
  and fades the lock screen out. There is no longer a full-screen wave on fast swipes.
- Smart Lock / Extend Unlock trusted states are detected, so swiping goes straight to the home screen.
- "Swipe up or down to unlock" hint with up/down arrows, as on the Xperia Z1. Unlock is vertical-only.
- New "PIN / pattern" card explaining how to avoid typing your PIN on most unlocks.

## 1.1

- Blinds react to where you touch (they twist toward the finger and flap more on faster swipes).
- Haptics: per-slat ticks, a click at the unlock point, a rattle on unlock.
- "Use current wallpaper".
- Grant buttons grey out once granted.

## How it works

Android doesn't let a normal app replace the system lock screen, so Blinds Lock does what
lock-screen apps have always done:

1. A small foreground service listens for **screen off**.
2. At that moment it opens `LockActivity` with `showWhenLocked`, so it sits **on top of** the
   system keyguard and is already there when you press power.
3. The activity draws your wallpaper + clock, cuts it into horizontal slats, and runs each slat
   as a damped spring that turns around its horizontal axis near your finger.
4. When you swipe far enough, it asks Android to dismiss the real keyguard
   (`KeyguardManager.requestDismissKeyguard`). With Swipe/None, or after fingerprint/face unlock,
   the blinds open straight onto your home screen. With a PIN, pattern or password, the system
   asks for it first and the blinds open afterwards. **Your phone stays exactly as secure as before.**

## Install & set up

1. Copy `BlindsLock-v1.0.apk` to the phone and open it. Allow "Install unknown apps" for your
   file manager or browser if Android asks.
   - If Play Protect says it doesn't recognise the app, that's normal for any app you build
     yourself. Tap **More details → Install anyway**, or let it scan the app.
2. Open **Blinds Lock** and turn on **Enable blinds lock screen**.
3. Grant **Display over other apps** (needed on Android 10+).
   - **Android 15+:** if the toggle is greyed out ("Restricted setting"), go to
     *Settings → Apps → Blinds Lock → ⋮ (top right) → Allow restricted settings*, then grant it.
4. Set battery to **Unrestricted** (so the system doesn't kill the app).
5. **Xiaomi / Redmi / POCO (MIUI/HyperOS), Oppo, Realme, Vivo:** in App info → *Other permissions*,
   allow **Show on lock screen** and **Open new windows while running in background**. Also turn on
   **Autostart** if your phone has it.
6. Optional: **Choose image** to use your own wallpaper. Tap **Try the effect** to preview.

For the most authentic feel, set the system screen lock to **Swipe** or **None**, or use
fingerprint/face unlock.

## PIN / pattern

No app can skip the system PIN; that is what keeps the phone secure. To make the blinds open
straight to the home screen most of the time, use one of these:
- **Fingerprint while the screen is off** (Samsung: "Fingerprint always on"). The phone wakes
  already unlocked.
- **Face recognition**: after the swipe, the system checks your face and continues without the PIN.
- **Smart Lock / Extend Unlock** (trusted places or devices). Blinds Lock detects the trusted state.
- **Lock automatically after X minutes** (Samsung: Lock screen → Secure lock settings).

## Limitations (Android-imposed)

- The system lock screen (and its notifications) is still underneath. Blinds Lock covers it but
  can't replace it.
- Unlocking right after a reboot uses the normal system lock screen until the service starts.
- On Swipe/None locks, the Home button can skip the blinds screen, just as with any third-party
  lock screen. Security is still handled by the system lock.
- On some heavily customised phones, the OEM may block or delay showing the screen when it turns
  off. Use the OEM permissions in step 5.

## Build from source

Requirements: JDK 17+ and Android SDK 35.

```
./gradlew assembleRelease            # -> app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest        # renders the effect off-device to build/frames/*.png
```

`keystore/` contains the signing key for this app. Keep it private and keep a backup.
Updates must be signed with the same key, or Android will refuse to install them over the
existing app.

## Tuning the effect

Everything lives in `BlindsView.java`:

| Constant | Meaning |
|---|---|
| `SPRING_K`, `SPRING_C` | slat stiffness and damping (wobble) |
| `thetaHold` in `step()` | how far the slats turn (42° on touch → 82° at full swipe / fast) |
| `bandHalfWidth()` | the window of slats that follows the finger (set in the app) |
| `REVEAL_FLIP_MS`, `FACE_FADE_MS` | timing of the unlock |

You can also change slat height and unlock distance in the app's settings.
