#!/usr/bin/python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# plektra-linux: Unihertz Titan 2 hardware keyboard behavior as in Plektra, for Linux
# in the Lindroid container (KDE Plasma 6 / kwin_wayland).
#
# A port of the Plektra logic (https://github.com/pkb-rocks/plektra, GPL-3.0; version
# nightly/v26.10-nightly.11.184948Z, commit d029832c5260040f2a852aa0adbe0a2813359a8b).
# Ported: ModifierKeyHandler.kt / ModifierStateController.kt (one-shot, lock by
# double press, hold), NavModeHandler.kt / NavModeController.kt (navigation mode),
# SymLayoutController.kt (SYM pages, SYM+key chord, auto-close),
# AlternateCharacterManager.kt (long press -> Alt character), InputEventRouter.kt
# (key handling order, Ctrl layout), TextInputController.kt (double space),
# DeviceSpecific.kt (Titan 2 scan codes: 251 = Ctrl, 253 = Sym). The mappings/*.json tables
# are taken from the Plektra assets unchanged.
# Plektra (C) the pkb-rocks/plektra contributors (a Pastiera fork). Port (C) 2026 serlk.
#
# This program is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either
# version 3 of the License, or (at your option) any later version. This program is
# distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
# the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# General Public License for more details (file COPYING).
#
# Device: reads "Lindroid-Keyboard-N" (a uinput device LindroidUI fills with raw
# Android scan codes), grabs it exclusively (EVIOCGRAB) and emits the result to its
# own uinput device "Lindroid-Plektra-Keyboard". Characters outside the layout (digits,
# symbols, emoji) are typed via free keycodes to which xkb/symbols/titan2 assigns
# the needed keysyms: the result does not depend on the active layout (us/ru).
# Python 3 standard library only (the Lindroid rootfs has python3, no compiler).

import errno
import fcntl
import json
import os
import queue
import re
import select
import signal
import socket
import stat
import struct
import subprocess
import sys
import syslog
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import plektra_android  # noqa: E402
import plektra_touchpad  # noqa: E402

VERSION = "1.0"
DEVICE_NAME = "Lindroid-Plektra-Keyboard"
# vendor/product 0x000a/0x000a: Android disables such a device via
# /system/usr/idc/Vendor_000a_Product_000a.idc (device.disabled=1, from vendor/lindroid),
# otherwise our output would go back to LindroidUI and round into the container.
DEVICE_VENDOR = 0x000A
DEVICE_PRODUCT = 0x000A
DEVICE_VERSION = 0x0703
SOURCE_RE = re.compile(r"^Lindroid-Keyboard-\d+$")

LIB_DIR = os.path.dirname(os.path.abspath(__file__))
from plektra_config import DEFAULT_CONFIG, SYSTEM_PATH as CONFIG_PATH  # noqa: E402
import plektra_config  # noqa: E402

# ---------------------------------------------------------------- evdev / uinput

EV_SYN, EV_KEY = 0, 1
SYN_REPORT = 0
EVENT_FMT = "llHHi"
EVENT_SIZE = struct.calcsize(EVENT_FMT)


def _ioc(d, t, nr, size):
    return (d << 30) | (size << 16) | (ord(t) << 8) | nr


EVIOCGRAB = _ioc(1, "E", 0x90, 4)
EVIOCGID = _ioc(2, "E", 0x02, 8)
UI_SET_EVBIT = _ioc(1, "U", 100, 4)
UI_SET_KEYBIT = _ioc(1, "U", 101, 4)
UI_DEV_SETUP = _ioc(1, "U", 3, 92)
UI_DEV_CREATE = _ioc(0, "U", 1, 0)
UI_DEV_DESTROY = _ioc(0, "U", 2, 0)


def EVIOCGNAME(n):
    return _ioc(2, "E", 0x06, n)


# linux/input-event-codes.h codes the daemon needs.
KEY = {
    "KEY_ESC": 1, "KEY_1": 2, "KEY_2": 3, "KEY_3": 4, "KEY_4": 5, "KEY_5": 6, "KEY_6": 7,
    "KEY_7": 8, "KEY_8": 9, "KEY_9": 10, "KEY_0": 11, "KEY_MINUS": 12, "KEY_EQUAL": 13,
    "KEY_BACKSPACE": 14, "KEY_TAB": 15, "KEY_Q": 16, "KEY_W": 17, "KEY_E": 18, "KEY_R": 19,
    "KEY_T": 20, "KEY_Y": 21, "KEY_U": 22, "KEY_I": 23, "KEY_O": 24, "KEY_P": 25,
    "KEY_LEFTBRACE": 26, "KEY_RIGHTBRACE": 27, "KEY_ENTER": 28, "KEY_LEFTCTRL": 29,
    "KEY_A": 30, "KEY_S": 31, "KEY_D": 32, "KEY_F": 33, "KEY_G": 34, "KEY_H": 35, "KEY_J": 36,
    "KEY_K": 37, "KEY_L": 38, "KEY_SEMICOLON": 39, "KEY_APOSTROPHE": 40, "KEY_GRAVE": 41,
    "KEY_LEFTSHIFT": 42, "KEY_BACKSLASH": 43, "KEY_Z": 44, "KEY_X": 45, "KEY_C": 46,
    "KEY_V": 47, "KEY_B": 48, "KEY_N": 49, "KEY_M": 50, "KEY_COMMA": 51, "KEY_DOT": 52,
    "KEY_SLASH": 53, "KEY_RIGHTSHIFT": 54, "KEY_LEFTALT": 56, "KEY_SPACE": 57,
    "KEY_CAPSLOCK": 58, "KEY_F1": 59, "KEY_F2": 60, "KEY_F3": 61, "KEY_F4": 62, "KEY_F5": 63,
    "KEY_F6": 64, "KEY_F7": 65, "KEY_F8": 66, "KEY_F9": 67, "KEY_F10": 68, "KEY_F11": 87,
    "KEY_F12": 88, "KEY_RIGHTCTRL": 97, "KEY_RIGHTALT": 100, "KEY_HOME": 102, "KEY_UP": 103,
    "KEY_PAGEUP": 104, "KEY_LEFT": 105, "KEY_RIGHT": 106, "KEY_END": 107, "KEY_DOWN": 108,
    "KEY_PAGEDOWN": 109, "KEY_INSERT": 110, "KEY_DELETE": 111, "KEY_MUTE": 113,
    "KEY_VOLUMEDOWN": 114, "KEY_VOLUMEUP": 115, "KEY_POWER": 116, "KEY_PAUSE": 119,
    "KEY_LEFTMETA": 125, "KEY_RIGHTMETA": 126, "KEY_COMPOSE": 127, "KEY_MENU": 139,
    "KEY_PROG1": 148, "KEY_PROG2": 149, "KEY_BACK": 158, "KEY_FORWARD": 159,
    "KEY_NEXTSONG": 163, "KEY_PLAYPAUSE": 164, "KEY_PREVIOUSSONG": 165, "KEY_STOPCD": 166,
    "KEY_HOMEPAGE": 172, "KEY_REFRESH": 173, "KEY_PRINT": 210, "KEY_SEARCH": 217,
    "KEY_COPY": 133, "KEY_PASTE": 135, "KEY_CUT": 137, "KEY_UNDO": 131,
}
CODE_NAME = {v: k for k, v in KEY.items()}

# Titan 2 keyboard scan codes (vendor-titan2/keylayout/TitanKey.kl) as LindroidUI forwards them.
T_SHIFT = 42
T_ALT = 100
T_SYM = 253
T_FN = 251          # Plektra DeviceSpecific.SCANCODE_TITAN2_CTRL; Android: CTRL_LEFT
T_BACK = 158
T_RECENTS = 580
T_SIDE1 = 249
T_SIDE2 = 250
T_DEL = 14
T_ENTER = 28
T_SPACE = 57
LETTER_CODES = {KEY["KEY_" + c]: c for c in "QWERTYUIOPASDFGHJKLZXCVBNM"}
ROWS = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"]
MOD_CODES = {T_SHIFT, T_ALT, T_FN}
# Output modifiers
O_SHIFT = KEY["KEY_LEFTSHIFT"]
O_CTRL = KEY["KEY_LEFTCTRL"]
O_ALT = KEY["KEY_LEFTALT"]
O_META = KEY["KEY_LEFTMETA"]
OUT_MODS = (O_CTRL, O_ALT, O_META, O_SHIFT)

# Slots for characters outside the layout: keycodes free on the phone keyboard, to which
# xkb/symbols/titan2 assigns keysyms on two levels (without / with Shift). Only codes <= 247
# (xkb <= 255), otherwise X11 apps in XWayland will not see them. Skipped are codes that
# the kernel (rfkill-input: 237-239, 246, 247), logind/powerdevil/KWin react to regardless of keysym
# (power, brightness, network, camera) and 195..199 (xkb 203..207 are the virtual modifiers <MDSW> <ALT> <META> ...).
SLOT_AVOID = {205, 212, 224, 225, 236, 237, 238, 239, 240, 246, 247}
# Codes that are also named keys in KEY (KEY_PRINT, KEY_SEARCH) and can be sent by macros and
# key settings: a slot keysym on them would replace Print/Search. Emitted, but not used as slots.
SLOT_RESERVED = {210, 217}
SLOT_CODES = [c for c in range(183, 195)] + [c for c in range(200, 247)
                                              if c not in SLOT_AVOID and c not in SLOT_RESERVED]
XKB_NAME = {c: "<FK%d>" % (c - 183 + 13) for c in range(183, 195)}
XKB_NAME.update({c: "<I%d>" % (c + 8) for c in range(200, 247)})


def log(msg):
    try:
        syslog.syslog(syslog.LOG_INFO, msg)
    except Exception:
        pass
    if os.environ.get("PLEKTRA_DEBUG") or sys.stderr.isatty():
        print(msg, file=sys.stderr, flush=True)


def now_ms():
    return time.monotonic() * 1000.0


# ---------------------------------------------------------------- Plektra tables

ANDROID_TO_EVDEV = {"KEYCODE_" + c: KEY["KEY_" + c] for c in "QWERTYUIOPASDFGHJKLZXCVBNM"}
ANDROID_TO_EVDEV.update({"KEYCODE_%d" % d: KEY["KEY_%d" % d] for d in range(10)})
ANDROID_TO_EVDEV["KEYCODE_GRAVE"] = KEY["KEY_GRAVE"]

# InputEventRouter.handleCtrlModifiedKey: "keycode" values
CTRL_KEYCODES = {
    "DPAD_UP": "KEY_UP", "DPAD_DOWN": "KEY_DOWN", "DPAD_LEFT": "KEY_LEFT",
    "DPAD_RIGHT": "KEY_RIGHT", "DPAD_CENTER": "KEY_ENTER", "TAB": "KEY_TAB",
    "MOVE_HOME": "KEY_HOME", "MOVE_END": "KEY_END", "PAGE_UP": "KEY_PAGEUP",
    "PAGE_DOWN": "KEY_PAGEDOWN", "ESCAPE": "KEY_ESC", "FORWARD_DEL": "KEY_DELETE",
}
SELECTION_AWARE = {"KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_HOME", "KEY_END",
                   "KEY_PAGEUP", "KEY_PAGEDOWN"}
# Plektra actions -> Linux shortcuts. Android does them via InputConnection
# (performContextMenuAction, TextSelectionHelper); on the desktop these are the standard shortcuts.
CTRL_ACTIONS = {
    "copy": ("KEY_C", {O_CTRL}), "paste": ("KEY_V", {O_CTRL}), "cut": ("KEY_X", {O_CTRL}),
    "undo": ("KEY_Z", {O_CTRL}), "select_all": ("KEY_A", {O_CTRL}),
    "expand_selection_left": ("KEY_LEFT", {O_SHIFT}),
    "expand_selection_right": ("KEY_RIGHT", {O_SHIFT}),
    "expand_selection_word_left": ("KEY_LEFT", {O_CTRL, O_SHIFT}),
    "expand_selection_word_right": ("KEY_RIGHT", {O_CTRL, O_SHIFT}),
    "move_word_left": ("KEY_LEFT", {O_CTRL}), "move_word_right": ("KEY_RIGHT", {O_CTRL}),
    "page_start": ("KEY_HOME", {O_CTRL}), "page_end": ("KEY_END", {O_CTRL}),
    "media_play_pause": ("KEY_PLAYPAUSE", set()), "media_previous": ("KEY_PREVIOUSSONG", set()),
    "media_next": ("KEY_NEXTSONG", set()),
}


def find_mapping_file(cfg, name):
    for d in cfg["mappings_dirs"]:
        p = os.path.join(d, name)
        if os.path.isfile(p):
            return p
    return None


def read_mappings(cfg, name):
    """The "mappings" dictionary of the first readable table in mappings_dirs. A broken file
    (bad JSON, wrong structure) is skipped with a message, the next directory is tried."""
    for d in cfg.get("mappings_dirs") or []:
        p = os.path.join(d, name)
        if not os.path.isfile(p):
            continue
        try:
            with open(p, encoding="utf-8") as f:
                data = json.load(f)
            m = data.get("mappings", {}) if isinstance(data, dict) else None
            if isinstance(m, dict):
                return m
            log("%s: no \"mappings\" dictionary, skipped" % p)
        except (OSError, ValueError) as e:
            log("%s: %s, skipped" % (p, e))
    return {}


def load_string_mappings(cfg, name):
    """KeyMappingLoader.loadStringMappings: {"mappings": {"KEYCODE_Q": "0", ...}}"""
    data = read_mappings(cfg, name)
    out = {}
    for k, v in data.items():
        code = ANDROID_TO_EVDEV.get(k)
        if code is not None and isinstance(v, str) and v:
            out[code] = v
    return out


def load_ctrl_mappings(cfg):
    data = read_mappings(cfg, "ctrl_key_mappings.json")
    out = {}
    for k, v in data.items():
        code = ANDROID_TO_EVDEV.get(k)
        if code is None or not isinstance(v, dict):
            continue
        t = v.get("type", "none")
        out[code] = (t, v.get(t, "") if t != "none" else "")
    return out


class Tables:
    PAGE_TITLES = {5: "Titan", 1: "Emoji", 2: "Symbols"}
    PAGE_IDS = {"device": 5, "emoji": 1, "symbols": 2}

    def __init__(self, cfg):
        self.device = load_string_mappings(cfg, "device_sym_key_mappings.json")
        if KEY["KEY_GRAVE"] in self.device:
            self.device[KEY["KEY_GRAVE"]] = "€"   # DEFAULT_PHYSICAL_KEYBOARD_CURRENCY_SYMBOL
        self.emoji = load_string_mappings(cfg, "sym_key_mappings.json")
        self.emoji_upper = load_string_mappings(cfg, "sym_key_mappings_uppercase.json")
        self.symbols = load_string_mappings(cfg, "sym_key_mappings_page2.json")
        self.symbols_upper = load_string_mappings(cfg, "sym_key_mappings_page2_uppercase.json")
        self.ctrl = load_ctrl_mappings(cfg)
        b = cfg.get("alt_binding", "device")
        self.alt = {"emoji": self.emoji, "symbols": self.symbols}.get(b, self.device)
        self.pages = [self.PAGE_IDS[p] for p in cfg.get("sym_pages", []) if p in self.PAGE_IDS]

    def page_map(self, page, shift):
        """SymLayoutController.mappingsForPage"""
        if page == 5:
            return self.device
        if page == 1:
            return dict(self.emoji, **self.emoji_upper) if shift else self.emoji
        if page == 2:
            return dict(self.symbols, **self.symbols_upper) if shift else self.symbols
        return {}

    def all_texts(self):
        for m in (self.device, self.emoji, self.emoji_upper, self.symbols, self.symbols_upper,
                  self.alt):
            for v in m.values():
                yield v


# ---------------------------------------------------------------- xkb slots

def needed_codepoints(texts):
    cps = set()
    for t in texts:
        for ch in t:
            if ch not in " \n\t":
                cps.add(ord(ch))
    return sorted(cps)


def gen_xkb(cps):
    cap = len(SLOT_CODES) * 2
    if len(cps) > cap:
        raise SystemExit("plektra-linux: %d characters, only %d slots" % (len(cps), cap))
    lines = [
        "// Generated by plektra-linux --gen-xkb. Do not edit by hand: regenerate.",
        "// SPDX-License-Identifier: GPL-3.0-or-later",
        "// plektra-linux slots: keycodes without keys on Titan 2 -> Plektra characters (levels 1/2).",
        "// Included unconditionally by the /etc/xkb/rules/evdev rule, over any layout.",
        "default partial alphanumeric_keys",
        'xkb_symbols "plektra" {',
    ]
    for i, code in enumerate(SLOT_CODES):
        pair = cps[2 * i:2 * i + 2]
        if not pair:
            break
        syms = ["U%04X" % c for c in pair]
        if len(syms) == 1:
            syms.append(syms[0])
        lines.append('    key %s { type[Group1] = "TWO_LEVEL", symbols[Group1] = [ %s ] };'
                     ' // evdev %d: %s' % (XKB_NAME[code], ", ".join(syms), code,
                                          " ".join(chr(c) for c in pair)))
    lines.append("};")
    return "\n".join(lines) + "\n"


SLOT_LINE = re.compile(r"key <[A-Z0-9]+>.*symbols\[Group1\] = \[ (U[0-9A-F]+), (U[0-9A-F]+) \].*"
                       r"// evdev (\d+):")


def parse_xkb(path):
    table = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                m = SLOT_LINE.search(line)
                if not m:
                    continue
                code = int(m.group(3))
                c1, c2 = int(m.group(1)[1:], 16), int(m.group(2)[1:], 16)
                table.setdefault(chr(c1), (code, 1))
                if c2 != c1:
                    table.setdefault(chr(c2), (code, 2))
    except OSError:
        pass
    return table


# ---------------------------------------------------------------- output

class Output:
    """Virtual keyboard. emit(code, value) writes an event; output modifiers are
    tracked so characters are typed with exactly the needed set of modifiers."""

    def __init__(self, writer, slots):
        self.writer = writer
        self.slots = slots
        self.down = set()
        self.missing_reported = set()
        self.commit_hook = None      # plektra-linux-im is active: text goes through commit_string

    def _ev(self, code, value):
        if value and code in self.down:
            return
        if not value and code not in self.down:
            return
        (self.down.add if value else self.down.discard)(code)
        self.writer(code, value)

    def press(self, code):
        self._ev(code, 1)

    def release(self, code):
        self._ev(code, 0)

    def release_all(self):
        for c in list(self.down):
            self._ev(c, 0)

    def tap(self, code, mods=frozenset()):
        """Press and release code with exactly the modifier set mods."""
        saved = [m for m in OUT_MODS if m in self.down]
        for m in saved:
            if m not in mods:
                self._ev(m, 0)
        for m in OUT_MODS:
            if m in mods:
                self._ev(m, 1)
        self._ev(code, 1)
        self._ev(code, 0)
        for m in OUT_MODS:
            if m in mods and m not in saved:
                self._ev(m, 0)
        for m in saved:
            self._ev(m, 1)

    def type_text(self, text):
        if self.commit_hook and text.strip("\n\t") == text and self.commit_hook(text):
            return
        for ch in text:
            if ch == " ":
                self.tap(KEY["KEY_SPACE"])
            elif ch == "\n":
                self.tap(KEY["KEY_ENTER"])
            elif ch == "\t":
                self.tap(KEY["KEY_TAB"])
            elif ch in self.slots:
                code, level = self.slots[ch]
                self.tap(code, {O_SHIFT} if level == 2 else set())
            elif "a" <= ch.lower() <= "z" and ch.isascii():
                # Latin letters of macros: as plain keys (with the ru layout they come out Cyrillic,
                # so in a text field the text goes through plektra-linux-im)
                self.tap(KEY["KEY_" + ch.upper()], {O_SHIFT} if ch.isupper() else set())
            elif ch not in self.missing_reported:
                self.missing_reported.add(ch)
                log("no xkb slot for %r (U+%04X): regenerate %s and log in again"
                    % (ch, ord(ch), "xkb/symbols/titan2"))


# ---------------------------------------------------------------- Plektra state

SHIFT_OFF, SHIFT_ONE_SHOT, SHIFT_CAPS = "off", "one_shot", "caps"


class ModState:
    """ModifierKeyHandler.CtrlState / AltState"""

    def __init__(self):
        self.pressed = False
        self.one_shot = False
        self.latch = False
        self.last_release = 0.0
        self.down_time = 0.0
        self.before_hold = None
        self.other_key = False

    def snapshot(self):
        return (self.one_shot, self.latch)

    def restore(self, snap):
        self.one_shot, self.latch = snap


class Engine:
    def __init__(self, cfg, tables, out, ui=None, clock=now_ms):
        self.cfg = cfg
        self.t = tables
        self.out = out
        self.ui = ui or (lambda kind, **kw: None)
        self.clock = clock
        self.lp_ms = float(cfg["long_press_ms"])
        self.dt_ms = float(cfg["double_tap_ms"])
        # Shift: ModifierStateController.ShiftStateMachine
        self.shift = SHIFT_OFF
        self.shift_last_tap = 0.0
        self.shift_pressed = False
        self.shift_down_time = 0.0
        self.shift_before_hold = None
        self.shift_other_key = False
        self.autocap = False
        self.ctrl = ModState()
        self.alt = ModState()
        self.last_mod_code = 0          # registerModifierTap / registerNonModifierKey
        # SYM
        self.sym_page = 0
        self.sym_pressed = False
        self.sym_pending = False
        self.sym_chord_used = False
        self.ctrl_out_held = False      # Fn is held and a real Ctrl has been sent
        # keys
        self.phys = {}                  # scan code -> press time (deduplication)
        self.held = {}                  # scan code -> output code while the key is held
        self.long_press = {}            # scan code -> [deadline, replacement text]
        self.hist = []                  # what was typed in a row (for double space)
        self.last_space = 0.0
        self.im = None                  # ImServer
        self.base_cfg = cfg
        self.app = ""
        self.profile = None
        self.sugg = 0                   # how many suggestions are on the bar
        self.space_pending = None       # [deadline, pressed_at] of a long space
        self.space_consumed = False
        self.remapped = {}              # phys. code -> output code (the "keys" profile)
        self.run_cmd = None             # the "run" macro: fn(cmd)
        self.touchpad = None

    def reload(self, cfg, tables):
        """New settings (the file changed), without a restart."""
        lp, dt = float(cfg["long_press_ms"]), float(cfg["double_tap_ms"])   # may raise: nothing applied yet
        self.base_cfg = cfg
        self.t = tables
        self.lp_ms, self.dt_ms = lp, dt
        app, self.app = self.app, None
        self.profile = "?"
        self.set_app(app or "")
        if self.sym_page and self.sym_page not in self.pages():
            self.sym_page = 0

    def on_context(self, ctx):
        """The bar reported the text before the cursor: auto-capitalize at the start of a field/sentence
        (Plektra: auto_capitalize_first_letter, honors after_period too)."""
        if not self.cfg.get("autocap_first_letter", True):
            return
        if ctx.get("sentence_start"):
            if self.shift == SHIFT_OFF and not self.autocap:
                self.autocap = True
                self.changed()
        elif self.autocap and not self.hist:
            self.autocap = False
            self.changed()

    # ---- app profiles
    def set_app(self, app, title=""):
        if app == self.app:
            return
        self.app = app
        prof = None
        for p in self.base_cfg.get("profiles") or []:
            if app and app in (p.get("apps") or []):
                prof = p
                break
        cfg = dict(self.base_cfg)
        if prof:
            cfg.update(prof.get("overrides") or {})
        self.cfg = cfg
        name = prof.get("name", "?") if prof else None
        if name != self.profile:
            self.profile = name
            log("window %s: profile %s" % (app, name or "default"))

    def pages(self):
        return [Tables.PAGE_IDS[p] for p in self.cfg.get("sym_pages", []) if p in Tables.PAGE_IDS]

    def altmap(self):
        b = self.cfg.get("alt_binding", "device")
        if b == "first":                    # "First layer in SYM order"
            p = next(iter(self.pages()), 5)
            return self.t.page_map(p, False) or self.t.device
        return {"emoji": self.t.emoji, "symbols": self.t.symbols}.get(b, self.t.device)

    def in_field(self):
        """A text field is focused (plektra-linux-im activated by kwin). Without the IM, assume yes."""
        im = self.im
        if im is None or not im.connected():
            return True
        return bool(im.active)

    # ---- common helpers
    def is_consecutive(self, code):
        r = self.last_mod_code == code
        self.last_mod_code = code
        return r

    def ctrl_active(self):
        return self.ctrl.pressed or self.ctrl.one_shot or self.ctrl.latch

    def alt_active(self):
        return self.alt.pressed or self.alt.one_shot or self.alt.latch

    def state(self):
        return {
            "shift": self.shift, "shift_pressed": self.shift_pressed, "autocap": self.autocap,
            "ctrl": "latch" if self.ctrl.latch else ("one_shot" if self.ctrl.one_shot else "off"),
            "ctrl_pressed": self.ctrl.pressed, "nav_mode": self.ctrl.latch,
            "alt": "latch" if self.alt.latch else ("one_shot" if self.alt.one_shot else "off"),
            "alt_pressed": self.alt.pressed, "sym_page": self.sym_page,
            "sym_pressed": self.sym_pressed,
        }

    def changed(self):
        """After every event: state for the plektra-linux-im bar (LED and the SYM page)."""
        if self.im is None:
            return
        page = self.page_preview()
        letters = {LETTER_CODES[c]: v for c, v in (page or {}).items() if c in LETTER_CODES}
        self.im.state(self.state(), letters if page is not None else None)

    def notify(self, what):
        self.ui("state", what=what, state=self.state(), page_map=self.page_preview())

    def page_preview(self):
        if not self.sym_page:
            return None
        return self.t.page_map(self.sym_page, self.shift != SHIFT_OFF)

    def hist_push(self, ch):
        self.hist.append(ch)
        del self.hist[:-6]

    def reset_context(self):
        """The cursor may have moved (touch, arrows, Enter): double space/auto-capitalize start over."""
        self.hist = []
        self.last_space = 0.0
        if self.autocap:
            self.autocap = False

    def wants_upper(self):
        """InputEventRouter.routeEditableFieldKeyDown: resolvedUppercase"""
        if self.shift == SHIFT_ONE_SHOT or self.autocap:
            return True
        if self.shift == SHIFT_CAPS:
            return not self.shift_pressed
        return self.shift_pressed

    def consume_shift_one_shot(self):
        changed = False
        if self.shift == SHIFT_ONE_SHOT:
            self.shift = SHIFT_OFF
            changed = True
        if self.autocap:
            self.autocap = False
        if changed and self.cfg.get("osd_oneshot"):
            self.notify("shift")

    def type_symbol(self, text):
        self.out.type_text(text)
        for ch in text:
            self.hist_push(ch)

    # ---- events
    def key(self, code, value):
        if value == 2:
            return
        if self.touchpad is not None and value:
            self.touchpad.on_key(code != T_SHIFT)
        t = self.clock()
        if self.filtered(code, value, t):
            return
        if self.remap_key(code, value):
            self.changed()
            return
        if value:
            if code in self.phys:
                return                  # duplicate press
            self.phys[code] = t
            self.key_down(code, t)
        else:
            if code not in self.phys:
                return                  # duplicate release
            del self.phys[code]
            self.key_up(code, t)
        self.changed()

    def filtered(self, code, value, t):
        """Plektra (Accessibility): "Debounce" and "Ignore overlaps"."""
        ign = self.__dict__.setdefault("_ignored", set())
        if not value:
            if code in ign:
                ign.discard(code)
                return True
            return False
        if code in self.phys:
            return False
        cfg = self.cfg
        last = self.__dict__.setdefault("_last_press", {})
        prev = last.get(code)
        last[code] = t
        if cfg.get("bounce_keys_enabled") and prev is not None and \
                t - prev < float(cfg.get("bounce_keys_delay_ms", 80)):
            cat = ("bounce_keys_modifiers" if code in MOD_CODES or code == T_SYM else
                   "bounce_keys_space" if code == T_SPACE else
                   "bounce_keys_enter" if code == T_ENTER else
                   "bounce_keys_backspace" if code == T_DEL else "bounce_keys_chars")
            if cfg.get(cat, cat != "bounce_keys_modifiers"):
                ign.add(code)
                return True
        if cfg.get("overlapping_keys_enabled") and code not in MOD_CODES and code != T_SYM:
            if any(c not in MOD_CODES and c != T_SYM for c in self.phys):
                ign.add(code)
                return True
        return False

    PHYS_NAMES = {"SYM": T_SYM, "FN": T_FN, "ALT": T_ALT, "SHIFT": T_SHIFT, "BACK": T_BACK,
                  "SPACE": T_SPACE, "DEL": T_DEL, "ENTER": T_ENTER, "SIDE1": T_SIDE1,
                  "SIDE2": T_SIDE2}

    def remap_key(self, code, value):
        """The "keys" profile: a physical key becomes another one entirely (a game: Fn = Tab)."""
        if value and code not in self.remapped:
            keys = self.cfg.get("keys") or {}
            if not keys:
                return False
            for name, spec in keys.items():
                pc = self.PHYS_NAMES.get(name) or KEY.get("KEY_" + name)
                if pc == code:
                    out = KEY.get(spec) if isinstance(spec, str) else None
                    if out is None:
                        return False
                    self.remapped[code] = out
                    self.out.press(out)
                    return True
            return False
        if not value and code in self.remapped:
            self.out.release(self.remapped.pop(code))
            return True
        return code in self.remapped

    def tick(self):
        """Long press timer (AlternateCharacterManager.scheduleLongPress)."""
        t = self.clock()
        sp = self.space_pending
        if sp and t >= sp[0]:
            # long space: KDE layout switch, the space itself is not typed
            self.space_pending = None
            self.space_consumed = True
            self.do_keys(self.cfg.get("layout_switch_keys"))
        for code, lp in list(self.long_press.items()):
            if t >= lp[0]:
                del self.long_press[code]
                self.out.tap(KEY["KEY_BACKSPACE"])
                if self.hist:
                    self.hist.pop()
                self.changed()
                if lp[1] == "text":
                    self.type_symbol(lp[2])
                else:                    # long_press_mode = shift: the same letter in uppercase
                    self.out.tap(lp[2], {O_SHIFT})
                    self.hist_push("a")

    def next_deadline(self):
        dl = [lp[0] for lp in self.long_press.values()]
        if self.space_pending:
            dl.append(self.space_pending[0])
        return min(dl) if dl else None

    def other_key_pressed(self):
        if self.ctrl.pressed:
            self.ctrl.other_key = True
        if self.alt.pressed:
            self.alt.other_key = True
        if self.shift_pressed:
            self.shift_other_key = True

    # ---- modifiers
    def shift_down(self, t):
        self.out.press(O_SHIFT)
        self.shift_pressed = True
        self.shift_down_time = t
        self.shift_before_hold = (self.shift, self.autocap)
        self.shift_other_key = False
        if self.autocap and self.shift == SHIFT_OFF:
            # auto-capitalization in Plektra is the same one-shot Shift (requestShiftOneShotFromAutoCap):
            # pressing Shift turns it off
            self.shift = SHIFT_ONE_SHOT
        prev = self.shift
        consecutive = self.is_consecutive(T_SHIFT)
        double = consecutive and t - self.shift_last_tap < self.dt_ms
        self.shift_last_tap = t
        if double or self.cfg.get("shift_tap_latches"):
            self.shift = SHIFT_OFF if self.shift == SHIFT_CAPS else SHIFT_CAPS
        elif self.shift == SHIFT_OFF:
            self.shift = SHIFT_ONE_SHOT
        else:
            self.shift = SHIFT_OFF
        self.autocap = False            # a manual Shift cancels auto-capitalization
        if prev != self.shift and (self.shift == SHIFT_CAPS or prev == SHIFT_CAPS
                                   or self.cfg.get("osd_oneshot")):
            self.notify("shift")

    def shift_up(self, t):
        self.out.release(O_SHIFT)
        self.shift_pressed = False
        if t - self.shift_down_time > 300 and not self.shift_other_key and self.shift_before_hold:
            # "intentional hold" without other keys: the logical state as before the press
            self.shift, self.autocap = self.shift_before_hold
        self.shift_before_hold = None

    def mod_down(self, st, code, t, single_tap_latches):
        """ModifierKeyHandler.handleCtrlKeyDown / handleAltKeyDown"""
        consecutive = self.is_consecutive(code)
        st.pressed = True
        st.down_time = t
        st.before_hold = st.snapshot()
        st.other_key = False
        if not consecutive:
            st.last_release = 0.0
        double = consecutive and st.last_release > 0 and t - st.last_release < self.dt_ms
        if st.latch:
            st.latch = False
            st.last_release = 0.0
        elif st.one_shot:
            st.one_shot = False
            if double:
                st.latch = True
            st.last_release = 0.0
        elif single_tap_latches or double:
            st.latch = True
            st.last_release = 0.0
        else:
            st.one_shot = True

    def mod_up(self, st, t):
        if not st.pressed:
            return
        st.pressed = False
        st.last_release = t
        if t - st.down_time > 300 and not st.other_key and st.before_hold is not None:
            st.restore(st.before_hold)
        elif st.other_key and st.one_shot and not st.latch:
            # a key was pressed during the hold: do not leave the one-shot mode
            st.one_shot = False
        st.before_hold = None

    def fn_down(self, t):
        was = (self.ctrl.one_shot, self.ctrl.latch)
        self.mod_down(self.ctrl, T_FN, t, self.cfg.get("ctrl_tap_latches"))
        if self.ctrl.latch and not self.cfg.get("nav_mode", True):
            self.ctrl.latch = False      # navigation mode is disabled in the settings
        if was[1] != self.ctrl.latch or (self.cfg.get("osd_oneshot") and was != (
                self.ctrl.one_shot, self.ctrl.latch)):
            self.notify("ctrl")

    def fn_up(self, t):
        if self.ctrl_out_held:
            self.out.release(O_CTRL)
            self.ctrl_out_held = False
        was = (self.ctrl.one_shot, self.ctrl.latch)
        self.mod_up(self.ctrl, t)
        if was[1] != self.ctrl.latch:
            self.notify("ctrl")

    def alt_down(self, t):
        if self.sym_page:
            self.close_sym()
        was = (self.alt.one_shot, self.alt.latch)
        self.mod_down(self.alt, T_ALT, t, self.cfg.get("alt_tap_latches"))
        if was[1] != self.alt.latch or (self.cfg.get("osd_oneshot") and was != (
                self.alt.one_shot, self.alt.latch)):
            self.notify("alt")

    def alt_up(self, t):
        was = self.alt.latch
        self.mod_up(self.alt, t)
        if was != self.alt.latch:
            self.notify("alt")

    def clear_alt(self):
        changed = self.alt.one_shot or self.alt.latch
        self.alt.one_shot = self.alt.latch = False
        self.alt.last_release = 0.0
        if changed:
            self.notify("alt")

    def clear_ctrl(self):
        changed = self.ctrl.one_shot or self.ctrl.latch
        self.ctrl.one_shot = self.ctrl.latch = False
        self.ctrl.last_release = 0.0
        if changed:
            self.notify("ctrl")

    # ---- SYM (SymLayoutController)
    def toggle_sym(self):
        cycle = [0] + self.pages()
        i = cycle.index(self.sym_page) if self.sym_page in cycle else 0
        self.sym_page = cycle[(i + 1) % len(cycle)] if len(cycle) > 1 else 0
        self.notify("sym")

    def close_sym(self):
        if self.sym_page:
            self.sym_page = 0
            self.notify("sym")
            return True
        return False

    def chord_symbol(self, code, shift):
        """SymLayoutController.resolveChordSymbol"""
        pages = self.pages()
        page = self.sym_page if self.sym_page in (5, 1, 2) else (pages[0] if pages else 0)
        m = self.t.page_map(page, False)
        if shift:
            up = self.t.page_map(page, True)
            return up.get(code) or m.get(code)
        return m.get(code)

    # ---- output for held keys (kwin does the repeat)
    def hold(self, code, out_code, mods=frozenset()):
        for m in OUT_MODS:
            if m in mods:
                self.out.press(m)
        self.out.press(out_code)
        self.held[code] = (out_code, frozenset(m for m in mods if m != O_SHIFT or
                                               not self.shift_pressed))

    def key_up(self, code, t):
        if code == T_SHIFT:
            return self.shift_up(t)
        if code == T_FN:
            return self.fn_up(t)
        if code == T_ALT:
            return self.alt_up(t)
        if code == T_SYM:
            self.sym_pressed = False
            if self.sym_pending and not self.sym_chord_used:
                if self.cfg.get("sym_tap"):          # profile: e.g. Esc in a terminal
                    self.do_keys(self.cfg["sym_tap"])
                else:
                    self.toggle_sym()
            self.sym_pending = self.sym_chord_used = False
            return
        if code == T_SPACE and (self.space_pending or self.space_consumed):
            pend = self.space_pending
            self.space_pending = None
            self.space_consumed = False
            if pend:                                  # short space: type it now
                if not self.double_space(pend[1]):
                    self.out.tap(KEY["KEY_SPACE"])
                    self.hist_push(" ")
                    self.after_space()
            return
        self.long_press.pop(code, None)
        h = self.held.pop(code, None)
        if h:
            out_code, mods = h
            self.out.release(out_code)
            for m in mods:
                if m == O_CTRL and self.ctrl_out_held:
                    continue
                self.out.release(m)

    # ---- the main part: InputEventRouter.routeEditableFieldKeyDown in the Plektra order
    def key_down(self, code, t):
        if code == T_SHIFT:
            return self.shift_down(t)
        if code == T_FN:
            return self.fn_down(t)
        if code == T_ALT:
            return self.alt_down(t)
        self.last_mod_code = 0          # registerNonModifierKey
        self.other_key_pressed()
        if code == T_SYM:
            # Alt+Sym: reset Alt (on Android this switches the language)
            if self.alt.pressed:
                self.alt.pressed = False
                self.clear_alt()
            self.sym_pending = True
            self.sym_chord_used = False
            self.sym_pressed = True
            return
        if code in (T_SIDE1, T_SIDE2, T_RECENTS):
            act = self.cfg.get({T_SIDE1: "side_key_1", T_SIDE2: "side_key_2",
                                T_RECENTS: "recents_key"}[code])
            return self.do_keys(act)
        # SYM is held: SYM+key chord
        if self.sym_pressed and code not in MOD_CODES:
            self.sym_chord_used = True
            if code in LETTER_CODES:
                mk = ("FN+" if self.ctrl.pressed else "") + "SYM+" + LETTER_CODES[code]
                m = (self.cfg.get("macros") or {}).get(mk)
                if m:
                    return self.macro(m)
            ch = self.chord_symbol(code, self.shift != SHIFT_OFF or self.shift_pressed)
            if ch:
                self.type_symbol(ch)
                self.consume_shift_one_shot()
                return
        # "Back" closes the SYM page
        if code == T_BACK and self.sym_page:
            self.close_sym()
            return
        # Ctrl+Space: layout switch, Ctrl and Alt are reset
        if code == T_SPACE and self.ctrl_active() and self.cfg.get("ctrl_space_layout_switch") \
                and not self.alt.pressed:
            self.clear_alt()
            keep = (self.ctrl.latch and self.cfg.get("ctrl_tap_latches")
                    and self.cfg.get("ctrl_latch_stays_on_space"))
            if keep:
                self.ctrl.one_shot = False
            else:
                self.clear_ctrl()
            self.do_keys(self.cfg.get("layout_switch_keys"))
            return
        # Fn is held: "native" Ctrl+key shortcuts (handleCtrlModifiedKey: physical combo)
        if self.ctrl.pressed and not self.cfg.get("nav_mode_ctrl_hold"):
            # a one-shot Ctrl enabled by this Fn press is cleared on release (other_key)
            pick = {KEY["KEY_W"]: 0, KEY["KEY_E"]: 1, KEY["KEY_R"]: 2}.get(code)
            if pick is not None and pick < self.sugg and self.in_field() and self.im:
                # Fn + 1/2/3 (W/E/R, the Alt layer digits): a suggestion from the bar
                self.im.broadcast({"pick": pick}, only_active=True)
                return
            self.reset_context()
            out_code = self.map_back(code)
            if out_code is None:
                return
            if not self.ctrl_out_held:
                self.out.press(O_CTRL)
                self.ctrl_out_held = True
            mods = {O_ALT} if self.alt.pressed else set()
            self.hold(code, out_code, mods)
            return
        # Alt (one-shot/locked) is cleared by space and Enter
        pass_alt_boundary = False
        if code == T_SPACE and self.cfg.get("alt_space") and self.alt_active():
            if self.alt.one_shot:
                self.alt.one_shot = False
            return self.do_keys(self.cfg["alt_space"])     # profile: e.g. Tab in a terminal
        if self.cfg.get("clear_alt_on_space") and code in (T_SPACE, T_ENTER) and (
                self.alt.latch or self.alt.one_shot):
            if self.alt.latch and self.cfg.get("alt_latch_stays_on_space"):
                self.alt.one_shot = False
                pass_alt_boundary = True
            else:
                self.clear_alt()
        # An open SYM page (Ctrl bypasses it)
        if self.sym_page and not (self.ctrl.one_shot or self.ctrl.latch):
            if code == T_ENTER and self.cfg.get("sym_auto_close"):
                self.close_sym()
                return self.normal_key(code, t)
            ch = self.t.page_map(self.sym_page,
                                 self.shift != SHIFT_OFF or self.shift_pressed).get(code)
            if ch:
                self.type_symbol(ch)
                self.consume_shift_one_shot()
                if self.cfg.get("sym_auto_close"):
                    self.close_sym()
                return
        # Alt layer
        if not pass_alt_boundary and self.alt_active():
            self.long_press.pop(code, None)
            if self.alt.one_shot:
                self.alt.one_shot = False
                if self.cfg.get("osd_oneshot"):
                    self.notify("alt")
            if code == T_BACK:
                return self.normal_key(code, t)
            if code == T_SPACE:
                if self.cfg.get("alt_space"):        # profile: e.g. Tab in a terminal
                    return self.do_keys(self.cfg["alt_space"])
                self.out.tap(KEY["KEY_SPACE"])   # Alt+Space = just a space
                self.hist_push(" ")
                return
            ch = self.altmap().get(code)
            if ch:
                self.type_symbol(ch)
                return
            if code == T_DEL and self.cfg.get("alt_backspace_delete"):
                self.out.tap(KEY["KEY_DELETE"])
                return
            return self.normal_key(code, t, {O_ALT} if self.alt.pressed else set())
        # One-shot / locked Ctrl (navigation mode)
        if self.ctrl.one_shot or self.ctrl.latch:
            return self.ctrl_mapped(code, t)
        return self.normal_key(code, t)

    def map_back(self, code):
        if code == T_BACK:
            k = self.cfg.get("back_key") or "KEY_BACK"
            return KEY.get(k)
        if code > 247:
            return None
        return code

    def do_keys(self, spec):
        if not spec:
            return
        if isinstance(spec, str):
            spec = [spec]
        codes = [KEY[k] for k in spec if k in KEY]
        if not codes:
            return
        mods = {c for c in codes[:-1]}
        extra = [c for c in mods if c not in OUT_MODS]
        for c in extra:
            self.out.press(c)
        self.out.tap(codes[-1], {c for c in mods if c in OUT_MODS})
        for c in reversed(extra):
            self.out.release(c)

    def macro(self, m):
        """{"text": "..."}: insert ({date}, {time}, {datetime}, {date:%d.%m.%Y});
        {"keys": [...]}: a shortcut; {"run": "command"}: run as the session user."""
        if not isinstance(m, dict):
            return
        if isinstance(m.get("text"), str):
            import datetime
            now = datetime.datetime.now()

            default = {"date": "%Y-%m-%d", "time": "%H:%M", "datetime": "%Y-%m-%d %H:%M"}

            def sub(mt):
                return now.strftime(mt.group(2) or default[mt.group(1)])
            self.type_symbol(re.sub(r"\{(date|time|datetime)(?::([^}]*))?\}", sub, m["text"]))
        if m.get("keys"):
            self.do_keys(m["keys"])
        if isinstance(m.get("run"), str) and m["run"] and self.run_cmd:
            self.run_cmd(m["run"])

    def ctrl_mapped(self, code, t):
        """InputEventRouter.handleCtrlModifiedKey (+ NavModeController.handleNavModeKey)"""
        nav = self.ctrl.latch
        if self.ctrl.one_shot and not self.ctrl.latch:
            self.ctrl.one_shot = False
            if self.cfg.get("osd_oneshot"):
                self.notify("ctrl")
        self.reset_context()
        mode = self.cfg.get("fn_tap", "auto")
        if not nav and (mode == "ctrl" or (mode == "auto" and not self.in_field())):
            # outside a field (and in a terminal) a single Fn is a real Ctrl for the next key
            out_code = KEY["KEY_BACKSPACE"] if code == T_DEL else self.map_back(code)
            if out_code is not None:
                self.hold(code, out_code, {O_CTRL} | ({O_SHIFT} if self.shift_pressed else set()))
            return
        sel_shift = {O_SHIFT} if self.shift_pressed else set()
        m = self.t.ctrl.get(code)
        if nav and code == T_ENTER:
            self.out.tap(KEY["KEY_ENTER"])      # nav mode: Enter = DPAD_CENTER
            return
        if m:
            kind, value = m
            if kind == "keycode":
                name = CTRL_KEYCODES.get(value)
                if name:
                    self.hold(code, KEY[name], sel_shift if name in SELECTION_AWARE else set())
                return
            if kind == "action":
                a = CTRL_ACTIONS.get(value)
                if a:
                    name, mods = a
                    if value in ("move_word_left", "move_word_right", "page_start",
                                 "page_end"):
                        mods = mods | sel_shift
                    self.out.tap(KEY[name], mods)
                return                           # toggle_minimal_ui and the like: nothing to do
            if kind == "native_ctrl":
                self.out.tap(code, {O_CTRL})
                return
            return                               # none / command
        if code == T_DEL:
            self.hold(code, KEY["KEY_BACKSPACE"], {O_CTRL})   # delete a word
            return
        self.normal_key(code, t)

    def normal_key(self, code, t, mods=frozenset()):
        if code in LETTER_CODES:
            upper = self.wants_upper()
            self.out.tap(code, ({O_SHIFT} if upper else set()) | set(mods))
            had_one_shot = self.shift == SHIFT_ONE_SHOT or self.autocap
            self.hist_push("a")
            if had_one_shot:
                self.consume_shift_one_shot()
            mode = self.cfg.get("long_press_mode", "alt")
            alt = self.altmap()
            if mode == "alt" and code in alt:
                self.long_press[code] = [t + self.lp_ms, "text", alt[code]]
            elif mode == "shift" and not upper:
                self.long_press[code] = [t + self.lp_ms, "key", code]
            elif mode in ("sym", "sym_emoji", "sym_symbols"):
                # Plektra: "First emoji or symbol layer" / "Symbol layer" / "Emoji layer"
                page = {"sym_emoji": 1, "sym_symbols": 2}.get(mode) or next(
                    (p for p in self.pages() if p in (1, 2)), 1)
                ch = self.t.page_map(page, upper).get(code) or self.t.page_map(page, False).get(code)
                if ch:
                    self.long_press[code] = [t + self.lp_ms, "text", ch]
            return
        if code == T_SPACE:
            if not mods and self.cfg.get("long_space_ms"):
                # decided on release: short is a space, long is a layout switch
                self.space_pending = [t + float(self.cfg["long_space_ms"]), t]
                return
            if self.double_space(t):
                return
            self.hold(code, KEY["KEY_SPACE"], mods)
            self.hist_push(" ")
            self.after_space()
            return
        if code == T_DEL:
            if self.hist:
                self.hist.pop()
            self.autocap = False
            if (self.shift_pressed and self.cfg.get("shift_backspace_delete")) or \
                    (O_ALT in mods and self.cfg.get("alt_backspace_delete")):
                self.out.tap(KEY["KEY_DELETE"])      # delete the character right of the cursor
                return
            self.hold(code, KEY["KEY_BACKSPACE"], mods)
            return
        self.reset_context()
        if code == T_ENTER:
            self.hold(code, KEY["KEY_ENTER"], mods)
            return
        out_code = self.map_back(code)
        if out_code is not None:
            self.hold(code, out_code, mods)

    # ---- TextInputController.handleDoubleSpaceToPeriod / AutoCapitalizeHelper
    def double_space(self, t):
        if not self.cfg.get("double_space_period"):
            return False
        is_double = self.last_space > 0 and t - self.last_space < self.dt_ms
        self.last_space = t
        h = self.hist
        if not is_double or len(h) < 2 or h[-1] != " ":
            return False
        prev = h[-2]
        if prev == " " or prev in ".!?…" or not (prev == "a" or prev.isalnum()):
            return False
        self.out.tap(KEY["KEY_BACKSPACE"])
        h.pop()
        self.type_symbol(". ")
        self.last_space = 0.0
        self.after_space()
        return True

    def after_space(self):
        h = self.hist
        if (self.cfg.get("autocap_after_period") and len(h) >= 3 and h[-1] == " "
                and h[-2] in ".!?" and (h[-3] == "a" or h[-3].isalnum())):
            if self.shift == SHIFT_OFF:
                self.autocap = True
                if self.cfg.get("osd_oneshot"):
                    self.notify("shift")

    def reset_all(self):
        """The source is gone: release everything."""
        self.long_press.clear()
        self.held.clear()
        self.phys.clear()
        self.remapped.clear()
        # releases that would have been swallowed (debounce/overlap) never come from a gone source
        self.__dict__.get("_ignored", set()).clear()
        self.space_pending = None
        self.space_consumed = False
        self.sym_pending = self.sym_chord_used = False
        self.ctrl_out_held = False
        self.ctrl.pressed = self.alt.pressed = self.shift_pressed = self.sym_pressed = False
        self.out.release_all()


# ---------------------------------------------------------------- link to plektra-linux-im

UI_SOCK = "/run/plektra-linux/ui.sock"
INJECT_SOCK = "/run/plektra-linux/inject.sock"


def _greeter_uid():
    try:
        import pwd
        return pwd.getpwnam("sddm").pw_uid
    except (KeyError, ImportError):
        return -2


class ImServer:
    """Socket for plektra-linux-im (the kwin input method, runs as the session user).
    Service -> IM: {"state": {...}, "page": {"Q": "0", ...}|null}, {"commit": "text"}.
    IM -> service: {"active": true|false}: whether a text field is focused."""

    def __init__(self, path=UI_SOCK):
        self.path = path
        self.clients = []
        self.active = set()
        self.last = None
        self.lock = threading.Lock()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
        self.srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.srv.bind(path)
        os.chmod(path, 0o666)
        self.srv.listen(4)
        self.inbox = queue.Queue()      # IM messages for the main thread
        self.wake_r, self.wake_w = os.pipe()
        os.set_blocking(self.wake_r, False)
        threading.Thread(target=self.accept_loop, daemon=True).start()

    def connected(self):
        return bool(self.clients)

    def accept_loop(self):
        while True:
            try:
                c, _ = self.srv.accept()
            except OSError:
                time.sleep(1)
                continue
            try:
                uid = struct.unpack("3i", c.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED,
                                                       12))[1]
            except OSError:
                uid = -1
            if uid != 0 and uid != PlasmaUI.session_user() and uid != _greeter_uid():
                c.close()                       # only the Plasma session owner, sddm (or root)
                continue
            with self.lock:
                self.clients.append(c)
                last = self.last
            if getattr(self, "config_msg", None):
                self._send(c, self.config_msg)
            if last:
                self._send(c, last)
            threading.Thread(target=self.reader, args=(c,), daemon=True).start()
            log("plektra-linux-im connected")

    def reader(self, c):
        buf = b""
        while True:
            try:
                data = c.recv(4096)
            except OSError:
                data = b""
            if not data:
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                try:
                    m = json.loads(line)
                except ValueError:
                    continue
                if not isinstance(m, dict):
                    continue
                if "active" in m:
                    with self.lock:
                        (self.active.add if m["active"] else self.active.discard)(c)
                self.inbox.put(m)
                try:
                    os.write(self.wake_w, b"x")
                except OSError:
                    pass
                if m.get("missed"):
                    log("the IM did not accept the text %r (the field closed)" % m["missed"])
        with self.lock:
            self.active.discard(c)
            if c in self.clients:
                self.clients.remove(c)
        c.close()

    def _send(self, c, obj):
        # no timeouts on the socket: the reader thread reads it blocking. Messages are small;
        # if the IM hangs and the buffer is full, the message is lost, the service does not wait.
        data = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
        try:
            return c.send(data, socket.MSG_DONTWAIT) == len(data)
        except OSError:
            return False

    def broadcast(self, obj, only_active=False):
        with self.lock:
            targets = list(self.active if only_active else self.clients)
        ok = False
        for c in targets:
            ok = self._send(c, obj) or ok
        return ok

    def config(self, cfg):
        self.config_msg = {"config": cfg}
        self.broadcast(self.config_msg)

    def state(self, st, page):
        msg = {"state": st, "page": page}
        if msg == self.last:
            return
        self.last = msg
        self.broadcast(msg)

    def commit(self, text):
        if not self.active:
            return False
        return self.broadcast({"commit": text}, only_active=True)


# ---------------------------------------------------------------- Plasma interface

class PlasmaUI:
    """Indication in the Plasma session: the SYM page is a notification (stays while the page
    is open, updated in place and closed), modifier locks are an OSD.
    Runs from a separate thread via busctl --user as the session owner."""

    def __init__(self, cfg, im=None):
        self.cfg = cfg
        self.im = im
        self.reload_for = None          # (uid, bus mtime) for which the keymap was already reloaded
        self.q = queue.Queue(maxsize=16)
        self.notif_id = 0
        self.state_file = cfg.get("state_file")
        threading.Thread(target=self.run, daemon=True).start()

    def __call__(self, kind, **kw):
        try:
            self.q.put_nowait((kind, kw))
        except queue.Full:
            pass

    @staticmethod
    def session_user():
        best = None
        try:
            for n in os.listdir("/run/user"):
                if not n.isdigit() or int(n) < 1000:
                    continue
                bus = "/run/user/%s/bus" % n
                try:
                    st = os.stat(bus)
                except OSError:
                    continue
                if best is None or st.st_mtime > best[1]:
                    best = (int(n), st.st_mtime)
        except OSError:
            return None
        return best[0] if best else None

    def busctl(self, *args, verb="call"):
        uid = self.session_user()
        if uid is None:
            return None
        try:
            import pwd
            gid = pwd.getpwuid(uid).pw_gid
        except Exception:
            gid = uid
        env = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
               "DBUS_SESSION_BUS_ADDRESS": "unix:path=/run/user/%d/bus" % uid,
               "XDG_RUNTIME_DIR": "/run/user/%d" % uid}
        cmd = ["busctl", "--user", "--timeout=2", verb] + list(args)
        if os.geteuid() == 0:
            cmd = ["setpriv", "--reuid=%d" % uid, "--regid=%d" % gid, "--clear-groups"] + cmd
        try:
            r = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=3)
            return r.stdout if r.returncode == 0 else None
        except Exception:
            return None

    def osd(self, text, icon="input-keyboard"):
        self.busctl("org.kde.plasmashell", "/org/kde/osdService", "org.kde.osdService",
                    "showText", "ss", icon, text)

    def notify_page(self, title, body):
        out = self.busctl("org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                          "org.freedesktop.Notifications", "Notify", "susssasa{sv}i",
                          "Plektra", str(self.notif_id), "input-keyboard", title, body, "0",
                          "2", "transient", "b", "true", "urgency", "y", "0", "60000")
        if out:
            m = re.search(r"u (\d+)", out)
            if m:
                self.notif_id = int(m.group(1))

    def close_page(self):
        if self.notif_id:
            self.busctl("org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                        "org.freedesktop.Notifications", "CloseNotification", "u",
                        str(self.notif_id))
            self.notif_id = 0

    @staticmethod
    def page_body(page_map):
        rows = []
        for row in ROWS:
            cells = []
            for c in row:
                v = page_map.get(KEY["KEY_" + c])
                if v:
                    cells.append("%s %s" % (c, v))
            rows.append("   ".join(cells))
        return "\n".join(rows)

    def write_state(self, st):
        if not self.state_file:
            return
        try:
            os.makedirs(os.path.dirname(self.state_file), exist_ok=True)
            tmp = self.state_file + ".tmp"
            with open(tmp, "w") as f:
                json.dump(st, f)
            os.chmod(tmp, 0o644)
            os.replace(tmp, self.state_file)
        except OSError:
            pass

    def kbd_light(self, st):
        """Keyboard backlight indicator: an Android property (by default
        sys.titan2.lindroid.kbd = caps|nav|alt|sym|off). The backlight is controlled by Titan2Parts
        (KeyboardLight), which decides how to blink; the service only reports the state."""
        prop = self.cfg.get("kbd_light_prop")
        if not prop:
            return
        v = ("caps" if st.get("shift") == "caps" else "nav" if st.get("ctrl") == "latch"
             else "alt" if st.get("alt") == "latch" else "sym" if st.get("sym_page") else "off")
        if v != getattr(self, "_light", None):
            self._light = v
            if not plektra_android.set_prop(prop, v) and v != "off":
                log("backlight: init did not accept %s=%s (no SELinux rule?)" % (prop, v))

    def reload_keymap(self, force=False):
        """kwin rereads the layout (and with it /etc/xkb/rules/evdev and the plektra-linux slots) on
        the org.kde.keyboard /Layouts reloadConfig signal, the same one the keyboard KCM sends. Sent when
        a new session appears and after the slots are regenerated: no re-login needed."""
        uid = self.session_user()
        if uid is None:
            return
        try:
            key = (uid, os.stat("/run/user/%d/bus" % uid).st_mtime)
        except OSError:
            return
        if key == self.reload_for and not force:
            return
        if self.busctl("/Layouts", "org.kde.keyboard", "reloadConfig", verb="emit") is not None:
            self.reload_for = key
            log("kwin: reloadConfig sent (uid %d)" % uid)

    def run(self):
        while True:
            try:
                kind, kw = self.q.get(timeout=5)
            except queue.Empty:
                self.reload_keymap()
                continue
            if kind == "reload":
                self.reload_keymap(force=True)
                continue
            if kind != "state":
                continue
            st, what = kw["state"], kw["what"]
            self.write_state(st)
            self.kbd_light(st)
            if self.cfg.get("ui") != "plasma" or (self.im and self.im.connected()):
                continue
            if what == "sym":
                p = st["sym_page"]
                if p:
                    title = "SYM: %s" % Tables.PAGE_TITLES.get(p, p)
                    self.notify_page(title, self.page_body(kw["page_map"] or {}))
                else:
                    self.close_page()
            elif what == "shift":
                self.osd({"caps": "Shift locked (CAPS)", "one_shot": "Shift: one character",
                          "off": "Shift off"}[st["shift"]] if not st["autocap"]
                         else "Shift: auto-capitalize")
            elif what == "ctrl":
                self.osd({"latch": "Fn: navigation mode", "one_shot": "Fn (Ctrl): one action",
                          "off": "Fn: off"}[st["ctrl"]])
            elif what == "alt":
                self.osd({"latch": "Alt locked", "one_shot": "Alt: one character",
                          "off": "Alt off"}[st["alt"]])


# ---------------------------------------------------------------- devices

UI_SET_RELBIT = _ioc(1, "U", 102, 4)
EV_REL = 2


class UInput:
    def __init__(self, codes, name=DEVICE_NAME, product=DEVICE_PRODUCT, rels=()):
        path = "/dev/uinput"
        if not os.path.exists(path):
            try:
                os.mknod(path, 0o600 | stat.S_IFCHR, os.makedev(10, 223))
            except OSError as e:
                log("no /dev/uinput and could not create it: %s" % e)
        self.fd = os.open(path, os.O_WRONLY | os.O_NONBLOCK | os.O_CLOEXEC)
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_SYN)
        for c in sorted(codes):
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, c)
        if rels:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_REL)
            for r in rels:
                fcntl.ioctl(self.fd, UI_SET_RELBIT, r)
        setup = struct.pack("HHHH80sI", 0x06, DEVICE_VENDOR, product, DEVICE_VERSION,
                            name.encode(), 0)   # BUS_VIRTUAL = 0x06
        fcntl.ioctl(self.fd, UI_DEV_SETUP, setup)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)

    def write(self, code, value):
        t = time.time()
        sec, usec = int(t), int((t % 1) * 1e6)
        os.write(self.fd, struct.pack(EVENT_FMT, sec, usec, EV_KEY, code, value) +
                 struct.pack(EVENT_FMT, sec, usec, EV_SYN, SYN_REPORT, 0))

    def events(self, evs):
        """[(type, code, value), ...] + SYN_REPORT in one packet."""
        t = time.time()
        sec, usec = int(t), int((t % 1) * 1e6)
        os.write(self.fd, b"".join(struct.pack(EVENT_FMT, sec, usec, ty, c, v) for ty, c, v in evs)
                 + struct.pack(EVENT_FMT, sec, usec, EV_SYN, SYN_REPORT, 0))

    def close(self):
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError:
            pass
        os.close(self.fd)


def device_name(fd):
    buf = bytearray(256)
    try:
        n = fcntl.ioctl(fd, EVIOCGNAME(len(buf)), buf, True)
    except OSError:
        return ""
    return bytes(buf[:max(0, n - 1)]).split(b"\0")[0].decode("utf-8", "replace")


class Sources:
    """Sources: Lindroid-Keyboard-N (grabbed) and other Lindroid-* (context reset only)."""

    def __init__(self):
        self.devs = {}          # fd -> (path, rdev, kind)
        self.ignored = set()    # (rdev, inode) of nodes that are not ours
        self.want_touchpad = True
        self.last_scan = 0.0

    def scan(self):
        self.last_scan = time.monotonic()
        try:
            names = os.listdir("/dev/input")
        except OSError:
            return
        known = {v[1] for v in self.devs.values()}
        present = set()
        for n in names:
            if not n.startswith("event"):
                continue
            p = "/dev/input/" + n
            try:
                st = os.stat(p)
            except OSError:
                continue
            ident = (st.st_rdev, st.st_ino)
            present.add(ident)
            if not stat.S_ISCHR(st.st_mode) or st.st_rdev in known or ident in self.ignored:
                continue
            try:
                fd = os.open(p, os.O_RDONLY | os.O_NONBLOCK | os.O_CLOEXEC)
            except OSError:
                continue
            name = device_name(fd)
            if SOURCE_RE.match(name):
                try:
                    fcntl.ioctl(fd, EVIOCGRAB, 1)
                except OSError as e:
                    log("%s (%s): EVIOCGRAB failed: %s" % (p, name, e))
                    os.close(fd)
                    continue
                self.devs[fd] = (p, st.st_rdev, "kbd")
                log("grabbed %s (%s)" % (p, name))
            elif name == "touchPad" and self.want_touchpad:
                # the keyboard touchpad is shared with Android: not grabbed, only read
                self.devs[fd] = (p, st.st_rdev, "touchpad")
                log("keyboard touchpad: %s" % p)
            elif name.startswith("Lindroid-") and not name.startswith("Lindroid-Plektra"):
                self.devs[fd] = (p, st.st_rdev, "aux")
            else:
                self.ignored.add(ident)
                os.close(fd)
        # an event minor is reused after hotplug (e.g. a restarted Lindroid-Keyboard-N):
        # forget ignored nodes that are gone so the new device gets looked at
        self.ignored &= present

    def drop(self, fd):
        p, rdev, kind = self.devs.pop(fd)
        try:
            os.close(fd)
        except OSError:
            pass
        log("source gone: %s" % p)
        return kind

    def has_kbd(self):
        return any(v[2] == "kbd" for v in self.devs.values())


# ---------------------------------------------------------------- main

def session_home():
    uid = PlasmaUI.session_user()
    return plektra_config.user_home(uid) if uid is not None else None


def load_config(home=None):
    return plektra_config.load(home)


IM_KEYS = ("panel", "suggestions", "suggestion_count", "bar_leds", "bar_sym_grid")


def im_config(cfg):
    return {k: cfg.get(k) for k in IM_KEYS}


def output_codes():
    # no power/sleep (116, 142, 143), SysRq (99) and rfkill keys
    codes = set(range(1, 248)) - SLOT_AVOID - {KEY["KEY_POWER"], 142, 143, 99}
    codes |= set(SLOT_CODES)
    return codes


def ensure_xkb(cfg, tables):
    path = cfg["xkb_symbols"]
    slots = parse_xkb(path)
    need = [c for c in needed_codepoints(tables.all_texts()) if chr(c) not in slots]
    if need:
        log("%s lacks %d characters (%s): regenerating; kwin rereads it on reloadConfig"
            % (path, len(need), "".join(chr(c) for c in need[:20])))
        cps = sorted(set(needed_codepoints(tables.all_texts())) |
                     {ord(ch) for ch in slots})
        tmp = path + ".tmp"
        try:
            text = gen_xkb(cps)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            # kwin may read the file at any moment: write a copy and replace atomically
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(text)
            os.chmod(tmp, 0o644)
            os.replace(tmp, path)
            slots = parse_xkb(path)
        except (OSError, SystemExit) as e:
            log("could not write %s: %s" % (path, e))
            try:
                os.unlink(tmp)
            except OSError:
                pass
    return slots


CLIP_DIR = "/lindroid/clipboard"    # = /data/lindroid/mnt/clipboard on Android (LindroidUI)
BTN_LEFT, BTN_RIGHT, BTN_MIDDLE = 0x110, 0x111, 0x112
REL_X, REL_Y, REL_HWHEEL, REL_WHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0, 1, 6, 8, 11, 12


class TouchpadOut:
    """Touchpad output: a mouse (uinput Lindroid-Plektra-Mouse), arrows via the service keyboard."""

    def __init__(self, mouse, eng, props):
        self.m, self.eng, self.props = mouse, eng, props
        self.wacc = [0, 0]

    def rel(self, dx, dy):
        self.m.events([(EV_REL, REL_X, dx), (EV_REL, REL_Y, dy)])

    def wheel(self, v, h):
        evs = []
        for i, (val, hi, lo) in enumerate(((v, REL_WHEEL_HI_RES, REL_WHEEL),
                                           (h, REL_HWHEEL_HI_RES, REL_HWHEEL))):
            if not val:
                continue
            evs.append((EV_REL, hi, val))
            self.wacc[i] += val
            n = int(self.wacc[i] / 120)
            if n:
                self.wacc[i] -= n * 120
                evs.append((EV_REL, lo, n))
        if evs:
            self.m.events(evs)

    def click(self):
        self.m.events([(EV_KEY, BTN_LEFT, 1)])
        self.m.events([(EV_KEY, BTN_LEFT, 0)])

    def key(self, code):
        self.eng.out.tap(code)

    def in_field(self):
        return self.eng.in_field() and bool(self.eng.im and self.eng.im.active)

    def lindroid_focused(self):
        f = self.props.get("sys.titan2.focus", "")
        return not f or f.startswith("org.lindroid.ui")

    def has_suggestions(self):
        return self.eng.sugg > 0

    def pick_suggestion(self):
        if self.eng.im:
            self.eng.im.broadcast({"pick": 0}, only_active=True)


def run_as_user(cmd):
    """The "run" macro: a command as the Plasma session owner, in its environment, separate from the service."""
    uid = PlasmaUI.session_user()
    if uid is None:
        log("run macro: no Plasma session")
        return
    try:
        import pwd
        pw = pwd.getpwuid(uid)
        gid, home = pw.pw_gid, pw.pw_dir
    except Exception:
        gid, home = uid, "/"
    env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": home,
           "XDG_RUNTIME_DIR": "/run/user/%d" % uid, "WAYLAND_DISPLAY": "wayland-0",
           "DISPLAY": ":0", "DBUS_SESSION_BUS_ADDRESS": "unix:path=/run/user/%d/bus" % uid,
           "QT_QPA_PLATFORM": "wayland", "LD_PRELOAD": "/usr/lib/libtls-padding.so",
           "XDG_SESSION_TYPE": "wayland", "LANG": os.environ.get("LANG", "C.UTF-8")}
    try:
        subprocess.Popen(["setpriv", "--reuid=%d" % uid, "--regid=%d" % gid, "--init-groups",
                          "sh", "-c", cmd], env=env, cwd=home, start_new_session=True,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL)
    except OSError as e:
        log("run macro %r: %s" % (cmd, e))


class ClipBridge:
    """Android <-> Linux clipboard through the shared Lindroid directory. Android (LindroidUI) writes
    android.txt when the Lindroid window gains focus and reads linux.txt when it loses it."""

    def __init__(self, im):
        self.im = im
        self.last_android = None
        self.last_linux = None
        self.mtime = 0
        self.next = 0.0
        try:
            os.makedirs(CLIP_DIR, exist_ok=True)
            # LindroidUI writes here as uid 1000: the directory is shared but sticky: other users'
            # files cannot be replaced, and root does not follow others' symlinks (protected_symlinks).
            os.chmod(CLIP_DIR, 0o1777)
        except OSError as e:
            log("clipboard: %s: %s" % (CLIP_DIR, e))

    def poll(self):
        if time.monotonic() < self.next:
            return
        self.next = time.monotonic() + 1.0
        p = os.path.join(CLIP_DIR, "android.txt")
        try:
            st = os.stat(p)
        except OSError:
            return
        if st.st_mtime == self.mtime:
            return
        self.mtime = st.st_mtime
        try:
            # the service runs as root: without O_NOFOLLOW a symlink android.txt -> /etc/shadow
            # would hand someone else's file to the Linux clipboard
            fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(fd, encoding="utf-8") as f:
                text = f.read()
        except (OSError, UnicodeDecodeError):
            return
        if text and text != self.last_android and text != self.last_linux:
            self.last_android = text
            if self.im:
                self.im.broadcast({"clip_set": text})

    def from_linux(self, text):
        if not text or text == self.last_linux or text == self.last_android:
            self.last_linux = text
            return
        self.last_linux = text
        p = os.path.join(CLIP_DIR, "linux.txt")
        tmp = None
        try:
            # a new file with a random name (O_EXCL, no symlink following), then
            # an atomic replace: a planted linux.txt.tmp -> /etc/shadow no longer works
            fd, tmp = tempfile.mkstemp(prefix=".linux.", dir=CLIP_DIR)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(text)
            os.chmod(tmp, 0o644)
            os.replace(tmp, p)
            tmp = None
        except OSError as e:
            log("clipboard: %s" % e)
        finally:
            if tmp:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass


def _on_term(signum, frame):
    # systemctl stop / container shutdown: leave through the finally block of run()
    # (release keys, destroy the uinput devices, hand the touchpad and backlight back to Android)
    raise SystemExit(0)


def run():
    syslog.openlog("plektra-linux")
    signal.signal(signal.SIGTERM, _on_term)
    signal.signal(signal.SIGHUP, _on_term)
    home = session_home()
    cfg = load_config(home)
    cfg_stamp = plektra_config.stamp(home)
    next_cfg_check = 0.0
    tables = Tables(cfg)
    slots = ensure_xkb(cfg, tables)
    im = None
    try:
        im = ImServer()
    except OSError as e:
        log("socket for plektra-linux-im not created: %s" % e)
    ui = PlasmaUI(cfg, im)
    dev = UInput(output_codes())
    out = Output(dev.write, slots)
    if im:
        out.commit_hook = im.commit
    eng = Engine(cfg, tables, out, ui)
    eng.im = im
    eng.run_cmd = run_as_user
    if im:
        im.config(im_config(cfg))
    ui("state", what="init", state=eng.state(), page_map=None)
    src = Sources()
    props = plektra_android.Props()
    mouse = tp = None
    tp_cfg = cfg.get("touchpad") or {}
    if tp_cfg.get("enabled", True):
        try:
            mouse = UInput({BTN_LEFT, BTN_RIGHT, BTN_MIDDLE}, name="Lindroid-Plektra-Mouse",
                           product=0x000B, rels=(REL_X, REL_Y, REL_HWHEEL, REL_WHEEL,
                                                 REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES))
            tp = plektra_touchpad.Touchpad(eng.cfg, TouchpadOut(mouse, eng, props))
            eng.touchpad = tp
        except OSError as e:
            log("touchpad: %s" % e)
    src.want_touchpad = tp is not None
    if tp is not None:
        # Titan2Parts (TouchpadController) stays quiet while the Lindroid window is focused and this property = 1
        plektra_android.set_prop("sys.titan2.lindroid.touchpad", "1")
        if not props.get("sys.titan2.focus", ""):
            log("touchpad: sys.titan2.focus is not readable, working without the Android focus check")
    clip = ClipBridge(im) if cfg.get("clipboard_sync", True) else None
    # Test input (root only): "CODE VALUE" datagrams as Lindroid-Keyboard events.
    # Needed because a grabbed (EVIOCGRAB) evdev node cannot be written from outside.
    inj = None
    try:
        try:
            os.unlink(INJECT_SOCK)
        except FileNotFoundError:
            pass
        inj = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        inj.bind(INJECT_SOCK)
        os.chmod(INJECT_SOCK, 0o600)
    except OSError as e:
        log("inject.sock: %s" % e)
        inj = None
    log("plektra-linux %s: output %s, %d slots" % (VERSION, DEVICE_NAME, len(slots)))
    extra = ([inj] if inj else []) + ([im.wake_r] if im else [])
    try:
        while True:
            if time.monotonic() - src.last_scan > (1.0 if src.has_kbd() else 0.5):
                src.scan()
            timeout = 0.5
            dls = [d for d in (eng.next_deadline(), tp.next_deadline() if tp else None) if d]
            if dls:
                timeout = max(0.0, min(timeout, (min(dls) - now_ms()) / 1000.0))
            try:
                r, _, _ = select.select(list(src.devs) + extra, [], [], timeout)
            except InterruptedError:
                r = []
            if inj is not None and inj in r:
                r.remove(inj)
                try:
                    code, value = (int(x) for x in inj.recv(64).split()[:2])
                    eng.key(code, value)
                except (OSError, ValueError):
                    pass
            if im is not None and im.wake_r in r:
                r.remove(im.wake_r)
                try:
                    os.read(im.wake_r, 4096)
                except OSError:
                    pass
            while im is not None and not im.inbox.empty():
                m = im.inbox.get_nowait()
                if "reload" in m:
                    cfg_stamp = None                    # the settings app: reread now
                    next_cfg_check = 0.0
                try:
                    if "focus" in m:
                        f = m["focus"] or {}
                        eng.set_app(str(f.get("app") or ""), str(f.get("title") or ""))
                        if tp:
                            tp.cfg = eng.cfg
                    if "sugg" in m:
                        eng.sugg = int(m["sugg"] or 0)
                    if "ctx" in m:
                        eng.on_context(m["ctx"] or {})
                    if "clip_linux" in m and clip and isinstance(m["clip_linux"], str):
                        clip.from_linux(m["clip_linux"])
                except (AttributeError, TypeError, ValueError) as e:
                    log("bad message from plektra-linux-im: %r" % e)
            for fd in r:
                kind = src.devs[fd][2]
                try:
                    data = os.read(fd, EVENT_SIZE * 64)
                except BlockingIOError:
                    continue
                except OSError:
                    # keys held on the gone keyboard never get a release: drop them even if a
                    # new Lindroid-Keyboard has already been grabbed (LindroidUI restart)
                    if src.drop(fd) == "kbd":
                        eng.reset_all()
                    continue
                if not data:
                    continue
                for i in range(0, len(data) - EVENT_SIZE + 1, EVENT_SIZE):
                    _, _, typ, code, value = struct.unpack_from(EVENT_FMT, data, i)
                    if kind == "touchpad":
                        tp.on_raw(typ, code, value, now_ms())
                        continue
                    if kind == "aux":
                        if typ == EV_KEY:   # a touch (BTN_TOUCH) or a mouse button: the cursor moved
                            eng.reset_context()
                            if tp and code == 0x14A and not value:
                                tp.on_screen_touch_up(now_ms())
                        continue
                    if typ == EV_KEY:
                        eng.key(code, value)
            eng.tick()
            if tp:
                tp.tick(now_ms())
            if clip:
                clip.poll()
            if time.monotonic() >= next_cfg_check:
                # settings: the session owner's own file over /etc; changes apply at once
                next_cfg_check = time.monotonic() + 1.0
                h = session_home()
                st = plektra_config.stamp(h)
                if st != cfg_stamp or h != home:
                    home, cfg_stamp = h, st
                    try:
                        old = cfg
                        cfg = load_config(home)
                        if (bool((old.get("touchpad") or {}).get("enabled", True)) !=
                                bool((cfg.get("touchpad") or {}).get("enabled", True)) or
                                bool(old.get("clipboard_sync", True)) !=
                                bool(cfg.get("clipboard_sync", True))):
                            # devices/bridges are created at startup: restart (systemd Restart=always)
                            log("touchpad/clipboard settings changed, restarting the service")
                            raise SystemExit(0)
                        tables = Tables(cfg)
                        if ensure_xkb(cfg, tables) != out.slots:
                            out.slots = parse_xkb(cfg["xkb_symbols"])
                            ui("reload")
                        eng.reload(cfg, tables)
                        ui.cfg = cfg
                        if tp:
                            tp.cfg = eng.cfg
                        if im:
                            im.config(im_config(cfg))
                        log("settings reloaded (%s)" % (plektra_config.user_path(home)
                                                           if home else CONFIG_PATH))
                    except Exception as e:
                        log("error in the settings: %r" % e)
    finally:
        try:
            out.release_all()
        finally:
            dev.close()
            if mouse:
                mouse.close()
                plektra_android.set_prop("sys.titan2.lindroid.touchpad", "0")
            if cfg.get("kbd_light_prop"):
                # no stale CAPS/nav indication on the keyboard once the service is gone
                plektra_android.set_prop(cfg["kbd_light_prop"], "off")


def main(argv):
    if len(argv) > 1 and argv[1] == "--gen-xkb":
        cfg = load_config()
        if len(argv) > 3:
            cfg["mappings_dirs"] = [argv[3]]
        cps = needed_codepoints(Tables(cfg).all_texts())
        text = gen_xkb(cps)
        if len(argv) > 2 and argv[2] != "-":
            with open(argv[2], "w", encoding="utf-8") as f:
                f.write(text)
        else:
            sys.stdout.write(text)
        return 0
    if len(argv) > 1 and argv[1] in ("-h", "--help"):
        print("plektra-linux [--gen-xkb [FILE|-] [MAPPINGS_DIR]]")
        return 0
    run()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
