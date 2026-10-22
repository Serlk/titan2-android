# SPDX-License-Identifier: GPL-3.0-or-later
# Keyboard touchpad: frames, wheel with slop and fling, mouse zone, text cursor, suggestions.
import os, sys, unittest
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
import plektra_touchpad as T


class Out:
    def __init__(self):
        self.calls = []
        self.field = False
        self.focused = True
        self.sugg = 0

    def rel(self, dx, dy):
        self.calls.append(("rel", dx, dy))

    def wheel(self, v, h):
        self.calls.append(("wheel", v, h))

    def click(self):
        self.calls.append(("click",))

    def key(self, code):
        self.calls.append(("key", code))

    def in_field(self):
        return self.field

    def lindroid_focused(self):
        return self.focused

    def pick_suggestion(self):
        self.calls.append(("pick",))

    def has_suggestions(self):
        return self.sugg > 0

    def of(self, kind):
        return [c for c in self.calls if c[0] == kind]

    def wheel_sum(self):
        return (sum(c[1] for c in self.of("wheel")), sum(c[2] for c in self.of("wheel")))


def raw(x, y):
    """Pad units -> raw coordinates (rounded; a unit of error at most)."""
    return round(x * T.RAW_X_DIV / T.SCREEN_W), round(y * T.RAW_Y_DIV / T.PAD_V)


class Pad:
    """Drives a Touchpad with frames given in pad units; time in ms."""

    def __init__(self, **tc):
        self.o = Out()
        self.tp = T.Touchpad({"touchpad": dict(tc)}, self.o)
        self.t = 1000

    def frame(self, x=None, y=None, touch=None, dt=19):
        self.t += dt
        if touch is not None:
            self.tp.on_raw(T.EV_KEY, T.BTN_TOUCH, 1 if touch else 0, self.t)
        if x is not None:
            rx, ry = raw(x, y)
            self.tp.on_raw(T.EV_ABS, T.ABS_MT_POSITION_X, rx, self.t)
            self.tp.on_raw(T.EV_ABS, T.ABS_MT_POSITION_Y, ry, self.t)
        self.tp.on_raw(T.EV_SYN, T.SYN_REPORT, 0, self.t)

    def down(self, x, y, dt=19):
        self.frame(x, y, touch=True, dt=dt)

    def move(self, x, y, dt=19):
        self.frame(x, y, dt=dt)

    def up(self, x=None, y=None, dt=19):
        self.frame(x, y, touch=False, dt=dt)

    def swipe(self, pts, dt=19, lift=True):
        self.down(*pts[0])
        for p in pts[1:]:
            self.move(*p, dt=dt)
        if lift:
            self.up(dt=dt)

    def tap(self, x, y, dt=19, gap=60):
        self.down(x, y, dt=gap)
        self.up(dt=dt)

    def run_fling(self, limit=2000):
        n = 0
        while self.tp.next_deadline() is not None and n < limit:
            self.t = max(self.t, self.tp.next_deadline())
            self.tp.tick(self.t)
            n += 1
        return n


class FrameTest(unittest.TestCase):
    def test_scale(self):
        self.assertEqual(T.scale(0, 0), (0, 0))
        x, y = T.scale(1440, 720)
        self.assertAlmostEqual(x, 1440 * 1440 / 1441)
        self.assertAlmostEqual(y, 720 * 910 / 721)
        self.assertEqual(T.PAD_V, 910)

    def test_frames_without_contact_ignored(self):
        p = Pad(mode="mouse_left")
        p.move(100, 100)
        p.move(300, 300)
        p.up()
        self.assertEqual(p.o.calls, [])

    def test_tool_finger_and_wake_key_ignored(self):
        p = Pad(mode="mouse_left")
        p.tp.on_raw(T.EV_KEY, T.BTN_TOOL_FINGER, 1, 1)
        p.tp.on_raw(T.EV_KEY, 143, 1, 1)       # KEY_WAKEUP: Android only
        p.tp.on_raw(T.EV_SYN, T.SYN_REPORT, 0, 1)
        self.assertIsNone(p.tp.g)
        self.assertEqual(p.o.calls, [])

    def test_syn_dropped_discards_frame(self):
        p = Pad(mode="mouse_left")
        p.down(100, 100)
        p.tp.on_raw(T.EV_ABS, T.ABS_MT_POSITION_X, 900, p.t)
        p.tp.on_raw(T.EV_SYN, T.SYN_DROPPED, 0, p.t)
        p.tp.on_raw(T.EV_SYN, T.SYN_REPORT, 0, p.t)
        self.assertEqual(p.o.calls, [])
        p.move(110, 100)
        self.assertAlmostEqual(sum(c[1] for c in p.o.of("rel")), 10, delta=1)

    def test_disabled_or_not_focused(self):
        for kw, focused in (({"enabled": False}, True), ({}, False)):
            p = Pad(**kw)
            p.o.focused = focused
            p.swipe([(700, 100), (700, 200), (700, 400), (700, 600)])
            p.tap(700, 300)
            self.assertEqual(p.o.calls, [])

    def test_cfg_read_at_touch_down(self):
        p = Pad(mode="scroll")
        p.down(100, 100)
        p.tp.cfg = {"touchpad": {"mode": "mouse_left"}}
        p.move(100, 300)
        self.assertEqual(p.o.of("rel"), [])                     # still scroll mode
        p.up()
        p.swipe([(100, 100), (150, 100)])
        self.assertAlmostEqual(sum(c[1] for c in p.o.of("rel")), 50, delta=1)  # now mouse_left


class WheelTest(unittest.TestCase):
    def test_slop_and_amount(self):
        p = Pad()
        p.down(700, 100)
        p.move(700, 200)        # first movement report: start point only
        self.assertEqual(p.o.calls, [])
        p.move(700, 220)        # 20 <= slop
        self.assertEqual(p.o.calls, [])
        p.move(700, 300)        # 100 - 22 = 78 units
        v, h = p.o.wheel_sum()
        self.assertEqual(h, 0)
        self.assertAlmostEqual(v, 78 * 0.012 * 120, delta=2)
        before = v
        p.move(700, 400)        # full delta from now on
        v, _ = p.o.wheel_sum()
        self.assertAlmostEqual(v - before, 100 * 0.012 * 120, delta=2)

    def test_direction_reverse_speed(self):
        def total(**kw):
            p = Pad(inertia=False, **kw)
            p.swipe([(700, 100), (700, 110), (700, 300), (700, 500)])
            return p.o.wheel_sum()
        v, h = total()
        self.assertGreater(v, 0)            # finger toward the user: positive REL_WHEEL
        self.assertEqual(h, 0)
        self.assertLess(total(reverse=True)[0], 0)
        self.assertAlmostEqual(total(scroll_speed=2.0)[0], 2 * v, delta=2)
        p = Pad(inertia=False)
        p.swipe([(900, 300), (880, 300), (700, 300), (500, 300)])
        v, h = p.o.wheel_sum()
        self.assertEqual(v, 0)
        self.assertLess(h, 0)               # finger left: negative REL_HWHEEL

    def test_one_axis_per_report(self):
        p = Pad(inertia=False)
        p.down(700, 100)
        p.move(700, 101)
        p.move(700, 200)                    # past the slop
        n = len(p.o.calls)
        p.move(760, 230)                    # dx 60 > dy 30: horizontal only
        new = p.o.calls[n:]
        self.assertTrue(new and all(c[1] == 0 and c[2] > 0 for c in new))

    def test_fraction_carry(self):
        p = Pad(inertia=False, scroll_speed=0.1)
        p.down(700, 100)
        p.move(700, 101)
        p.move(700, 151)                    # 28 units past the slop
        for i in range(20):
            p.move(700, 160 + 10 * i)       # 10 units = 1.44 hi-res each at 0.1
        v, _ = p.o.wheel_sum()
        units = p.tp.g.ry - p.tp.g.sy - 22
        self.assertAlmostEqual(v, units * 0.012 * 120 * 0.1, delta=1.0)

    def test_slop_skip_after_scroll(self):
        p = Pad(inertia=False)
        p.swipe([(700, 100), (700, 110), (700, 300)])
        p.o.calls = []
        p.down(700, 300, dt=100)
        p.move(700, 305)
        p.move(700, 315)                    # 10 < slop, but slop skipped
        self.assertGreater(p.o.wheel_sum()[0], 0)
        p.up()
        p.o.calls = []
        p.down(700, 300, dt=600)            # > 500 ms: slop again
        p.move(700, 305)
        p.move(700, 315)
        self.assertEqual(p.o.calls, [])

    def test_builtin_key_disables_scroll_shift_does_not(self):
        p = Pad(inertia=False)
        p.down(700, 100)
        p.move(700, 101)
        p.move(700, 200)
        p.tp.on_key(False)                  # Shift
        n = len(p.o.calls)
        p.move(700, 300)
        self.assertGreater(len(p.o.calls), n)
        p.tp.on_key(True)
        n = len(p.o.calls)
        p.move(700, 400)
        p.move(700, 500)
        self.assertEqual(len(p.o.calls), n)
        p.up()
        p.swipe([(700, 100), (700, 110), (700, 300)], lift=False)   # next touch: back on
        self.assertGreater(len(p.o.calls), n)

    def test_screen_touch_guard(self):
        p = Pad(inertia=False)
        p.tp.on_screen_touch_up(p.t)
        p.down(700, 100)
        for i in range(10):                 # 200 ms of movement inside the guard
            p.move(700, 110 + 20 * i)
        self.assertEqual(p.o.calls, [])
        p.t += 800
        p.move(700, 300)                    # new start point
        p.move(700, 315)                    # within the slop again
        self.assertEqual(p.o.calls, [])
        p.move(700, 400)
        self.assertGreater(p.o.wheel_sum()[0], 0)


class FlingTest(unittest.TestCase):
    def fast(self, **kw):
        p = Pad(**kw)
        p.swipe([(700, 50 + 40 * i) for i in range(12)], dt=10)
        return p

    def test_fling_runs_and_stops(self):
        p = self.fast()
        self.assertIsNotNone(p.tp.next_deadline())
        before = p.o.wheel_sum()[0]
        steps = p.run_fling()
        self.assertGreater(steps, 5)
        self.assertLess(steps, 2000)
        self.assertIsNone(p.tp.next_deadline())
        self.assertGreater(p.o.wheel_sum()[0], before)
        self.assertEqual(p.o.wheel_sum()[1], 0)

    def test_tick_respects_deadline(self):
        p = self.fast()
        d = p.tp.next_deadline()
        n = len(p.o.calls)
        p.tp.tick(d - 1)
        self.assertEqual(len(p.o.calls), n)
        self.assertEqual(p.tp.next_deadline(), d)

    def test_no_fling_without_inertia_or_slow(self):
        self.assertIsNone(self.fast(inertia=False).tp.next_deadline())
        p = Pad()
        p.swipe([(700, 100), (700, 110), (700, 300)], lift=False)
        p.t += 300                          # finger rested before the lift: no recent samples
        p.up()
        self.assertIsNone(p.tp.next_deadline())

    def test_touch_down_and_key_stop_fling(self):
        p = self.fast()
        p.down(700, 300)
        self.assertIsNone(p.tp.next_deadline())
        p = self.fast()
        p.tp.on_key(False)                  # Shift: fling goes on
        self.assertIsNotNone(p.tp.next_deadline())
        p.tp.on_key(True)
        self.assertIsNone(p.tp.next_deadline())

    def test_slop_skipped_during_fling(self):
        p = self.fast()
        p.o.calls = []
        p.down(700, 300, dt=1000)           # well past 500 ms, but the fling is still running
        p.move(700, 305)
        p.move(700, 315)
        self.assertGreater(p.o.wheel_sum()[0], 0)

    def test_velocity_against_displacement_dropped(self):
        p = Pad()
        # forward a long way, then a quick flick back that stays ahead of the start point
        pts = [(700, 100), (700, 110)] + [(700, 120 + 40 * i) for i in range(10)]
        pts += [(700, 450 - 30 * i) for i in range(9)]
        p.swipe(pts, dt=10)
        self.assertIsNone(p.tp.next_deadline())     # vy < 0, displacement > 0


class MouseTest(unittest.TestCase):
    def test_pointer_zone_left(self):
        p = Pad(mode="mouse_left", mouse_speed=1.5)
        p.swipe([(100, 100), (110, 100), (110, 130), (300, 400)])
        dx = sum(c[1] for c in p.o.of("rel"))
        dy = sum(c[2] for c in p.o.of("rel"))
        self.assertAlmostEqual(dx, 200 * 1.5, delta=2)
        self.assertAlmostEqual(dy, 300 * 1.5, delta=2)
        self.assertEqual(p.o.of("wheel"), [])
        self.assertEqual(p.o.of("click"), [])
        p.tap(100, 100)
        self.assertEqual(p.o.of("click"), [])       # no click in the pointer zone

    def test_pointer_carry(self):
        p = Pad(mode="mouse_left", mouse_speed=0.25)
        p.down(100, 100)
        for i in range(1, 41):
            p.move(100 + i, 100)
        self.assertAlmostEqual(sum(c[1] for c in p.o.of("rel")), 10, delta=1)

    def test_zone_right(self):
        p = Pad(mode="mouse_right")
        p.swipe([(1000, 100), (1100, 100)])
        self.assertTrue(p.o.of("rel"))
        p = Pad(mode="mouse_right")
        p.swipe([(800, 100), (900, 100)])           # x = 800 is outside the right zone
        self.assertEqual(p.o.of("rel"), [])
        p = Pad(mode="mouse_left")
        p.swipe([(580, 100), (680, 100)])           # x = 580 is outside the left zone
        self.assertEqual(p.o.of("rel"), [])

    def test_click_outside_zone(self):
        p = Pad(mode="mouse_left")
        p.tap(900, 300)
        self.assertEqual(p.o.of("click"), [("click",)])
        p = Pad(mode="mouse_left")
        p.swipe([(900, 300), (920, 310), (940, 330)])   # < 50 on both axes
        self.assertEqual(len(p.o.of("click")), 1)
        p = Pad(mode="mouse_left", inertia=False)
        p.swipe([(900, 300), (900, 330), (900, 360)])   # 60 on y: a scroll, no click
        self.assertEqual(p.o.of("click"), [])
        self.assertGreater(p.o.wheel_sum()[0], 0)

    def test_no_click_in_scroll_mode(self):
        p = Pad(mode="scroll")
        p.tap(900, 300)
        self.assertEqual(p.o.calls, [])

    def test_click_frozen_or_guarded(self):
        p = Pad(mode="mouse_right")
        p.down(300, 300)
        p.tp.on_key(False)
        p.up()
        self.assertEqual(p.o.of("click"), [])
        p.tap(300, 300)
        self.assertEqual(len(p.o.of("click")), 1)  # the next touch is not frozen
        p.tp.on_screen_touch_up(p.t)
        p.tap(300, 300)
        self.assertEqual(len(p.o.of("click")), 1)
        p.t += 900
        p.tap(300, 300)
        self.assertEqual(len(p.o.of("click")), 2)

    def test_field_disables_mouse(self):
        p = Pad(mode="mouse_left")
        p.o.field = True
        p.swipe([(100, 100), (300, 300)])
        p.tap(900, 300, gap=400)
        self.assertEqual(p.o.calls, [])


class FieldTest(unittest.TestCase):
    def cursor_pad(self):
        p = Pad()
        p.o.field = True
        p.tap(700, 400)
        p.tap(720, 420)
        self.assertTrue(p.tp.cursor_mode)
        p.t += 400
        return p

    def keys(self, p):
        return [c[1] for c in p.o.of("key")]

    def test_double_tap_toggles(self):
        p = self.cursor_pad()
        p.tap(700, 400)
        self.assertTrue(p.tp.cursor_mode)       # 400 ms later: the first of a new pair
        p.tap(710, 400)
        self.assertFalse(p.tp.cursor_mode)
        p.tap(720, 400)                         # a third quick tap counts again
        self.assertTrue(p.tp.cursor_mode)

    def test_double_tap_limits(self):
        p = Pad()
        p.o.field = True
        p.tap(700, 400)
        p.tap(700, 400, gap=320)                # > 300 ms between the lifts
        self.assertFalse(p.tp.cursor_mode)
        p.t += 400
        p.tap(700, 400)
        p.tap(800, 400)                         # 100 units apart
        self.assertFalse(p.tp.cursor_mode)

    def test_no_scroll_in_field(self):
        p = Pad()
        p.o.field = True
        p.swipe([(700, 100), (700, 110), (700, 300), (700, 600)], dt=10)
        self.assertEqual(p.o.of("wheel"), [])
        self.assertIsNone(p.tp.next_deadline())

    def test_arrows(self):
        p = self.cursor_pad()
        p.down(700, 400)
        p.move(720, 400)                        # 20 < 30
        self.assertEqual(self.keys(p), [])
        p.move(735, 400)                        # 35 from the reference
        self.assertEqual(self.keys(p), [T.KEY_RIGHT])
        p.move(800, 400)                        # 65: still one arrow per report
        self.assertEqual(self.keys(p), [T.KEY_RIGHT] * 2)
        p.move(760, 400)
        self.assertEqual(self.keys(p)[-1], T.KEY_LEFT)
        p.move(760, 350)                        # 50 < 100 up
        self.assertEqual(len(self.keys(p)), 3)
        p.move(760, 250)                        # 150 up from (760, 400)
        self.assertEqual(self.keys(p)[-1], T.KEY_UP)
        p.move(760, 360)
        self.assertEqual(self.keys(p)[-1], T.KEY_DOWN)
        p.move(820, 400)                        # diagonal: no key, new reference
        self.assertEqual(len(self.keys(p)), 5)
        p.move(840, 400)                        # 20 from the new reference
        self.assertEqual(len(self.keys(p)), 5)
        p.up()

    def test_key_leaves_cursor_mode(self):
        p = self.cursor_pad()
        p.tp.on_key(False)                      # Shift too (like Android)
        self.assertFalse(p.tp.cursor_mode)
        p = self.cursor_pad()
        p.tap(700, 400)
        p.tp.on_key(True)                       # also forgets the last lift
        p.tap(700, 400)
        self.assertFalse(p.tp.cursor_mode)
        p.swipe([(700, 400), (800, 400)])
        self.assertEqual(self.keys(p), [])

    def test_field_gone_clears_mode(self):
        p = self.cursor_pad()
        p.o.field = False
        p.tap(700, 400)
        self.assertFalse(p.tp.cursor_mode)

    def test_suggestion_swipe(self):
        p = Pad()
        p.o.field = True
        p.o.sugg = 3
        p.swipe([(700, 500), (710, 400), (720, 360)])     # rise 140, dx 20
        self.assertEqual(p.o.of("pick"), [("pick",)])
        p.t += 400
        p.swipe([(700, 500), (790, 400), (800, 360)])     # rise 140 < 2 * 100
        self.assertEqual(len(p.o.of("pick")), 1)
        p.t += 400
        p.swipe([(700, 500), (700, 400)])                 # rise 100 <= 120
        self.assertEqual(len(p.o.of("pick")), 1)
        p.t += 400
        p.o.sugg = 0
        p.swipe([(700, 500), (700, 300)])                 # nothing on the bar
        self.assertEqual(len(p.o.of("pick")), 1)

    def test_no_suggestion_in_cursor_mode_or_outside_field(self):
        p = self.cursor_pad()
        p.o.sugg = 3
        p.swipe([(700, 500), (700, 300)])
        self.assertEqual(p.o.of("pick"), [])
        self.assertEqual(self.keys(p), [T.KEY_UP])         # one arrow per report
        p = Pad(inertia=False)
        p.o.sugg = 3
        p.swipe([(700, 500), (700, 300)])
        self.assertEqual(p.o.of("pick"), [])


if __name__ == "__main__":
    unittest.main(verbosity=1)
