Русская версия: [README.ru.md](README.ru.md)

# plektra-linux: the Titan 2 keyboard in Linux (Lindroid) like in Plektra

A service for the Lindroid Linux container (Debian trixie, KDE Plasma 6, kwin_wayland). With it
the Unihertz Titan 2 hardware keyboard behaves the same as with the
[Plektra](https://github.com/pkb-rocks/plektra) IME on Android: Sym pages, the Titan Alt layer,
Fn as Ctrl with a navigation mode, one-shot and locked modifiers, long press and double space
all work.

## License and origin

GPL-3.0-or-later, the full text is in `COPYING`. This is a port of the Plektra logic
(GPL-3.0, © the pkb-rocks/plektra contributors, a Pastiera fork). The version taken is the one pinned in
`scripts/fetch-plektra.sh`: tag `nightly/v26.10-nightly.11.184948Z`, commit `d029832c5260`.
Source for comparison: `downloads/plektra-src/plektra`.

What was ported from where:

| Plektra file | What was taken |
|---|---|
| `DeviceSpecific.kt` | Titan 2 scan codes: 251 = Ctrl (Fn), 253 = Sym |
| `ModifierKeyHandler.kt`, `core/ModifierStateController.kt` | the Shift state machine (off → one-shot → caps), Ctrl/Alt (one-shot, lock by double press, hold, an "intentional hold" without keys changes nothing) |
| `NavModeHandler.kt`, `core/NavModeController.kt` | navigation mode (the Ctrl layout, Enter = DPAD_CENTER) |
| `core/SymLayoutController.kt` | the SYM page cycle, the SYM+key chord, auto-close, "Back" closes the page |
| `AlternateCharacterManager.kt` | long press (300 ms): the character is replaced with the Alt character |
| `InputEventRouter.kt` | key handling order, Ctrl actions, Alt+Space, Ctrl+Space, Alt reset by space |
| `core/TextInputController.kt`, `AutoCapitalizeHelper.kt` | double space → ". " and a capital after the end of a sentence |
| `SettingsManager.kt`, `SymPagesConfig.kt` | defaults |

The tables in `mappings/` are copied from the Plektra assets unchanged:
`devices/titan2/device_sym_key_mappings.json` (the Alt layer and the Titan page),
`common/sym/sym_key_mappings.json` (emoji), `common/sym/sym_key_mappings_page2.json`
(symbols), `common/ctrl/ctrl_key_mappings.json` (Ctrl and navigation).

## How keys get into Linux (analysis)

1. Android → LindroidUI. The `DisplayActivity` window forwards `event.getScanCode()` to the container,
   i.e. raw kernel scan codes, not Android keycodes
   (`nativeKeyEvent(mDisplayID, event.getScanCode(), ...)`). The codes land in the uinput device
   `Lindroid-Keyboard-<display>`, which declares keybits 0..254. Hence:
   * Sym (253) and Fn (251) arrive, but in xkb these are keycodes 261/259, and xkb only works
     up to 255. Without the service both keys are dead in Linux.
   * Recents (580 / APP_SWITCH) never reaches the window: PhoneWindowManager takes it first
     (Android "Recents"). Also, this code is not declared in the Lindroid uinput device.
   * The side keys 249/250 (MACRO_1/2) are consumed by the `Titan2Parts` KeyHandler in
     system_server. These are its configurable actions.
   * Back (158) arrives. But LindroidUI handles it itself as Android "back" and shows
     the "stop the container?" dialog. The `vendor_lindroid/0007` patch fixes this.
2. Plektra on Android **takes part**. The `DisplayActivity` window is an IME target by default
   (`ImeFocusController`), so Plektra sees every key first. The window has no text field
   of its own, so Plektra takes the "no input field" branch
   (`handleKeyDownWithNoEditableField`):
   * the first Ctrl (= Fn) press is consumed by `NavModeHandler` ("waiting for the second press");
   * a double press enables Android navigation, and letters turn into DPAD events without a
     scan code, i.e. they are lost in Linux;
   * Sym enables "power shortcuts", and the next letter may launch an Android app.

   This is visible in `out/lindroid-kbd-events.txt`: letters and Back are recorded twice, Fn (0x00fb)
   once. On a single evdev node the kernel does not pass two DOWNs in a row. So the recording is merged
   from two devices: the hardware TitanKey and Lindroid-Keyboard-0, and Fn did not reach Lindroid
   because Plektra ate it. The `vendor_lindroid/0007` patch sets
   `FLAG_ALT_FOCUSABLE_IM` on the window, and the IME no longer gets the Linux window keys. Just in case,
   the service still drops repeated DOWN/UP of the same key.
3. Android does not read Lindroid devices: `/system/usr/idc/Vendor_000a_Product_000a.idc`
   (`device.disabled = 1`, from vendor/lindroid). The service output device has the same
   vendor/product 0x000a/0x000a. So the output does not return to Android and does not loop back
   into the container.
4. In the container libinput sees only `Lindroid*` devices
   (`/usr/lib/udev/rules.d/99-lindroid-input-ignore.rules` from the rootfs). The output device
   is called `Lindroid-Plektra-Keyboard`.

## Design

The chosen option is "an evdev daemon → uinput + a small XKB extension".

* `plektra-linux.py` is a Python 3 service, standard library only. The rootfs has
  `python3`, no compiler. The service:
  * finds all `Lindroid-Keyboard-N`, grabs them exclusively (`EVIOCGRAB`) and
    picks up their re-creation when a display starts again;
  * implements the Plektra state machine;
  * types through its own uinput device `Lindroid-Plektra-Keyboard`.

  If the service crashes, the grab is released and kwin gets raw keys again. Touches on
  other `Lindroid-*` devices reset the double space context: the cursor may have moved.
* Characters outside the layout (digits, symbols, `° ± – „ “ ” « »`, emoji) are typed via keycodes
  that do not exist on the phone keyboard (F13–F24 and 200–246, without the power, brightness, radio
  and camera codes). The `xkb/symbols/titan2` file assigns Unicode keysyms to them on two levels.
  `xkb/rules/evdev` includes it unconditionally over any layout and options
  (`! include %S/evdev` + `* = +titan2(plektra)`). libxkbcommon looks for rules in
  `/etc/xkb` first, and the slot keys have a single group. So the characters are typed the same with
  the us and ru layouts. Regular letters go as regular keys and follow the KDE layout (ru
  gives Cyrillic). The codes are at most 247, so the slots work in X11 apps too
  (XWayland). kwin builds the keymap at startup, so the service sends the session the
  `org.kde.keyboard /Layouts reloadConfig` signal (the KDE layout settings send the same one). On it
  kwin rereads the rules, and the slots work without logging in again. The signal is sent when the
  service starts, on every new session and after the slots are regenerated.
* **The Plektra bar** (`plektra-linux-im.py`) is a kwin input method that replaces maliit
  (`kwinrc [Wayland] InputMethod=/usr/share/applications/org.plektra.linux.im.desktop`).
  kwin starts it itself and activates it (`zwp_input_method_v1.activate`) only when
  a text field with text-input (Qt, GTK, Firefox) is focused. So the bar, as in Plektra,
  is visible only while typing and hides when leaving the field. What it shows follows
  the Plektra `LedStatusView` and `StatusBarController`:
  * **A modifier LED bar** at the bottom, segments in the Plektra places:
    Shift | Sym | … | Ctrl (Fn) | Alt. Dark is off, blue is one-shot or
    held, orange is locked (CAPS, navigation mode, locked Alt;
    for Sym, the "Symbols" page).
  * **The SYM page grid** while a page is open: the 3 Titan 2 rows (Q–P, A–L, Z X C V · B
    N M) on a black background, each key shows the page character and the letter in the corner, as in Plektra.
  * The service characters (Alt layer, SYM pages, long press) go into the field via
    `commit_string`: independent of the layout and the xkb slots. The slots remain a fallback
    outside fields and in XWayland.

  The bar is drawn in pure Python: a TrueType rasterizer (DejaVu Sans) and color emoji from
  NotoColorEmoji (CBDT/PNG) in `plektra_render.py`. The Wayland protocol is implemented in
  Python too, no packages are needed. The link to the service is `/run/plektra-linux/ui.sock` (session
  owner only). As on Android, the bar sits at the bottom of the screen across the full width, windows
  move above it. For that install.sh writes `KWIN_IM_SHOW_ALWAYS=1` into the session environment
  (`/etc/environment.d/92-plektra-linux-im.conf`) and the login screen environment:
  without it kwin shows the bottom panel only after the field is touched. The variant under the text
  cursor (overlay) is `PLEKTRA_IM_PANEL=cursor` in the same place. To get maliit back: "System Settings → Keyboard → Virtual
  Keyboard".
* If the bar is not running (e.g. maliit is selected), indication works as before: the SYM page is
  a Plasma notification, locks are an OSD. The current state is always in
  `/run/plektra-linux/state.json`.

Why not other options:

* An fcitx5 or IBus module. They are not in the rootfs. They see keys only with an active input
  field, while the Plektra navigation mode works everywhere. In KWin input-method v1 takes the place
  of the on-screen keyboard. The module would have to be built in C++.
* XKB only. XKB has no timers (long press, double press, "hold without
  keys") and no page state. Keys 251 and 253 are not available in xkb at all (code > 255).

## Behavior and layout

### Shift
| Action | Result |
|---|---|
| press | the next single letter is uppercase |
| double press (< 500 ms) | CAPS (OSD); another press turns it off |
| hold + letters | uppercase; with CAPS the hold gives lowercase |
| hold > 300 ms without other keys | the state does not change |

The physical Shift is also passed on as a real Shift: Shift+click, selection,
shortcuts.

### Alt (the Titan right Alt): the Titan layer
`W1 E2 R3 S4 D5 F6 X7 C8 V9 Q0 · A@ B. G* H# I_ J+ K" L' M? N, O/ P: T( U- Y) Z!`

* a press applies to one key;
* a double press locks Alt (OSD), the next press releases it;
* hold + key;
* Space or Enter clear the one-shot and locked Alt, the space itself is typed;
* Alt+Space is just a space;
* a real Alt does not reach the apps, as in Plektra.

**Long press of a letter (300 ms)**: the letter is typed first, then it is replaced with the
Alt character (BackSpace + character). Letters have no autorepeat, as in Plektra.

### Sym: symbol pages
* Pressing Sym cycles through the pages: **Titan** (Alt layer) → **Emoji** → **Symbols**
  → off. A notification shows the open page.
* On an open page a key types its character, and the page closes
  (auto-close). "Back" closes the page. Enter closes it and passes through. Alt
  closes the page too.
* Holding Sym + a key types the character from the open page, or, if it is closed, from
  the first one (Titan). The page does not switch in this case.

Emoji: `Q😀 W😂 E😍 R😊 T😎 Y👍 U❤️ I😘 O😡 P😉 · A😢 S😭 D😱 F😰 G😴 H🤔 J🤢 K🙄 L😌 · Z😔 X🥳 C😅 V🤗 B🥰 N👎 M😳`

Symbols: ``Q~ W` E{ R} T[ Y] U< I> O° P% · A= S; D± F– G\ H| J„ K“ L” · Z» X« C& V^ B¡ N§ M$``

### Fn (251): the Plektra Ctrl and navigation mode
* **Hold Fn + key** is a real Ctrl+key: Ctrl+T, Ctrl+W, Ctrl+S in
  apps. This is the Plektra rule: "with a physically held Ctrl, native shortcuts".
  Fn+Alt (both held) + key give Ctrl+Alt+key (an addition for the
  desktop).
* **Pressing Fn**, as in Plektra, depends on whether the cursor is in a field:
  * in a text field (kwin activated the bar) the next key is taken from the Plektra
    Ctrl layout (one-shot);
  * outside a field the next key gets a real Ctrl.

  The `fn_tap` setting: `auto` / `map` / `ctrl`.
* **Double press Fn**: navigation mode (OSD), the Ctrl layout keys work until
  the next Fn press. Enter in this mode is Enter.

The Ctrl layout (`ctrl_key_mappings.json`):

| Key | Action | | Key | Action |
|---|---|---|---|---|
| E / S / D / F | ↑ ← ↓ → | | J / K / L | ← ↓ → |
| W / R | select a character left/right (Shift+←/→) | | U / I | select a word (Ctrl+Shift+←/→) |
| N / M | word left/right (Ctrl+←/→) | | Y / H | PgUp / PgDn |
| Q | Esc | | T | Tab |
| O | Enter (DPAD_CENTER) | | A | select all (Ctrl+A) |
| Z / X / C / V | undo / cut / copy / paste (Ctrl+Z/X/C/V) | | Del | delete a word (Ctrl+BackSpace) |
| G, P, B | nothing (in Plektra: no action, minimal UI, on-screen keyboard mode) | | | |

Holding Shift meanwhile makes the arrows and word jumps select text. Fn+Space (Fn
pressed before the space or held) switches the KDE layout (Meta+Alt+K), Alt and Fn
are reset. Note: a held Ctrl+Space is intercepted by Android itself.

### Other keys
| Key | In Linux |
|---|---|
| Back (158) | closes the SYM page, otherwise Esc (`back_key`, can be KEY_BACK = XF86Back) |
| Del, Enter | as is, with kwin autorepeat |
| Space | a space on release; **holding ≥ 0.5 s switches the KDE layout** (`long_space_ms`, 0 is off; space has no autorepeat) |
| Double space after a letter/digit | ". " and the next letter uppercase (`double_space_period`, `autocap_after_period`) |
| Recents (580) | stays with Android (leaving Lindroid); if it arrives, Meta+W (`recents_key`) |
| Side 249/250 | taken by Titan2Parts; if they arrive, KEY_PROG1/2 (XF86Launch1/2 for your own KDE shortcuts) |

### Word suggestions (no autocorrect)
In a text field the bar shows three completions of the current word from the Plektra dictionaries
(`dict/en.tsv`, `dict/ru.tsv`, 50,000 words from the Plektra `dict_backup`, by frequency). The word
is taken from the surrounding text the app (Qt, GTK) reports to kwin. The language is detected
from the letters. To pick a suggestion:
* Fn (held) + W / E / R, which are the digits 1/2/3 of the Alt layer;
* a swipe up on the keyboard touchpad picks the first suggestion.

The word is replaced entirely, and a space is added. While suggestions are on screen, Fn+W/E/R do not
send Ctrl+W/E/R.

### App profiles (terminal etc.)
A KWin script (`kwin-focus.js`, loaded by the bar) reports which window is focused, and the service
applies a profile from `profiles` in config.json: `{"name", "apps": [resourceClass…],
"overrides": {…}}`. `overrides` takes any setting keys plus:
* `fn_tap`;
* `sym_tap`: a key instead of the SYM pages;
* `alt_space`: a key instead of Alt+Space;
* `keys`: a full replacement of physical keys: `{"FN": "KEY_TAB", "SYM": "KEY_ESC"}`, names
  `SYM FN ALT SHIFT BACK SPACE DEL ENTER SIDE1 SIDE2` or letters.

The built-in **terminal** profile (Konsole, Yakuake, xterm, foot, kitty, Alacritty, GNOME
Terminal/Console, Ghostty, WezTerm etc.):
* Fn, then C is a real Ctrl+C (SIGINT), and so for any key;
* Sym is Esc;
* Sym+key takes the "Symbols" page first: Q `~`, W `` ` ``, G `\`, H `|`, U `<`, I `>`,
  T/Y `[ ]`, E/R `{ }`;
* Alt+Space is Tab;
* double space and auto-capitalization are off.

A profile example for a game: `{"name": "game", "apps": ["supertuxkart"], "overrides": {"keys":
{"FN": "KEY_TAB"}}}`.

### Macros
`"macros": {"SYM+D": {"text": "{date:%d.%m.%Y}"}, "SYM+M": {"text": "me@example.com"},
"FN+SYM+T": {"run": "konsole"}, "SYM+P": {"keys": ["KEY_LEFTMETA", "KEY_PRINT"]}}`:
* Sym+letter or Fn+Sym+letter, instead of a character, inserts text (`{date}`, `{time}`,
  `{datetime}`, a strftime format after a colon), presses a shortcut (`keys`) or runs a
  program as the session user (`run`);
* text in a field goes through the bar (any characters); outside a field Latin text goes as regular
  keys.

### Keyboard touchpad
The keyboard touchpad (`plektra_touchpad.py`) follows the same rules as the Titan2Parts touchpad on
Android, with output to the uinput mouse `Lindroid-Plektra-Mouse`. Everything is decided when the
finger touches the pad and holds for the whole gesture:
* **Outside a text field** (`"mode": "scroll"`): a swipe scrolls with the hi-res wheel
  (`REL_WHEEL_HI_RES`/`REL_HWHEEL_HI_RES`, plus `REL_WHEEL`/`REL_HWHEEL` per full detent), along
  one axis at a time (vertically the content follows the finger). The first ~22 pad units are a dead zone
  (skipped when a new swipe follows a scroll within half a second or catches a running fling).
  A fast swipe keeps scrolling with inertia that slows down on its own (`"inertia": false` turns
  it off); the next touch stops it. A key press other than Shift stops the wheel and the inertia
  until the next touch, so typing never scrolls the page. For 0.8 s after a finger leaves the
  screen the touchpad neither scrolls nor clicks.
* **In a text field** the touchpad never scrolls. Two quick taps in the same place (under 300 ms)
  toggle text cursor mode; in it a horizontal stroke moves the cursor with Left/Right (one arrow per
  ~30 units), a vertical one with Up/Down (per ~100 units). Any key press leaves the mode.
  Without cursor mode a swipe up picks the first suggestion of the bar.
* **Mouse zone** (`"mode": "mouse_left"` or `mouse_right`): a touch that starts in the left or
  right 2/5 of the pad moves the pointer (`mouse_speed`); a touch elsewhere scrolls as above, and a
  tap there (less than 50 units of movement, no key pressed meanwhile) is a left click.
* Other settings: `reverse` (wheel direction), `scroll_speed`, `enabled`.

The behavior is covered by `tests/test_touchpad.py` (synthetic evdev frames, no device needed).

The `touchPad` device is shared with Android: the service does not grab it but reads it in parallel and
is active only while Lindroid is focused on Android. That is the `sys.titan2.focus` property,
the service reads it straight from `/dev/__properties__` in the container. At startup the service sets
`sys.titan2.lindroid.touchpad=1`: with it Titan2Parts must stay silent in the Lindroid window (see
"What is needed on the Android side").

### Android ↔ Linux clipboard
Text is exchanged through the shared directory `/lindroid/clipboard` (on Android,
`/data/lindroid/mnt/clipboard`):
* when switching to Lindroid (the window gains focus) LindroidUI (patch `vendor_lindroid/0008`)
  puts the Android clipboard into `android.txt`, the service hands it to Klipper through the bar, and in
  Linux it pastes with Fn+V;
* a copy in Linux (the Klipper signal) is written to `linux.txt`, and LindroidUI makes it
  the Android clipboard when leaving Lindroid.

There is no other way: only the focused app may read the Android clipboard.

### Keyboard backlight as an indicator
The service sets the Android property `sys.titan2.lindroid.kbd` = `caps` | `nav` | `alt` |
`sym` | `off` (CAPS, navigation mode, locked Alt, an open SYM page). The property
is written through the property_service socket, which is passed into the container. The backlight is blinked by
Titan2Parts (KeyboardLight), if it supports this property.

### Not in the port
* autocorrect (by decision), auto-capitalization at the start of a field;
* quick launch of Android apps;
* the Plektra on-screen keyboard.

The "copy/paste" Ctrl actions are Ctrl+C/V. In Konsole copying is
Ctrl+Shift+C, a terminal peculiarity.

## Configuration

### The "Plektra (Titan 2 keyboard)" app
It is in the menu and in "System Settings → Keyboard". The screens and wording follow
the Plektra settings:
* "Keyboards & Devices";
* "Modifiers";
* "Input Languages";
* "Smart Features";
* "Suggestions";
* "Keyboard bar";
* "Nav Mode";
* "SYM key shortcuts";
* "Advanced";
* "Accessibility".

Texts are EN and RU by the system locale; where Plektra has no Russian string, a translation is given.
Linux items are marked "(Linux)":
* app profiles;
* touchpad: modes, sensitivity, inertia, mouse zone;
* macros;
* long space;
* bar position;
* clipboard;
* backlight indicator.

There are editors for:
* SYM layers: a Titan 2 grid, key → character;
* SYM page order and inclusion;
* navigation mode assignments, like the Plektra "Configure %1$s" dialog;
* profiles;
* macros.

Technically it is the `org.plektra.settings` plasmoid in a `plasmawindowed` window. The Plasma 6 rootfs
has no separate Qt6 QML launcher and no compiler for a KCM. The UI is Kirigami and
Kirigami Addons FormCard, looking like KDE apps. Reading and writing go through
`plektra_config.py` (the Plasma5Support `executable` engine), no packages are needed.

### Files and applying without logging in again
The settings are assembled from three layers:
* defaults (as in Plektra);
* `/etc/plektra-linux/config.json` (system);
* `~/.config/plektra-linux/config.json` of the Plasma session owner, written by the app.

Custom tables live in `~/.config/plektra-linux/mappings/`, then are looked up in
`/etc/plektra-linux/mappings/`, in the Plektra format.

The service (root) checks these files once a second and applies changes at once: timings,
modifiers, SYM pages, profiles, macros, touchpad. It regenerates the xkb slots when needed,
and kwin rereads the layout. The service passes the bar its settings:
suggestions, LEDs, position. Switching "bottom/under the cursor" recreates the panel surface at once.

These need a restart (the service restarts itself via systemd `Restart=always`):
* enabling or disabling the touchpad;
* clipboard sync.

Only the `KWIN_IM_SHOW_ALWAYS=1` variable (set by the installer) needs logging in to Plasma again:
without it kwin shows the bottom panel only after the field is touched. The app
notes this.

Manually: `plektra_config.py get | set BASE64 | sethex HEX | reset [key] | getmap NAME |
setmap NAME BASE64 | resetmap NAME` as your user. An example with all keys is
`config.example.json`.

## Installation

* **Firmware**: `vendor-titan2/titan2.mk` puts the files into
  `/system_ext/usr/share/titan2/plektra-linux/`. The lxc template (`prepare_rootfs_gsi`,
  patch `vendor_lindroid/0006`) runs `install.sh` from there into the rootfs when creating a container
  and passes `/dev/uinput` through. The service creates `/dev/uinput` itself if the node is missing.
* **An existing container from a PC**: `scripts/install-plektra-linux.sh` (adb + root + nsenter).
  Until the firmware has the `vendor_lindroid/0007` patch, Plektra on Android keeps
  intercepting the Lindroid window keys. Workaround: turn off "Nav mode" and
  "Power shortcuts" in Plektra, or pick another Android keyboard while working in Lindroid.
* **An existing container from inside** (the firmware already has the files):
  `sudo sh /system_ext/usr/share/titan2/plektra-linux/install.sh /system_ext/usr/share/titan2/plektra-linux /`

What gets installed:
* `/usr/local/lib/plektra-linux/`: the service, the bar (`plektra-linux-im.py`,
  `plektra_render.py`), tables, the settings example, README, COPYING;
* `/etc/xkb/symbols/titan2`, `/etc/xkb/rules/evdev`;
* `/etc/systemd/system/plektra-linux.service` (enabled in multi-user.target);
* `/usr/share/applications/org.plektra.linux.im.desktop`, `/etc/xdg/kwinrc`
  (`[Wayland] InputMethod`, only if it is not there yet), for users with maliit in
  `~/.config/kwinrc` it is replaced with Plektra;
* `/usr/local/bin/plektra-linux-im` and `/etc/sddm.conf.d/91-plektra-linux.conf`: the sddm login
  screen also gets the bar instead of maliit (the same kwin command as in
  `plasma-wayland.conf`, but with `--inputmethod /usr/local/bin/plektra-linux-im`);
* the settings app: `/usr/share/plasma/plasmoids/org.plektra.settings/`, the shortcut
  `/usr/share/applications/org.plektra.settings.desktop` and the "System Settings" entry
  `/usr/share/plasma/systemsettings/externalmodules/org.plektra.settings.desktop`;
* the modules `plektra_config.py` (settings), `plektra_dbus.py` (pure-Python D-Bus), `plektra_android.py` (Android
  properties), `plektra_touchpad.py`, the `dict/` dictionaries, `kwin-focus.js`.

Inside a running container `install.sh` restarts the service. In open sessions it
switches the input method via `kwriteconfig6 --notify`: kwin starts the bar at once
instead of maliit, no re-login needed. No apt packages are needed.

## Testing

* From a PC: `scripts/check-plektra-linux.sh`: slot regeneration, logic tests
  (`tests/test_engine.py`, `tests/test_touchpad.py`, `tests/test_config.py`), building the us,ru
  keymap with the rule. The settings app was tested the same way, in a nested kwin:
  `plasmawindowed org.plektra.settings` with `XDG_DATA_DIRS` pointing to a copy of the plasmoid.
  The bar can be tested without a phone in a nested kwin:
  `kwin_wayland --virtual --inputmethod ".../plektra-linux-im.py"` (this is how
  commit_string, suggestions and the KWin script were tested).
* Key presses without fingers: `tests/inject.py "W:450 ALT E SYM SYM Q +FN S -FN"` as root inside the
  container. Through `/run/plektra-linux/inject.sock` (0600) keys arrive as from
  Lindroid-Keyboard. The Plasma session must be unlocked: when locked, keys go
  to the lock screen.
* In the container:
  * `systemctl status plektra-linux`, `journalctl -t plektra-linux` (there should be the line
    "grabbed /dev/input/eventN (Lindroid-Keyboard-0)");
  * `cat /run/plektra-linux/state.json`;
  * `libinput debug-events` (if libinput-tools is installed): events come from
    `Lindroid-Plektra-Keyboard`;
  * in Kate/Konsole: Alt+W gives `1`, a long W gives `1`, Sym Sym Q gives 😀, Sym×3 O gives °,
    Fn Fn E S D F move the cursor, holding Fn + T opens a tab.

## What is needed on the Android side (Titan2Parts, not in this directory)

1. **Touchpad** (`TouchpadController.java`, done). Neither side grabs the `touchPad` evdev
   node: Titan2Parts and this service both read it in parallel (the Android framework ignores
   the device through its idc). The hand-over goes through two properties, checked by both
   sides at every pad touch-down and kept for the whole gesture:
   * `sys.titan2.lindroid.touchpad` is set to `1` by this service while it runs and back to
     `0` when it stops;
   * `sys.titan2.focus` holds the focused Android window (our framework patch).

   While the focus starts with `org.lindroid.ui` and the property is `1`, Titan2Parts injects
   nothing for the pad (no scrolling, finger, clicks or cursor arrows), so Lindroid never sees the
   same swipe twice. The only thing it still does there is the wide swipe that turns the keyboard
   light on. This service, in turn, acts only while `org.lindroid.ui` has the focus (or when
   `sys.titan2.focus` cannot be read from the container, see item 3). When the service is not
   running, the property is `0` and Android handles the pad in the Lindroid window as in any app.
2. **Backlight** (`KeyboardLight.java`). Watch `sys.titan2.lindroid.kbd`
   (`caps|nav|alt|sym|off`) and, while `org.lindroid.ui` is focused, show the state.
   For example: on a change to non-`off`, a short flash to maximum brightness, then
   slow blinking until the value is `off`; on `off`, the normal mode.
   Watcher: `SystemProperties.addChangeCallback` or polling on focus change and on
   key presses (KeyHandler already calls `mLight.onKey`).
3. **SELinux** (outside this directory). The properties must be declared:
   ```
   # property_contexts
   sys.titan2.lindroid.   u:object_r:titan2_lindroid_prop:s0 prefix string
   # .te
   type titan2_lindroid_prop, property_type;
   set_prop(perspectived, titan2_lindroid_prop)   # Lindroid container processes
   get_prop(system_server, titan2_lindroid_prop)
   ```
   If `sys.titan2.focus` cannot be read from the container (a property type without access for
   perspectived), `get_prop(perspectived, <sys.titan2.focus type>)` is needed. Without the read
   the service drives the touchpad without the focus check.
4. The clipboard is already in the `vendor_lindroid/0008` patch (LindroidUI + `system_app.te`),
   nothing is needed in Titan2Parts.
