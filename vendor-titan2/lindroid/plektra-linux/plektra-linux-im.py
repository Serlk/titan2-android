#!/usr/bin/python3
# SPDX-License-Identifier: GPL-3.0-or-later
#
# plektra-linux-im: the Plektra "keyboard bar" for KDE Plasma (kwin_wayland) in Lindroid.
# A Wayland input method (zwp_input_method_v1 + zwp_input_panel_v1) that kwin itself
# starts (kwinrc [Wayland] InputMethod=.../org.plektra.linux.im.desktop) instead of maliit.
# kwin activates it only when a text field is focused, so the bar is visible, as in
# Plektra, only while typing. What it shows (Plektra LedStatusView / StatusBarController):
#  * a modifier indicator bar at the bottom: Shift | Sym | ... | Ctrl(Fn) | Alt, gray is off,
#    blue is one-shot/held, orange is locked (CAPS, navigation mode, Alt-lock);
#  * with a SYM page open, the Titan 2 grid (3 rows of 10) with the page characters and the key
#    letter in the corner, on a black background, as in Plektra.
# Also: characters typed by plektra-linux (Alt layer digits, symbols, emoji) go into the field
# via commit_string, without xkb slots and regardless of the layout.
# Link to the plektra-linux service: /run/plektra-linux/ui.sock (JSON lines).
# The Wayland client is pure Python (the rootfs has no pywayland/PyQt).
# Logic and look are a port of Plektra (https://github.com/pkb-rocks/plektra, GPL-3.0).

import bisect
import json
import os
import re
import select
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import plektra_render as R  # noqa: E402
try:
    import plektra_dbus  # noqa: E402
except ImportError:
    plektra_dbus = None

HERE = os.path.dirname(os.path.abspath(__file__))

UI_SOCK = os.environ.get("PLEKTRA_UI_SOCK", "/run/plektra-linux/ui.sock")
ROWS = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"]

# Plektra: LedStatusView / KeyboardThemeColors (Plektra Dark)
LED_OFF = 0xFF26292D        # Plektra: argb(100,17,17,17) over the background; opaque, slightly lighter
LED_ACTIVE = 0xFF6496FF     # Color.rgb(100, 150, 255)
LED_LOCKED = 0xFFF76300     # Color.rgb(247, 99, 0)
BG = 0xFF000000
KEY_BG = 0xFF15191D
KEY_BORDER = 0xFF454B52
TEXT = 0xFFE8EAED
LABEL = 0xFF9AA0A6
# ModifierLedLayouts.DEFAULT
LED_SEGMENTS = [("shift", 0.000, 0.164), ("sym", 0.168, 0.164), ("ctrl", 0.672, 0.164),
                ("alt", 0.840, 0.160)]


def log(msg):
    print("plektra-linux-im: " + msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- Wayland wire protocol

class Wl:
    def __init__(self):
        fd = os.environ.get("WAYLAND_SOCKET")
        if fd:
            self.sock = socket.socket(fileno=int(fd))
            os.environ.pop("WAYLAND_SOCKET", None)
        else:
            path = os.environ.get("WAYLAND_DISPLAY", "wayland-0")
            if not path.startswith("/"):
                path = os.path.join(os.environ["XDG_RUNTIME_DIR"], path)
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.connect(path)
        self.sock.setblocking(True)
        self.next_id = 2
        self.objs = {1: "wl_display"}
        self.handlers = {}
        self.rbuf = b""
        self.out = []
        self.out_fds = []

    def new(self, iface):
        i = self.next_id
        self.next_id += 1
        self.objs[i] = iface
        return i

    @staticmethod
    def _str(s):
        if s is None:
            return struct.pack("<I", 0)
        b = s.encode() + b"\0"
        return struct.pack("<I", len(b)) + b + b"\0" * ((4 - len(b) % 4) % 4)

    def req(self, obj, op, fmt="", *args, fd=None):
        body = b""
        for f, a in zip(fmt, args):
            if f in "uon":
                body += struct.pack("<I", a or 0)
            elif f == "i":
                body += struct.pack("<i", a)
            elif f == "s":
                body += self._str(a)
        self.out.append(struct.pack("<II", obj, ((8 + len(body)) << 16) | op) + body)
        if fd is not None:
            self.out_fds.append(fd)

    def flush(self):
        if not self.out:
            return
        data = b"".join(self.out)
        self.out = []
        if self.out_fds:
            anc = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, struct.pack("%di" % len(self.out_fds),
                                                                       *self.out_fds))]
            self.sock.sendmsg([data], anc)
            self.out_fds = []
        else:
            self.sock.sendall(data)

    def read(self):
        data, anc, _, _ = self.sock.recvmsg(65536, socket.CMSG_SPACE(16 * 4))
        for lvl, typ, d in anc:
            if lvl == socket.SOL_SOCKET and typ == socket.SCM_RIGHTS:
                for k in range(len(d) // 4):
                    os.close(struct.unpack_from("i", d, 4 * k)[0])   # we do not need the fds
        if not data:
            raise EOFError("wayland: connection closed")
        self.rbuf += data
        while len(self.rbuf) >= 8:
            obj, so = struct.unpack_from("<II", self.rbuf)
            size, op = so >> 16, so & 0xFFFF
            if len(self.rbuf) < size:
                break
            body = self.rbuf[8:size]
            self.rbuf = self.rbuf[size:]
            h = self.handlers.get(obj)
            if h:
                try:
                    h(op, body)
                except SystemExit:
                    raise
                except Exception as e:      # do not crash: kwin does not restart the IM after exit
                    import traceback
                    log("error in a handler: %r\n%s" % (e, traceback.format_exc()))

    @staticmethod
    def parse(fmt, body):
        out = []
        p = 0
        for f in fmt:
            if f in "uon":
                out.append(struct.unpack_from("<I", body, p)[0])
                p += 4
            elif f == "i":
                out.append(struct.unpack_from("<i", body, p)[0])
                p += 4
            elif f == "s":
                ln = struct.unpack_from("<I", body, p)[0]
                p += 4
                out.append(body[p:p + ln - 1].decode("utf-8", "replace") if ln else None)
                p += (ln + 3) & ~3
        return out

    def roundtrip(self):
        cb = self.new("wl_callback")
        done = []
        self.handlers[cb] = lambda op, body: done.append(1)
        self.req(1, 0, "n", cb)            # wl_display.sync
        self.flush()
        while not done:
            self.read()


# ---------------------------------------------------------------- input method

class PlektraIM:
    def __init__(self):
        self.wl = Wl()
        self.globals = {}
        self.out_info = {"w": 1440, "h": 1440, "scale": 1}
        self.ctx = None
        self.serial = 0
        self.state = {}
        self.page = None
        self.fscale = None          # wp_fractional_scale: scale * 120
        self.pref_scale = None
        self.buffers = []
        self.r = R.TextRenderer()
        self.ui = None
        self.ui_buf = b""
        self.last_ui_try = 0.0
        self.toplevel = self.kwin_shows_toplevel()
        self.drawn_key = None
        self.sugg = []                  # suggestions on the bar
        self.word = ""                  # the word before the cursor (from surrounding_text)
        self.dicts = {}
        self.bus = None
        self.last_clip = None
        self.cfg = {}
        self.fs = None
        self.viewport = None

    @staticmethod
    def kwin_shows_toplevel():
        """By default as Plektra on Android: a full-width bar at the bottom of the screen, windows
        move above it. kwin shows such a panel without touching the field only with
        KWIN_IM_SHOW_ALWAYS=1 (install.sh writes it into the session and login screen environment).
        PLEKTRA_IM_PANEL=cursor: an "overlay" panel under the text cursor."""
        return os.environ.get("PLEKTRA_IM_PANEL", "bottom") != "cursor"

    # -- registration
    def setup(self):
        wl = self.wl
        reg = wl.new("wl_registry")
        wl.handlers[1] = self.on_display

        def on_global(op, b):
            if op == 0:
                name, iface, ver = Wl.parse("usu", b)
                self.globals[iface] = (name, ver)
        wl.handlers[reg] = on_global
        wl.req(1, 1, "n", reg)             # get_registry
        wl.roundtrip()
        need = ["wl_compositor", "wl_shm", "zwp_input_method_v1", "zwp_input_panel_v1"]
        miss = [n for n in need if n not in self.globals]
        if miss:
            raise SystemExit("plektra-linux-im: no %s (kwin did not grant input method rights?)" % miss)

        def bind(iface, maxv):
            name, ver = self.globals[iface]
            i = wl.new(iface)
            v = min(ver, maxv)
            wl.req(reg, 0, "usun", name, iface, v, i)
            return i, v
        self.compositor, self.comp_v = bind("wl_compositor", 6)
        self.shm, _ = bind("wl_shm", 1)
        self.im, _ = bind("zwp_input_method_v1", 1)
        self.panel, _ = bind("zwp_input_panel_v1", 1)
        self.output = None
        if "wl_output" in self.globals:
            self.output, _ = bind("wl_output", 2)
            wl.handlers[self.output] = self.on_output
        self.viewporter = bind("wp_viewporter", 1)[0] if "wp_viewporter" in self.globals else None
        self.fsm = (bind("wp_fractional_scale_manager_v1", 1)[0]
                    if "wp_fractional_scale_manager_v1" in self.globals else None)
        wl.handlers[self.im] = self.on_im
        wl.roundtrip()
        self.make_surface()
        self.redraw()                   # activate may have arrived before the surface was created
        log("ready (panel: %s)" % ("bottom of the screen" if self.toplevel else "under the cursor"))

    def make_surface(self):
        """The panel surface. Switching "bottom/under the cursor" needs a new surface
        (zwp_input_panel_surface_v1 has no request to change the mode)."""
        wl = self.wl
        if getattr(self, "surface", None):
            for obj in (getattr(self, "fs", None), self.viewport):
                if obj:
                    wl.req(obj, 0)                          # destroy
            wl.req(self.surface, 0)                         # wl_surface.destroy
            self.fs = None
        self.drawn_key = None
        self.surface = wl.new("wl_surface")
        wl.req(self.compositor, 0, "n", self.surface)
        wl.handlers[self.surface] = self.on_surface
        self.viewport = None
        if self.viewporter:
            self.viewport = wl.new("wp_viewport")
            wl.req(self.viewporter, 1, "no", self.viewport, self.surface)
        if self.fsm:
            fs = self.fs = wl.new("wp_fractional_scale_v1")
            wl.req(self.fsm, 1, "no", fs, self.surface)
            wl.handlers[fs] = self.on_fscale
        self.psurf = wl.new("zwp_input_panel_surface_v1")
        wl.req(self.panel, 0, "no", self.psurf, self.surface)
        if self.toplevel and self.output:
            wl.req(self.psurf, 0, "ou", self.output, 0)    # set_toplevel(output, center_bottom)
        else:
            wl.req(self.psurf, 1)                           # set_overlay_panel
        wl.flush()

    def on_display(self, op, body):
        if op == 0:
            obj, code, msg = Wl.parse("uus", body)
            raise SystemExit("wayland error obj=%d code=%d: %s" % (obj, code, msg))

    def on_output(self, op, body):
        if op == 1:
            flags, w, h, _ = Wl.parse("uiii", body)
            if flags & 1:
                self.out_info["w"], self.out_info["h"] = w, h
        elif op == 3:
            self.out_info["scale"] = Wl.parse("i", body)[0]

    def on_surface(self, op, body):
        if op == 2 and self.comp_v >= 6:      # preferred_buffer_scale
            self.pref_scale = Wl.parse("i", body)[0]
            self.redraw()

    def on_fscale(self, op, body):
        if op == 0:
            self.fscale = Wl.parse("u", body)[0]
            self.redraw()

    def on_im(self, op, body):
        if op == 0:                                     # activate(new context)
            cid = Wl.parse("n", body)[0]
            self.wl.objs[cid] = "zwp_input_method_context_v1"
            self.wl.handlers[cid] = self.on_ctx
            self.ctx = cid
            self.serial = 0
            log("activate: a text field is focused")
            self.ui_send({"active": True})
            self.drawn_key = None
            self.redraw()
        elif op == 1:                                   # deactivate(context)
            cid = Wl.parse("o", body)[0]
            if cid == self.ctx:
                log("deactivate")
                self.ctx = None
                self.ui_send({"active": False})
                self.unmap()
            self.wl.req(cid, 0)                         # context.destroy
            self.wl.flush()

    def on_ctx(self, op, body):
        if op == 4:                                     # commit_state(serial)
            self.serial = Wl.parse("u", body)[0]
        elif op == 0:                                   # surrounding_text(text, cursor, anchor)
            text, cursor, anchor = Wl.parse("suu", body)
            b = (text or "").encode()
            before = b[:cursor].decode("utf-8", "ignore")
            m = re.search(r"[^\W\d_][\w'’-]*$", before)
            self.set_word(m.group(0) if m and cursor == anchor else "")
            # start of a sentence (for Plektra auto-capitalization): an empty field or after . ! ?
            ss = cursor == anchor and (not before.strip() or bool(re.search(r"[.!?…]\s+$", before)))
            if ss != getattr(self, "sent_start", None):
                self.sent_start = ss
                self.ui_send({"ctx": {"sentence_start": ss}})
        elif op == 1:                                   # reset
            self.set_word("")

    def apply_config(self, cfg):
        old = self.cfg
        self.cfg = cfg
        panel = cfg.get("panel")
        top = self.kwin_shows_toplevel() if panel in (None, "auto") else panel == "bottom"
        if top != self.toplevel and hasattr(self, "psurf"):
            self.toplevel = top
            self.make_surface()
            log("panel: %s" % ("bottom of the screen" if top else "under the cursor"))
        else:
            self.toplevel = top
        if old.get("suggestions") != cfg.get("suggestions") or \
                old.get("suggestion_count") != cfg.get("suggestion_count"):
            w, self.word = self.word, None
            self.set_word(w or "")
        self.drawn_key = None
        self.redraw()

    # -- suggestions (Plektra dictionaries, no autocorrect)
    def dict_for(self, word):
        lang = "ru" if re.search("[а-яёА-ЯЁ]", word) else "en"
        d = self.dicts.get(lang)
        if d is None:
            keys, items = [], []
            try:
                with open(os.path.join(HERE, "dict", lang + ".tsv"), encoding="utf-8") as f:
                    for line in f:
                        if line.startswith("#"):
                            continue
                        w, _, fr = line.rstrip("\n").partition("\t")
                        keys.append(w.lower())
                        items.append((w, int(fr or 0)))
            except OSError:
                pass
            d = self.dicts[lang] = (keys, items)
        return d

    def suggest(self, word):
        if len(word) < 1 or os.environ.get("PLEKTRA_NO_SUGGEST") or \
                self.cfg.get("suggestions") is False:
            return []
        count = max(1, min(3, int(self.cfg.get("suggestion_count") or 3)))
        keys, items = self.dict_for(word)
        lw = word.lower()
        i = bisect.bisect_left(keys, lw)
        cand = []
        while i < len(keys) and keys[i].startswith(lw) and len(cand) < 3000:
            if keys[i] != lw:
                cand.append(items[i])
            i += 1
        cand.sort(key=lambda e: -e[1])
        out = []
        for w, _ in cand[:count]:
            if word[:1].isupper():
                w = w[:1].upper() + w[1:]
            out.append(w)
        return out

    def set_word(self, word):
        if word == self.word:
            return
        self.word = word
        sugg = self.suggest(word)
        if sugg != self.sugg:
            self.sugg = sugg
            self.ui_send({"sugg": len(sugg)})
            self.redraw()

    def pick(self, i):
        if self.ctx is None or i >= len(self.sugg):
            return
        n = len(self.word.encode())
        if n:
            self.wl.req(self.ctx, 5, "iu", -n, n)       # delete_surrounding_text(index, length)
        self.wl.req(self.ctx, 1, "us", self.serial, self.sugg[i] + " ")
        self.wl.flush()
        self.set_word("")

    # -- D-Bus: the focused window (KWin script) and the clipboard (Klipper)
    def dbus_setup(self):
        if plektra_dbus is None:
            return
        try:
            b = plektra_dbus.Bus()
        except OSError as e:
            log("D-Bus unavailable: %s" % e)
            return
        self.bus = b
        try:
            b.request_name("org.plektra.Linux")
        except OSError as e:
            # e.g. a second bar instance owns it: the bar still works, only app profiles do not
            log("D-Bus name org.plektra.Linux: %s" % e)
        b.methods[("/org/plektra/Linux", "org.plektra.Linux", "ActiveWindow")] = self.on_window
        try:
            b.call("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", "unloadScript", "s",
                   ("plektra-focus",))
            sid = b.call("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", "loadScript",
                         "ss", (os.path.join(HERE, "kwin-focus.js"), "plektra-focus"))[0]
            b.call("org.kde.KWin", "/Scripting/Script%d" % sid, "org.kde.kwin.Script", "run")
        except OSError as e:
            log("KWin script not loaded: %s" % e)
        try:
            b.add_match("type='signal',interface='org.kde.klipper.klipper',"
                        "member='clipboardHistoryUpdated'")
            b.signals.append(("org.kde.klipper.klipper", "clipboardHistoryUpdated", self.on_clip))
        except OSError:
            pass

    def on_window(self, app, title):
        self.ui_send({"focus": {"app": app, "title": title}})
        return None

    def on_clip(self, *a):
        try:
            text = self.bus.call("org.kde.klipper", "/klipper", "org.kde.klipper.klipper",
                                 "getClipboardContents")[0]
        except OSError:
            return
        if text and text != self.last_clip:
            self.last_clip = text
            self.ui_send({"clip_linux": text})

    def set_clip(self, text):
        if not self.bus or not text or text == self.last_clip:
            return
        self.last_clip = text
        try:
            self.bus.call("org.kde.klipper", "/klipper", "org.kde.klipper.klipper",
                          "setClipboardContents", "s", (text,))
        except OSError as e:
            log("Klipper: %s" % e)

    # -- text output
    def commit(self, text):
        if self.ctx is None or not text:
            return False
        self.wl.req(self.ctx, 1, "us", self.serial, text)   # commit_string
        self.wl.flush()
        return True

    # -- drawing
    def scale(self):
        if self.fscale:
            return self.fscale / 120.0
        if self.pref_scale:
            return float(self.pref_scale)
        return float(self.out_info.get("scale") or 1)

    def layout(self):
        s = self.scale()
        out_w = int(self.out_info["w"] / max(1.0, float(self.out_info.get("scale") or 1))) \
            if not self.fscale else int(self.out_info["w"] * 120 / self.fscale)
        sym = self.page is not None and self.state.get("sym_page") in (1, 2, 5) and \
            self.cfg.get("bar_sym_grid", True) is not False
        if self.toplevel:
            lw = out_w
        else:
            lw = min(out_w, 520 if sym else 300)
        strip = 7 if self.cfg.get("bar_leds", True) is not False else 0
        grid_h = (3 * 40 + 2 * 4 + 4 + 12) if sym else (32 if self.sugg else 0)
        if not self.toplevel and not sym and self.sugg:
            lw = min(out_w, 420)
        return lw, grid_h + strip, s, sym, grid_h

    def redraw(self):
        if self.ctx is None or not hasattr(self, "psurf"):
            return
        lw, lh, s, sym, grid_h = self.layout()
        if lh <= 0:
            if self.drawn_key is not None:
                self.unmap()
            return
        key = (lw, lh, s, tuple(self.sugg), json.dumps(self.state, sort_keys=True),
               json.dumps(self.page, sort_keys=True) if sym else "")
        if key == self.drawn_key:
            return
        self.drawn_key = key
        bw, bh = max(1, int(round(lw * s))), max(1, int(round(lh * s)))
        cv = R.Canvas(bw, bh)
        cv.fill(BG)
        if sym:
            self.draw_grid(cv, s, bw, int(grid_h * s))
        elif self.sugg:
            self.draw_sugg(cv, s, bw, int(grid_h * s))
        if self.cfg.get("bar_leds", True) is not False:
            self.draw_leds(cv, s, bw, int(grid_h * s), bh)
        self.attach(cv, lw, lh, s)

    def draw_leds(self, cv, s, bw, y0, bh):
        st = self.state
        def color(kind):
            if kind == "sym":
                p = st.get("sym_page", 0)
                if p == 2:
                    return LED_LOCKED
                if p or st.get("sym_pressed"):
                    return LED_ACTIVE
                return LED_OFF
            if kind == "shift":
                if st.get("shift") == "caps":
                    return LED_LOCKED
                return LED_ACTIVE if (st.get("shift") == "one_shot" or st.get("autocap")
                                      or st.get("shift_pressed")) else LED_OFF
            v = st.get(kind)
            if v == "latch":
                return LED_LOCKED
            return LED_ACTIVE if (v == "one_shot" or st.get(kind + "_pressed")) else LED_OFF
        top = y0 + int(1 * s)
        for kind, x, w in LED_SEGMENTS:
            cv.rect(x * bw, top, w * bw, bh - top, color(kind), radius=3 * s)

    def draw_sugg(self, cv, s, bw, gh):
        """The Plektra suggestion row: three cells, the number (Fn+1/2/3 = W/E/R) small on the left."""
        n = 3
        pad = 3 * s
        cw = (bw - 2 * pad - (n - 1) * pad) / n
        for i, w in enumerate(self.sugg[:n]):
            x = pad + i * (cw + pad)
            cv.rect(x, pad, cw, gh - 2 * pad, KEY_BG, radius=5 * s)
            if self.r.font:
                px = int(15 * s)
                self.r.draw_text(cv, str(i + 1), x + 6 * s, pad + (gh - 2 * pad) * 0.7, int(10 * s),
                                 LABEL)
                width = sum((self.r.font.glyph(c, px) or (0, 0, b"", 0, 0, px * 0.5))[5] for c in w)
                tx = x + max(16 * s, (cw - width) / 2)
                self.r.draw_text(cv, w, tx, pad + (gh - 2 * pad) * 0.68, px, TEXT)

    def draw_grid(self, cv, s, bw, gh):
        pad, sp, kh = 4 * s, 4 * s, 40 * s
        kw = (bw - 2 * pad - 9 * sp) / 10
        page = self.state.get("sym_page")
        big = kh * (0.75 if page == 1 else 0.5)
        layout = [list(ROWS[0]), list(ROWS[1]) + [None],
                  list("ZXCV") + [None, None] + list("BNM") + [None]]
        for ri, row in enumerate(layout):
            y = pad + ri * (kh + sp)
            for ci, letter in enumerate(row):
                if letter is None:
                    continue
                x = pad + ci * (kw + sp)
                cv.rect(x, y, kw, kh, KEY_BORDER, radius=6 * s)
                cv.rect(x + s, y + s, kw - 2 * s, kh - 2 * s, KEY_BG, radius=5 * s)
                sym = (self.page or {}).get(letter)
                if sym:
                    self.r.draw_centered(cv, sym, x + kw / 2, y + kh / 2, int(big), TEXT)
                if self.r.font:
                    lp = int(12 * s * 0.85)
                    g = self.r.font.glyph(letter, lp)
                    adv = g[5] if g else lp * 0.6
                    self.r.draw_text(cv, letter, x + kw - adv - 3 * s, y + kh - 3 * s, lp, LABEL)

    def attach(self, cv, lw, lh, s):
        wl = self.wl
        size = len(cv.buf)
        fd = os.memfd_create("plektra-panel", os.MFD_CLOEXEC)
        os.ftruncate(fd, size)
        os.pwrite(fd, cv.buf, 0)
        pool = wl.new("wl_shm_pool")
        wl.req(self.shm, 0, "ni", pool, size, fd=fd)      # create_pool(id, fd, size): fd outside the body
        buf = wl.new("wl_buffer")
        wl.req(pool, 0, "niiiiu", buf, 0, cv.w, cv.h, cv.w * 4, 0)  # ARGB8888
        wl.req(pool, 1)                                  # pool.destroy
        wl.handlers[buf] = lambda op, b, i=buf: self.release(i)
        wl.req(self.surface, 1, "oii", buf, 0, 0)        # attach
        if self.viewport and abs(s - round(s)) > 1e-3:
            wl.req(self.viewport, 2, "ii", lw, lh)       # set_destination
            if self.comp_v >= 3:
                wl.req(self.surface, 8, "i", 1)
        else:
            if self.viewport:
                wl.req(self.viewport, 2, "ii", -1, -1)
            if self.comp_v >= 3:
                wl.req(self.surface, 8, "i", int(round(s)))   # set_buffer_scale
        wl.req(self.surface, 2, "iiii", 0, 0, 1 << 30, 1 << 30)  # damage
        wl.req(self.surface, 6)                          # commit
        wl.flush()
        os.close(fd)

    def release(self, buf):
        self.wl.req(buf, 0)
        self.wl.handlers.pop(buf, None)

    def unmap(self):
        self.wl.req(self.surface, 1, "oii", 0, 0, 0)
        self.wl.req(self.surface, 6)
        self.wl.flush()
        self.drawn_key = None

    # -- link to the service
    def ui_connect(self):
        if self.ui is not None or time.monotonic() - self.last_ui_try < 2:
            return
        self.last_ui_try = time.monotonic()
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            s.connect(UI_SOCK)
            s.setblocking(False)
        except OSError:
            s.close()
            self.ui = None
            return
        self.ui = s
        self.ui_buf = b""
        self.ui_send({"hello": "im", "active": self.ctx is not None, "sugg": len(self.sugg)})
        if self.ui is not None and getattr(self, "sent_start", None) is not None:
            # a restarted service has lost the field context: resend it
            self.ui_send({"ctx": {"sentence_start": self.sent_start}})

    def ui_send(self, obj):
        if self.ui is None:
            return
        try:
            self.ui.sendall((json.dumps(obj) + "\n").encode())
        except OSError:
            self.ui.close()
            self.ui = None

    def ui_read(self):
        try:
            data = self.ui.recv(65536)
        except BlockingIOError:
            return
        except OSError:
            data = b""
        if not data:
            self.ui.close()
            self.ui = None
            return
        self.ui_buf += data
        while b"\n" in self.ui_buf:
            line, self.ui_buf = self.ui_buf.split(b"\n", 1)
            try:
                m = json.loads(line)
            except ValueError:
                continue
            if "commit" in m:
                ok = self.commit(m["commit"])
                if not ok:
                    self.ui_send({"active": False, "missed": m["commit"]})
            if "config" in m:
                self.apply_config(m["config"] or {})
            if "pick" in m:
                self.pick(int(m["pick"]))
            if "clip_set" in m:
                self.set_clip(m["clip_set"])
            if "state" in m:
                self.state = m["state"]
                self.page = m.get("page")
                self.redraw()

    def run(self):
        self.setup()
        self.dbus_setup()
        while True:
            self.ui_connect()
            fds = [self.wl.sock] + ([self.ui] if self.ui else []) + ([self.bus] if self.bus else [])
            r, _, _ = select.select(fds, [], [], 1.0)
            if self.wl.sock in r:
                self.wl.read()
            if self.bus is not None and self.bus in r:
                try:
                    self.bus.process()
                except Exception as e:      # a malformed message must not end the bar
                    log("D-Bus: %r" % e)
                    try:
                        self.bus.sock.close()
                    except OSError:
                        pass
                    self.bus = None
            if self.ui is not None and self.ui in r:
                try:
                    self.ui_read()
                except Exception as e:
                    import traceback
                    log("error: %r\n%s" % (e, traceback.format_exc()))
            self.wl.flush()


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--render-test":
        # render check without Wayland: plektra-linux-im.py --render-test out.png PAGE_JSON
        im = PlektraIM.__new__(PlektraIM)
        im.r = R.TextRenderer()
        im.state = {"shift": "one_shot", "ctrl": "latch", "alt": "off", "sym_page": int(os.environ.get("PAGE", "1"))}
        im.page = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        im.toplevel, im.out_info, im.fscale, im.pref_scale = True, {"w": 1440, "scale": 2}, None, 2
        lw, lh, s, sym, gh = im.layout()
        cv = R.Canvas(int(lw * s), int(lh * s))
        cv.fill(BG)
        im.draw_grid(cv, s, cv.w, int(gh * s))
        im.draw_leds(cv, s, cv.w, int(gh * s), cv.h)
        with open(sys.argv[2], "wb") as f:
            f.write(R.png_encode(cv))
        return
    PlektraIM().run()


if __name__ == "__main__":
    main()
