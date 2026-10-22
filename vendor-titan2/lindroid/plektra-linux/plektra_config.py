#!/usr/bin/python3
# SPDX-License-Identifier: GPL-3.0-or-later
# plektra-linux: settings. Defaults (as in Plektra) + the system file
# /etc/plektra-linux/config.json + the user file ~/.config/plektra-linux/config.json
# of the Plasma session owner (written by the "Plektra (Titan 2 keyboard)" app).
# The service (root) and the bar watch the files and apply changes at once.
#
# Command line (for the settings app, run as the user):
#   plektra_config.py get              -> JSON {defaults, system, user, effective, paths}
#   plektra_config.py set BASE64JSON   -> merge into the user file (null = delete the key)
#   plektra_config.py reset [KEY...]   -> delete keys (all without arguments)
#   plektra_config.py getmap NAME      -> a mappings table (user or system)
#   plektra_config.py setmap NAME BASE64JSON | resetmap NAME

import base64
import copy
import json
import os
import sys

LIB_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONFIG = {
    "long_press_ms": 300,            # Plektra DEFAULT_LONG_PRESS_THRESHOLD
    "double_tap_ms": 500,            # Plektra DOUBLE_TAP_THRESHOLD
    "long_press_mode": "alt",        # alt | shift | off (Plektra DEFAULT_LONG_PRESS_MODIFIER = alt)
    "sym_pages": ["device", "emoji", "symbols"],  # Plektra SymPagesConfig() default
    "sym_page_order": ["device", "emoji", "symbols"],   # order in the settings (on and off)
    "sym_auto_close": True,          # DEFAULT_SYM_AUTO_CLOSE
    "alt_binding": "device",         # AltModifierBinding.DeviceSym
    "nav_mode": True,                # DEFAULT_NAV_MODE_ENABLED
    "nav_mode_ctrl_hold": False,     # DEFAULT_NAV_MODE_CTRL_HOLD_ENABLED
    "clear_alt_on_space": True,      # DEFAULT_CLEAR_ALT_ON_SPACE
    "alt_latch_stays_on_space": False,
    "ctrl_space_layout_switch": True,  # DEFAULT_CTRL_SPACE_LAYOUT_SWITCH
    "double_space_period": True,     # DEFAULT_DOUBLE_SPACE_TO_PERIOD
    "autocap_after_period": True,    # DEFAULT_AUTO_CAPITALIZE_AFTER_PERIOD
    "autocap_first_letter": True,    # DEFAULT_AUTO_CAPITALIZE_FIRST_LETTER (needs the bar: field text)
    "ctrl_latch_stays_on_space": False,
    "shift_backspace_delete": False,  # DEFAULT_SHIFT_BACKSPACE_DELETE
    "alt_backspace_delete": False,    # DEFAULT_ALT_BACKSPACE_DELETE
    "bounce_keys_enabled": False,     # Accessibility: debounce
    "bounce_keys_delay_ms": 80,
    "bounce_keys_chars": True,
    "bounce_keys_modifiers": False,
    "bounce_keys_space": True,
    "bounce_keys_enter": True,
    "bounce_keys_backspace": True,
    "overlapping_keys_enabled": False,
    "shift_tap_latches": False,      # DEFAULT_MODIFIER_TAP_LATCHES
    "alt_tap_latches": False,
    "ctrl_tap_latches": False,
    # Linux-specific
    "back_key": "KEY_ESC",           # what "Back" (158) does when the SYM page is closed
    "recents_key": ["KEY_LEFTMETA", "KEY_W"],   # 580 (never reaches Linux: Android opens "Recents" itself)
    "side_key_1": "KEY_PROG1",       # 249 (Func1), usually consumed by Titan2Parts on the Android side
    "side_key_2": "KEY_PROG2",       # 250 (Func2)
    "layout_switch_keys": ["KEY_LEFTMETA", "KEY_LEFTALT", "KEY_K"],  # KDE: next layout
    "ui": "plasma",                  # plasma | none
    "osd_oneshot": False,            # show the OSD for one-shot modifiers too
    "fn_tap": "auto",                # auto: in a field the Plektra Ctrl layout, outside a field Ctrl; map; ctrl
    "sym_tap": None,                 # None: SYM pages; otherwise key(s), e.g. "KEY_ESC"
    "alt_space": None,               # None: space; otherwise key(s), e.g. "KEY_TAB"
    "keys": {},                      # full replacement of physical keys: {"FN": "KEY_TAB"}
    "long_space_ms": 500,            # long space switches the KDE layout; 0 disables it
    "macros": {},                    # {"SYM+D": {"text": "{date}"}, "FN+SYM+T": {"run": "konsole"}}
    "profiles": [
        {"name": "terminal",
         "apps": ["org.kde.konsole", "konsole", "org.kde.yakuake", "yakuake", "xterm", "uxterm",
                  "foot", "footclient", "kitty", "Alacritty", "alacritty", "org.gnome.Terminal",
                  "gnome-terminal-server", "org.gnome.Console", "kgx", "com.mitchellh.ghostty",
                  "org.wezfurlong.wezterm", "qterminal", "xfce4-terminal", "lxterminal",
                  "terminator", "st-256color", "urxvt", "cool-retro-term"],
         "overrides": {"fn_tap": "ctrl", "sym_tap": "KEY_ESC", "alt_space": "KEY_TAB",
                       "sym_pages": ["symbols", "device", "emoji"],
                       "double_space_period": False, "autocap_after_period": False,
                       "autocap_first_letter": False}},
    ],
    "touchpad": {"enabled": True, "mode": "scroll", "reverse": False, "scroll_speed": 1.0,
                 "mouse_speed": 1.0, "inertia": True},
    "clipboard_sync": True,
    # the bar (plektra-linux-im)
    "panel": "auto",                 # auto (environment: bottom) | bottom | cursor
    "suggestions": True,             # word suggestions (Plektra: experimental_suggestions_enabled)
    "suggestion_count": 3,
    "bar_leds": True,                # modifier LED bar (modifier_indicator_mode)
    "bar_sym_grid": True,            # grid of the open SYM page
    "kbd_light_prop": "sys.titan2.lindroid.kbd",   # backlight indicator (Titan2Parts)
    "mappings_dirs": ["/etc/plektra-linux/mappings", os.path.join(LIB_DIR, "mappings")],
    "xkb_symbols": "/etc/xkb/symbols/titan2",
    "state_file": "/run/plektra-linux/state.json",
}


SYSTEM_PATH = "/etc/plektra-linux/config.json"
USER_REL = ".config/plektra-linux/config.json"
MAP_NAMES = ("device_sym_key_mappings.json", "sym_key_mappings.json",
             "sym_key_mappings_page2.json", "ctrl_key_mappings.json",
             "sym_key_mappings_uppercase.json", "sym_key_mappings_page2_uppercase.json")
DEEP = ("touchpad",)            # dictionaries merged by key


def user_home(uid=None):
    if uid is None and os.environ.get("HOME"):
        return os.environ["HOME"]
    try:
        import pwd
        return pwd.getpwuid(os.getuid() if uid is None else uid).pw_dir
    except Exception:
        return os.environ.get("HOME", "/root")


def user_path(home):
    return os.path.join(home, USER_REL)


def user_map_dir(home):
    return os.path.join(home, ".config/plektra-linux/mappings")


def _read(path):
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        return {k: v for k, v in d.items() if not k.startswith("_")} if isinstance(d, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as e:
        sys.stderr.write("plektra-linux: %s: %s\n" % (path, e))
        return {}


def merge(base, over):
    out = dict(base)
    for k, v in over.items():
        if k in DEEP and isinstance(v, dict) and isinstance(out.get(k), dict):
            d = dict(out[k])
            d.update(v)
            out[k] = d
        else:
            out[k] = v
    return out


# keys whose value must keep the type of the default (a hand-edited file with a wrong type
# would otherwise break the service on every key press or at startup)
STR_KEYS = ("long_press_mode", "alt_binding", "ui", "fn_tap", "panel", "kbd_light_prop",
            "xkb_symbols", "state_file")
LIST_KEYS = ("sym_pages", "sym_page_order", "mappings_dirs", "profiles")
DICT_KEYS = ("keys", "macros", "touchpad")


def _number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def sanitize(cfg):
    """Values of a wrong type fall back to the default; broken profiles/macros are dropped."""
    for k, dv in DEFAULT_CONFIG.items():
        v = cfg.get(k)
        if _number(dv) and not _number(v):
            bad = True
        elif k in STR_KEYS and not (isinstance(v, str) or (v is None and dv is None)):
            bad = True
        elif k in LIST_KEYS and not isinstance(v, list):
            bad = True
        elif k in DICT_KEYS and not isinstance(v, dict):
            bad = True
        else:
            bad = False
        if bad:
            if k in cfg:
                sys.stderr.write("plektra-linux: %s: wrong value %r, using the default\n" % (k, v))
            cfg[k] = copy.deepcopy(dv)
    profiles = []
    for p in cfg["profiles"]:
        if not isinstance(p, dict):
            continue
        p = dict(p)
        if not isinstance(p.get("apps"), list):
            p["apps"] = []
        if not isinstance(p.get("overrides"), dict):
            p["overrides"] = {}
        profiles.append(p)
    cfg["profiles"] = profiles
    cfg["macros"] = {k: v for k, v in cfg["macros"].items() if isinstance(v, dict)}
    return cfg


def load(home=None):
    """Effective settings: defaults <- /etc <- user (home)."""
    cfg = copy.deepcopy(DEFAULT_CONFIG)
    cfg = merge(cfg, _read(SYSTEM_PATH))
    if home:
        cfg = merge(cfg, _read(user_path(home)))
    cfg = sanitize(cfg)
    if home:
        cfg["mappings_dirs"] = [user_map_dir(home)] + list(cfg["mappings_dirs"])
    return cfg


def stamp(home=None):
    """Fingerprint of the settings files for change tracking (mtime/size)."""
    out = []
    paths = [SYSTEM_PATH] + ([user_path(home)] if home else [])
    dirs = ["/etc/plektra-linux/mappings"] + ([user_map_dir(home)] if home else [])
    for p in paths + [os.path.join(d, n) for d in dirs for n in MAP_NAMES]:
        try:
            st = os.stat(p)
            out.append((p, st.st_mtime_ns, st.st_size))
        except OSError:
            pass
    return tuple(out)


def _write_user(d, home):
    p = user_path(home)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2, sort_keys=True)
    os.replace(tmp, p)


def main(argv):
    home = user_home()
    cmd = argv[1] if len(argv) > 1 else "get"
    # the settings app passes JSON in hex: safe for the command line
    if cmd in ("sethex", "setmaphex"):
        argv = list(argv)
        argv[-1] = base64.b64encode(bytes.fromhex(argv[-1])).decode()
        cmd = cmd[:-3]
    if cmd in ("getmap", "setmap", "resetmap") and (len(argv) < 3 or argv[2] not in MAP_NAMES):
        sys.stderr.write("plektra-linux: unknown table\n")
        return 2
    if cmd == "get":
        print(json.dumps({"defaults": DEFAULT_CONFIG, "system": _read(SYSTEM_PATH),
                          "user": _read(user_path(home)), "effective": load(home),
                          "paths": {"user": user_path(home), "system": SYSTEM_PATH}},
                         ensure_ascii=False))
    elif cmd == "set":
        patch = json.loads(base64.b64decode(argv[2]).decode("utf-8"))
        d = _read(user_path(home))
        for k, v in patch.items():
            if v is None:
                d.pop(k, None)
            elif k in DEEP and isinstance(v, dict):
                cur = dict(d.get(k) or {})
                for kk, vv in v.items():
                    if vv is None:
                        cur.pop(kk, None)
                    else:
                        cur[kk] = vv
                d[k] = cur
            else:
                d[k] = v
        _write_user(d, home)
        print("ok")
    elif cmd == "reset":
        d = _read(user_path(home))
        for k in (argv[2:] or list(d)):
            d.pop(k, None)
        _write_user(d, home)
        print("ok")
    elif cmd == "getmap":
        name = argv[2]
        for dd in [user_map_dir(home)] + DEFAULT_CONFIG["mappings_dirs"]:
            p = os.path.join(dd, name)
            if os.path.isfile(p):
                with open(p, encoding="utf-8") as f:
                    m = json.load(f)
                print(json.dumps({"source": p, "user": dd == user_map_dir(home),
                                  "mappings": m.get("mappings", {})}, ensure_ascii=False))
                return 0
        print(json.dumps({"source": None, "user": False, "mappings": {}}))
    elif cmd == "setmap":
        name = argv[2]
        m = json.loads(base64.b64decode(argv[3]).decode("utf-8"))
        d = user_map_dir(home)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, name + ".tmp"), "w", encoding="utf-8") as f:
            json.dump({"mappings": m}, f, ensure_ascii=False, indent=2)
        os.replace(os.path.join(d, name + ".tmp"), os.path.join(d, name))
        print("ok")
    elif cmd == "resetmap":
        name = argv[2]
        try:
            os.unlink(os.path.join(user_map_dir(home), name))
        except FileNotFoundError:
            pass
        print("ok")
    else:
        sys.stderr.write(__doc__ or "")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
