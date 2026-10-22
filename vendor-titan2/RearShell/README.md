Русская версия: [README.ru.md](README.ru.md)

# RearShell: the Titan 2 rear screen shell

System app `org.titan2.rear` (module `RearShell`, priv-app in system_ext, platform signature)
for the 410x502 rear display (~200 dpi; the logical id changes when the display is re-added and
is looked up via DisplayManager by the physical port `ro.titan2.rear_display_port`, falling back
to a built-in non-default display with a 410x502 mode; virtual displays are never taken). It provides a lock screen with a clock and swipe-up
unlock, a home screen, an app list, a quick panel, its own navigation gestures (Android gesture
navigation does not work on secondary displays) and themes.

**The shell has no settings of its own.** Everything is in Titan2Parts: Settings → Titan 2 →
Rear screen (`RearScreenActivity`, action `org.titan2.parts.REAR_SETTINGS`). The shell reads
settings and themes from the Titan2Parts provider `org.titan2.parts.rear` and updates live
(ContentObserver). Panel power, display group/focus/power, the sleep timeout and waking are
handled by the system part (Titan2Parts `RearController`/`RearDisplay`, frameworks_base).

## Architecture

```
Titan2Parts (uid system, persistent)                RearShell (persistent, directBootAware)
 ├─ RearScreenActivity + fragments ──write──▶ rear_config (SharedPreferences, DE)
 │   Rear{Apps,Order,Themes,ThemeEditor}Fragment       │ notifyChange
 ├─ RearThemes: assets/rear_themes, files/rear_themes  ▼
 └─ RearConfigProvider  ◀──── query/openFile/call ──── Cfg (keys), ThemeStore (theme, fonts,
     (signature permission REAR_CONFIG, directBootAware)  images → cache), ContentObserver
                                                     RearHomeActivity, RearGestureService, …
SystemUI: Titan2RearUnlockService ◀── Messenger ── UnlockClient (PIN/pattern, unlocking)
```

* The shared library `titan2-rear-common` (`common/`, java_library, sdk current) is in both APKs:
  `RearKeys` (keys and defaults), `RearContract` (provider URIs, permission),
  `ThemeJson` (theme schema v2: defaults, inheritance, v1 conversion, validation),
  `ThemeSpec` (parsed theme), `Effective` (setting over theme), `Wallpapers`,
  `faces/*` (clock faces and `ClockRenderer`; the Titan2Parts preview draws with them too).
* `Settings.System` keys remain only for what the system part reads:
  `titan2_rear_enabled`, `titan2_rear_screen_off_timeout`, `titan2_rear_wake_double_tap`,
  `titan2_rear_wake_double_tap_pose`, `titan2_rear_wake_flip`, `titan2_rear_system_decors`
  (system bars on the rear display, below). The former
  `titan2_rear_lock_mode`/`titan2_rear_allowed_apps` are moved to the provider once
  (`RearConfig.migrate`).
* The shell writes to the provider only `favorites` (long press on an icon) and `clock_face`
  (the "next clock face" action); the provider refuses anything else (`SHELL_WRITABLE`).
* The old `org.titan2.rear.SETTINGS` action is kept: `SettingsRedirectActivity` opens the
  Titan2Parts settings on the main screen.

### System decorations on the rear display

By default the rear display has **no** system decorations: no status bar, shade, navigation
bar, back gesture handler, wallpaper or keyguard presentation (frameworks_base
`Titan2RearDecor`; SystemUI's `KeyguardPresentation` is never shown there). The shell does not
depend on any of them: it draws its own background (theme color/gradient/picture over a black
window) and its own lock screen (a `showWhenLocked` window), and brings its own gestures.
With `titan2_rear_system_decors` = 1 (Titan2Parts: "System controls") the system bars appear:
the home screen and "Recents" pad their content by the system bar insets, and the gesture
service stops using the left/right edges (SystemUI's back gesture owns them there; the bottom
swipe stays). The shell's home activity is exported (SECONDARY_HOME) but closes at once if it is
started on the main display or into another app's task.

## Shell files

| File | What it does |
|---|---|
| `RearHomeActivity` | SECONDARY_HOME, singleTop, showWhenLocked: clock → favorites pages; sheets on top (app list, quick panel, PIN entry), vertical gesture routing, lock |
| `Sheet` | sheet progress 0…1: follows the finger, on release a spring (androidx.dynamicanimation) with velocity, programmatically M3 curves |
| `Motion` | M3 curves (emphasized, emphasized decelerate/accelerate, standard), durations, springs; theme/settings speed multiplier, system animation scale |
| `PagerView`, `PageIndicator` | horizontal pages (M3 settling, neighboring pages fade/shrink), vertical gestures are passed continuously to the home screen |
| `ClockView` | clock via the shared `ClockRenderer`; face change is a cross-fade; tap/double/long press run actions from the settings |
| `BouncerView`, `UnlockView`, `UnlockClient` | unlocking from the rear screen (below) |
| `DrawerView`, `AppGridView`, `Icons`, `Apps` | app list and grids, icon shapes/style, launch with scale-up from the icon |
| `QuickPanel`, `Torch`, `BrightnessControl` | tiles from the settings, rear display brightness |
| `RearGestureService`, `RearActions` | navigation gestures over apps |
| `RearRecentsActivity` | "Recents" of the rear display |
| `RearNotificationListener` | notification count / titles for the lock mode |
| `Cfg`, `ThemeStore` | settings and theme from the Titan2Parts provider |

## Lock screen and unlocking

* The default page is the clock. On the lock screen only the clock, to the extent of `lock_mode`:
  `off` (black screen), `clock`, `clock_count` (default: clock, charge, notification count),
  `clock_titles` (plus titles; marked as less private; nothing if the system setting
  "notifications on the lock screen" is off; VISIBILITY_SECRET ones, also by the channel or
  user override, are skipped, for PRIVATE the publicVersion or the app name). No favorites, app list, "Recents",
  Wi-Fi/DND/sound/settings on the lock screen; the torch per the `lock_torch` setting.
* Swipe up from the clock (setting "Unlock from the rear screen", on by default): the
  "bouncer" slides up following the finger while the clock moves up, shrinks and fades;
  released past the middle or flung up, it opens, otherwise it springs back. Once open,
  `MSG_QUERY` to `Titan2RearUnlockService`:
  * `can_skip` (no PIN, trusted device, fingerprint already recognized) → `MSG_DISMISS`, no input;
  * `available` and PIN/pattern type → input (`UnlockView`, themed): input lockout countdown,
    shake on error, PIN auto-confirm only if it is enabled in the system;
  * otherwise (password, admin policy) → "unlock on the main screen" +
    `KeyguardManager.requestDismissKeyguard`.
  Closing: swipe down by the top part, "Back" (gesture), screen off (the main one, or the
  rear one alone), the activity leaving: the input is erased (the byte[] is wiped with zeros after sending and on close).
  With the setting off, a swipe up only shows a hint.
* After unlocking the input slides down and the pages fade in (the same progress); on a state
  change not through the input, the content appears smoothly (alpha/scale, emphasized decelerate).
* `UnlockClient` keeps the service bound for the whole lifetime of the process: while it is,
  SystemUI locks the phone again when the rear screen goes off with the main one off.

## Home screen and gestures

* When unlocked, a swipe up from the clock/favorites runs `home_swipe_up` (the app list by
  default, follows the finger), a swipe down runs `home_swipe_down` (the quick panel; anywhere
  or only from the top edge). Tap/double/long press on the clock have their own actions
  (by default a long press opens the settings on the main screen).
* Tapping an icon launches on the rear display (`setLaunchDisplayId`), the window "grows" out of
  the icon (`makeScaleUpAnimation`; the expressive style uses `makeClipRevealAnimation`). A long
  press adds to / removes from favorites (the order is changed by dragging in Titan2Parts).
* Navigation over apps: `InputManager.monitorGestureInput` on the rear display +
  `pilferPointers`. Swipe up from the bottom ("Home" by default), with a hold ("Recents"), from
  the left and right edges (separately, "Back" by default); any of them can be Home/Back/Recents/
  Torch/Screen off/Nothing, with sensitivity and haptics. With the system decorations on, the
  left/right edges are left to the system back gesture.

## Themes

The reference is `THEMES.md` (schema 2: color tokens and element colors, fonts, clock faces and
their parameters, background color/gradient/image with blur and dimming, grid and icons,
animations, `extends` inheritance). Built-in themes are in Titan2Parts (`assets/rear_themes`),
JSON/ZIP import/export and the editor with a palette and preview are there too.

## Permissions

Signature (via the platform signature): MONITOR_INPUT, INJECT_EVENTS, CONTROL_DISPLAY_BRIGHTNESS,
REMOVE_TASKS, NETWORK_SETTINGS, MANAGE_NOTIFICATIONS, DEVICE_POWER (turning the rear screen off),
CONTROL_KEYGUARD (binding to Titan2RearUnlockService), `org.titan2.permission.REAR_CONFIG`
(declared in Titan2Parts). signature|privileged via `privapp-permissions-org.titan2.rear.xml`:
REAL_GET_TASKS, START_ACTIVITIES_FROM_BACKGROUND, START_TASKS_FROM_RECENTS,
SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE.

## Tested / not tested

* **Tested:** `m RearShell Titan2Parts` builds (crDroid 16, Docker).
* **Not tested on the device; none of this has been run:**
  * unlocking: the whole chain with `Titan2RearUnlockService` (binding with CONTROL_KEYGUARD,
    replies, `can_skip`/`MSG_DISMISS`, PIN auto-confirm, lockout countdown), that the keyguard
    is then dismissed and the shell sees it via KeyguardManager/USER_PRESENT;
    `requestDismissKeyguard` from a rear display activity;
  * the provider: access via REAR_CONFIG (permission install order on the first boot),
    working before the first unlock (DE storage of both apps), live updates;
  * animations on the phone GPU (spring smoothness, RenderEffect blur, saveLayerAlpha on a
    face change), scale-up/clip-reveal when launching on a secondary display;
  * gestures: that `monitorGestureInput` receives sub_touch touches and pilfer takes the touch
    away from the app; BACK injection with a displayId; that `goToSleep(displayId)` turns off only the rear;
  * `DisplayManager.setBrightness(rearId)` (depends on the rear display having its own
    DisplayPowerController), Wi-Fi/DND/sound from a priv-app, granting notification access from Titan2Parts;
  * font/icon/PIN pad sizes at ~200 dpi; the theme editor and ZIP import with real files;
    the Titan2Parts preview versus how the shell draws.

## Limitations

* "Recents" shows only tasks on the rear display, without thumbnails; a long press closes a task.
* The "back" gesture is KEYCODE_BACK without predictive back.
* The quick panel exists only inside the home screen (not over other apps).
* There is no search in the app list on the rear screen itself (no keyboard there); search is in the settings.
* No Bluetooth tile (it needs the BLUETOOTH_CONNECT/PRIVILEGED runtime permissions).
