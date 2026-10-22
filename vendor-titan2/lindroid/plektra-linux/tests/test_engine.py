# SPDX-License-Identifier: GPL-3.0-or-later
# plektra-linux logic check without a device: python3 -I -B tests/test_engine.py
import importlib.util
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("plektra", os.path.join(ROOT, "plektra-linux.py"))
P = importlib.util.module_from_spec(spec)
spec.loader.exec_module(P)
K = P.KEY


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class Rig:
    def __init__(self, **over):
        cfg = dict(P.DEFAULT_CONFIG)
        cfg["mappings_dirs"] = [os.path.join(ROOT, "mappings")]
        cfg.update(over)
        self.tables = P.Tables(cfg)
        self.slots = P.parse_xkb(os.path.join(ROOT, "xkb", "symbols", "titan2"))
        self.ev = []
        self.out = P.Output(lambda c, v: self.ev.append((c, v)), self.slots)
        self.ui = []
        self.clock = Clock()
        self.e = P.Engine(cfg, self.tables, self.out,
                          lambda kind, **kw: self.ui.append(kw.get("what")), self.clock)
        self.rev = {v: k for k, v in self.slots.items()}

    def wait(self, ms):
        self.clock.t += ms
        self.e.tick()

    def press(self, code, hold=30):
        self.e.key(code, 1)
        self.wait(hold)
        self.e.key(code, 0)
        self.wait(30)

    def down(self, code):
        self.e.key(code, 1)
        self.wait(10)

    def up(self, code):
        self.e.key(code, 0)
        self.wait(10)

    def text(self):
        """Reconstruct what was typed: us letters, slots, special keys in <>."""
        res = []
        mods = set()
        for c, v in self.ev:
            if c in P.OUT_MODS:
                (mods.add if v else mods.discard)(c)
                continue
            if not v:
                continue
            if c in P.LETTER_CODES:
                ch = P.LETTER_CODES[c]
                ch = ch if P.O_SHIFT in mods else ch.lower()
                pre = "".join(n for m, n in ((P.O_CTRL, "C-"), (P.O_ALT, "A-"),
                                              (P.O_META, "M-")) if m in mods)
                res.append("<%s%s>" % (pre, ch) if pre else ch)
            elif (c, 2 if P.O_SHIFT in mods else 1) in self.rev:
                res.append(self.rev[(c, 2 if P.O_SHIFT in mods else 1)])
            elif c == K["KEY_SPACE"] and not (mods - {P.O_SHIFT}):
                res.append(" ")
            else:
                pre = "".join(n for m, n in ((P.O_CTRL, "C-"), (P.O_ALT, "A-"),
                                              (P.O_META, "M-"), (P.O_SHIFT, "S-")) if m in mods)
                res.append("<%s%s>" % (pre, P.CODE_NAME.get(c, c)[4:]))
        return "".join(res)


Q, W, E, R, A, S, C, T, B = (K["KEY_" + x] for x in "QWERASCTB")
SH, ALT, SYM, FN, BACK, DEL, ENTER, SPACE = (P.T_SHIFT, P.T_ALT, P.T_SYM, P.T_FN, P.T_BACK,
                                             P.T_DEL, P.T_ENTER, P.T_SPACE)
H, I = K["KEY_H"], K["KEY_I"]


class EngineTest(unittest.TestCase):
    def test_plain_and_dupes(self):
        r = Rig()
        r.e.key(H, 1); r.e.key(H, 1); r.wait(20); r.e.key(H, 0); r.e.key(H, 0)
        r.press(I)
        self.assertEqual(r.text(), "hi")

    def test_shift_one_shot_and_caps(self):
        r = Rig()
        r.press(SH); r.press(A); r.press(B)
        self.assertEqual(r.text(), "Ab")
        r = Rig()
        r.press(SH); r.press(SH); r.press(A); r.press(B); r.wait(600); r.press(SH); r.press(A)
        self.assertEqual(r.text(), "ABa")

    def test_shift_held(self):
        r = Rig()
        r.down(SH); r.press(A); r.press(B); r.up(SH); r.press(C)
        self.assertEqual(r.text(), "ABc")

    def test_alt_one_shot_latch_hold(self):
        r = Rig()
        r.press(ALT); r.press(W); r.press(W)
        self.assertEqual(r.text(), "1w")
        r = Rig()
        r.press(ALT); r.press(ALT); r.press(W); r.press(E); r.press(SPACE); r.press(W)
        self.assertEqual(r.text(), "12 w")
        r = Rig()
        r.down(ALT); r.press(W); r.press(A); r.up(ALT); r.press(W)
        self.assertEqual(r.text(), "1@w")

    def test_long_press(self):
        r = Rig()
        r.press(W, hold=350)
        self.assertEqual(r.text(), "w<BACKSPACE>1")
        r = Rig()
        r.press(W, hold=200)
        self.assertEqual(r.text(), "w")

    def test_sym_pages(self):
        r = Rig()
        r.press(SYM); r.press(W); r.press(W)
        self.assertEqual(r.text(), "1w")
        r = Rig()
        r.press(SYM); r.press(SYM); r.press(Q)
        self.assertEqual(r.text(), "😀")
        r = Rig()
        r.press(SYM); r.press(SYM); r.press(SYM); r.press(T)
        self.assertEqual(r.text(), "[")
        r = Rig()
        r.press(SYM); r.press(SYM); r.press(SYM); r.press(SYM); r.press(T)
        self.assertEqual(r.text(), "t")

    def test_sym_chord(self):
        r = Rig()
        r.down(SYM); r.press(Q); r.up(SYM); r.press(Q)
        self.assertEqual(r.text(), "0q")
        self.assertEqual(r.e.sym_page, 0)

    def test_back_closes_sym_else_esc(self):
        r = Rig()
        r.press(SYM); r.press(BACK); r.press(BACK)
        self.assertEqual(r.text(), "<ESC>")

    def test_ctrl_one_shot(self):
        r = Rig()
        r.press(FN); r.press(C); r.press(C)
        self.assertEqual(r.text(), "<C-c>c")
        r = Rig()
        r.press(FN); r.press(E)
        self.assertEqual(r.text(), "<UP>")
        r = Rig()
        r.press(FN); r.press(DEL)
        self.assertEqual(r.text(), "<C-BACKSPACE>")

    def test_nav_mode(self):
        r = Rig()
        r.press(FN); r.press(FN); r.press(E); r.press(S); r.press(ENTER); r.wait(600)
        r.press(FN); r.press(E)
        self.assertEqual(r.text(), "<UP><LEFT><ENTER>e")

    def test_ctrl_held_native(self):
        r = Rig()
        r.down(FN); r.press(T); r.up(FN); r.press(T)
        self.assertEqual(r.text(), "<C-t>t")

    def test_hold_alone_restores(self):
        r = Rig()
        r.down(FN); r.wait(500); r.up(FN); r.press(C)
        self.assertEqual(r.text(), "c")

    def test_layout_switch(self):
        r = Rig()
        r.press(FN); r.press(SPACE); r.press(A)
        self.assertEqual(r.text(), "<A-M-k>a")

    def test_double_space_autocap(self):
        r = Rig()
        r.press(A); r.press(SPACE); r.press(SPACE); r.press(B)
        self.assertEqual(r.text(), "a <BACKSPACE>. B")

    def test_shift_cancels_autocap(self):
        r = Rig()
        r.press(A); r.press(SPACE); r.press(SPACE); r.press(SH); r.press(B)
        self.assertEqual(r.text(), "a <BACKSPACE>. b")

    def test_alt_space_and_enter_clear(self):
        r = Rig()
        r.press(ALT); r.press(SPACE); r.press(W)
        self.assertEqual(r.text(), " w")

    def test_fn_hold_shift_selection(self):
        r = Rig()
        r.press(FN); r.down(SH); r.press(S); r.up(SH)
        self.assertEqual(r.text(), "<S-LEFT>")

    def test_terminal_profile(self):
        r = Rig()
        r.e.set_app("org.kde.konsole")
        r.press(FN); r.press(C)                       # Fn, C = a real Ctrl+C
        r.press(SYM)                                  # Sym = Esc
        r.down(SYM); r.press(Q); r.up(SYM)            # Sym+Q: the first page is symbols: ~
        r.press(ALT); r.press(SPACE)                  # Alt+Space = Tab
        self.assertEqual(r.text(), "<C-c><ESC>~<TAB>")
        r.e.set_app("org.kde.kate")
        r.press(SYM); r.press(W)
        self.assertEqual(r.text(), "<C-c><ESC>~<TAB>1")

    def test_fn_outside_field_is_ctrl(self):
        class Im:
            active = set()
            def connected(self): return True
            def state(self, *a): pass
            def broadcast(self, *a, **k): pass
        r = Rig()
        r.e.im = Im()
        r.press(FN); r.press(T)
        self.assertEqual(r.text(), "<C-t>")
        r.e.im.active = {1}
        r.press(FN); r.press(E)
        self.assertEqual(r.text(), "<C-t><UP>")

    def test_long_space_layout(self):
        r = Rig()
        r.press(SPACE, hold=600); r.press(A)
        self.assertEqual(r.text(), "<A-M-k>a")

    def test_macro_and_remap(self):
        r = Rig(macros={"SYM+D": {"text": "x{date:%Y}y"}}, keys={"FN": "KEY_TAB"})
        r.down(SYM); r.press(K["KEY_D"]); r.up(SYM)
        r.press(FN)
        import datetime
        self.assertEqual(r.text(), "x%dy<TAB>" % datetime.date.today().year)

    def test_bounce_keys(self):
        r = Rig(bounce_keys_enabled=True, bounce_keys_delay_ms=80)
        r.press(A, hold=10); r.press(A, hold=10); r.wait(100); r.press(A)
        self.assertEqual(r.text(), "aa")

    def test_long_press_sym_layer(self):
        r = Rig(long_press_mode="sym_symbols")
        r.press(Q, hold=350)
        self.assertEqual(r.text(), "q<BACKSPACE>~")

    def test_slots_cover_tables(self):
        r = Rig()
        missing = [chr(c) for c in P.needed_codepoints(r.tables.all_texts())
                   if chr(c) not in r.slots]
        self.assertEqual(missing, [])


if __name__ == "__main__":
    unittest.main(verbosity=1)
