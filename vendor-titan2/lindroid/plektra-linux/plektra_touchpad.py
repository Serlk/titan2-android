# SPDX-License-Identifier: GPL-3.0-or-later
# plektra-linux: the keyboard touchpad of the Titan 2 in Linux.
#
# The `touchPad` evdev device reports one contact (BTN_TOUCH + ABS_MT_POSITION_X/Y, ~52 Hz).
# Each SYN_REPORT frame is a touch-down, a movement report or a lift. Depending on the state
# at touch-down a gesture is one of:
#   - "field":   a text field is active: double tap toggles text-cursor mode, strokes send
#                arrow keys in that mode, an upward swipe without it picks a suggestion;
#   - "pointer": mouse_left/mouse_right and the touch-down is in the pointer zone: REL_X/REL_Y;
#   - "scroll":  everything else: hi-res wheel with touch slop and fling; in the mouse modes a
#                short tap outside the pointer zone is also a left click.
# Linux has no swipe-for-light (Android does it in every case) and no double-tap wake.

EV_SYN, EV_KEY, EV_ABS = 0x00, 0x01, 0x03
SYN_REPORT, SYN_DROPPED = 0, 3
BTN_TOOL_FINGER, BTN_TOUCH = 0x145, 0x14A
ABS_MT_POSITION_X, ABS_MT_POSITION_Y = 0x35, 0x36
KEY_UP, KEY_LEFT, KEY_RIGHT, KEY_DOWN = 103, 105, 106, 108

# Pad units. The Linux side assumes a 1440x1440 screen.
SCREEN_W = SCREEN_H = 1440
PAD_V = 2 * SCREEN_H // 3 - 50          # 910
RAW_X_DIV, RAW_Y_DIV = 1441, 721

# cursor assistant
TAP_MS = 300                            # two lifts closer than this ...
TAP_DIST = 80                           # ... and within this on both axes toggle text-cursor mode
ARROW_H = 30                            # horizontal step
ARROW_V = 100                           # vertical step
AXIS_RATIO = 5                          # |dx| >= 5|dy| (or the other way round) for an arrow
SUGG_RISE = 120                         # upward swipe

# guard after a real screen touch
GUARD_MS = 800

# wheel
SLOP = 22
SLOP_SKIP_MS = 500
SCROLL_PER_UNIT = 0.012
HIRES = 120                             # hi-res units per detent

# fling
FLING_WINDOW_MS = 100
FLING_STEP_MS = 8
FLING_DIV = 1300.0
FLING_DECAY = 0.965
FLING_MIN_V = 1.0                       # units/s
SCROLL_FACTOR = 64                      # px per scroll unit for the "below 1 pixel" stop

# mouse modes
ZONE_LEFT = 2 * SCREEN_W / 5            # mouse_left: x < 576
ZONE_RIGHT = 3 * SCREEN_W / 5           # mouse_right: x > 864
CLICK_DIST = 50

MODES = ("scroll", "mouse_left", "mouse_right")


def scale(raw_x, raw_y):
    """Raw pad coordinates -> pad units."""
    return raw_x * SCREEN_W / RAW_X_DIV, raw_y * PAD_V / RAW_Y_DIV


def _num(v, dv):
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else dv


def _axis(dx, dy):
    """One axis per step: the larger of |dx| and |dy|, vertical on a tie."""
    return ("h", dx) if abs(dx) > abs(dy) else ("v", dy)


def _slope(pts):
    """Least-squares slope of [(t_ms, value)] in value units per second, 0 if undefined."""
    n = len(pts)
    if n < 2:
        return 0.0
    mt = sum(t for t, _ in pts) / n
    mv = sum(v for _, v in pts) / n
    den = sum((t - mt) ** 2 for t, _ in pts)
    if den <= 0:
        return 0.0
    return sum((t - mt) * (v - mv) for t, v in pts) / den * 1000.0


class _Gesture:
    """State of one pad contact, from touch-down to lift. Decided at touch-down."""

    def __init__(self, t, x, y):
        self.t0, self.x0, self.y0 = t, x, y
        self.px, self.py = x, y             # position of the previous frame
        self.ignored = False                # service disabled or Lindroid not focused
        self.field = False                  # a text field is active
        self.mode = "scroll"
        self.zone = False                   # starts in the pointer zone
        self.frozen = False                 # a key was pressed during the gesture
        self.scroll_off = False             # a built-in key disabled the wheel
        self.moved = False                  # moved >= CLICK_DIST from the touch-down point
        self.speed, self.reverse, self.inertia, self.mouse_speed = 1.0, False, True, 1.0
        # text cursor
        self.refx, self.refy = x, y
        # wheel
        self.skip_slop = False
        self.have_start = False             # the first usable movement report was seen
        self.sx = self.sy = 0.0             # start point
        self.rx = self.ry = 0.0             # last consumed point
        self.scrolling = False              # past the slop
        self.ever_scrolled = False
        self.last_axis = "v"
        self.samples = []                   # (t, x, y) since the start, for the fling


class Touchpad:
    def __init__(self, cfg, out):
        """out: an object with the methods rel(dx, dy), wheel(v120, h120), click(), key(code),
        in_field(), lindroid_focused(), pick_suggestion(), has_suggestions()."""
        self.cfg = cfg
        self.o = out
        # frame assembly
        self._touch = False                 # BTN_TOUCH as of the frame being assembled
        self._down = False                  # BTN_TOUCH as of the last complete frame
        self._rx = self._ry = 0             # latest raw X/Y
        self._pend = []                     # events of the current frame
        self._dropped = False               # SYN_DROPPED: discard up to the next SYN_REPORT
        self.g = None                       # current gesture
        # across gestures
        self.cursor_mode = False            # text-cursor mode
        self.last_lift = None               # (t, x, y) of the previous field lift
        self.guard_until = None             # end of the guard after a real screen touch
        self.last_scroll_lift = None        # lift time of the last gesture that scrolled
        self.fling = None                   # [vx, vy, t_prev, speed, reverse]
        self.fling_next = None
        self.acc = {"v": 0.0, "h": 0.0}     # fractional hi-res wheel remainders
        self.racc = [0.0, 0.0]              # fractional pointer remainders

    # ---- input from the service

    def on_key(self, builtin=True):
        """A key press from the device keyboard; builtin is False for Shift."""
        # Like Android: any key press that is not one of
        # our own arrows leaves text-cursor mode and forgets the last lift. Our arrows go out
        # through the service keyboard and never come back here.
        # The caller only reports builtin/Shift, so a device key that is an
        # arrow on its own (none on Titan 2 without a modifier) is treated as a non-arrow key.
        self.cursor_mode = False
        self.last_lift = None
        # A freeze (every key, Shift too) only suppresses the tap click, the
        # double-tap lift and the suggestion swipe; the wheel and the fling stop only for
        # non-Shift keys (Shift + wheel is horizontal scrolling), the pointer never stops.
        if self.g is not None:
            self.g.frozen = True
        if builtin:
            if self.g is not None:
                self.g.scroll_off = True
            self._stop_fling()

    def on_screen_touch_up(self, now):
        """A real touch was lifted (ms): no scrolling or clicks for 800 ms."""
        self.guard_until = now + GUARD_MS
        # A running fling is pad scrolling, so the guard stops it (the user just touched the
        # content).
        self._stop_fling()

    def on_raw(self, typ, code, value, now):
        """One evdev event of the touchPad device (ms)."""
        if typ == EV_SYN:
            if code == SYN_DROPPED:
                self._dropped = True
                self._pend = []
            elif code == SYN_REPORT:
                if self._dropped:
                    # the kernel lost events: drop this partial frame (evdev semantics)
                    self._dropped = False
                    self._pend = []
                    return
                self._frame(now)
            return
        if self._dropped:
            return
        if typ == EV_KEY:
            if code == BTN_TOUCH:
                self._pend.append((code, 1 if value else 0))
            # BTN_TOOL_FINGER is ignored; other key presses are the wake gesture of the
            # alternative controller, which only Android handles
        elif typ == EV_ABS and code in (ABS_MT_POSITION_X, ABS_MT_POSITION_Y):
            self._pend.append((code, value))

    def next_deadline(self):
        """Time (ms) of the next tick() needed, or None."""
        return self.fling_next if self.fling is not None else None

    def tick(self, now):
        """Timer step (ms): one fling step every 8 ms."""
        if self.fling is None or now < self.fling_next:
            return
        vx, vy, tp, speed, reverse = self.fling
        dt = now - tp
        axis, d = _axis(vx * dt / FLING_DIV, vy * dt / FLING_DIV)
        if abs(d * SCROLL_PER_UNIT) * SCROLL_FACTOR < 1.0:
            self._stop_fling()
            return
        self._wheel(axis, d, speed, reverse)
        vx *= FLING_DECAY
        vy *= FLING_DECAY
        if max(abs(vx), abs(vy)) <= FLING_MIN_V:
            self._stop_fling()
            return
        self.fling = [vx, vy, now, speed, reverse]
        self.fling_next = now + FLING_STEP_MS

    # ---- frames

    def _frame(self, now):
        moved = False
        for code, value in self._pend:
            if code == BTN_TOUCH:
                self._touch = bool(value)
            elif code == ABS_MT_POSITION_X:
                moved = moved or value != self._rx
                self._rx = value
            else:
                moved = moved or value != self._ry
                self._ry = value
        self._pend = []
        was, self._down = self._down, self._touch
        x, y = scale(self._rx, self._ry)
        if self._touch and not was:
            self._touch_down(now, x, y)
        elif self._touch and was:
            if moved:   # a frame without a new position carries nothing to act on
                self._movement(now, x, y)
        elif was:
            self._lift(now, x, y)
        # no contact before or after: ignored

    def _tcfg(self):
        c = self.cfg.get("touchpad") if isinstance(self.cfg, dict) else None
        return c if isinstance(c, dict) else {}

    def _touch_down(self, now, x, y):
        fling_was_running = self.fling is not None
        self._stop_fling()                  # a touch-down stops a running fling
        g = self.g = _Gesture(now, x, y)
        tc = self._tcfg()
        if not tc.get("enabled", True) or not self.o.lindroid_focused():
            g.ignored = True                # only while Lindroid has the Android focus
            return
        g.field = bool(self.o.in_field())
        if not g.field:
            self.cursor_mode = False        # text-cursor mode may end with the field
        mode = tc.get("mode", "scroll")
        g.mode = mode if mode in MODES else "scroll"
        g.zone = ((g.mode == "mouse_left" and x < ZONE_LEFT) or
                  (g.mode == "mouse_right" and x > ZONE_RIGHT))
        g.speed = _num(tc.get("scroll_speed"), 1.0)
        g.mouse_speed = _num(tc.get("mouse_speed"), 1.0)
        g.reverse = bool(tc.get("reverse", False))
        g.inertia = bool(tc.get("inertia", True))
        g.skip_slop = fling_was_running or (
            self.last_scroll_lift is not None and now - self.last_scroll_lift < SLOP_SKIP_MS)
        self.acc = {"v": 0.0, "h": 0.0}
        self.racc = [0.0, 0.0]

    def _track_moved(self, g, x, y):
        if abs(x - g.x0) >= CLICK_DIST or abs(y - g.y0) >= CLICK_DIST:
            g.moved = True

    def _movement(self, now, x, y):
        g = self.g
        if g is None or g.ignored:
            return
        self._track_moved(g, x, y)
        if g.field:
            if self.cursor_mode:
                self._cursor(g, x, y)
        elif g.zone:
            self._pointer(g, x, y)
        else:
            self._scroll(g, now, x, y)
        g.px, g.py = x, y

    def _lift(self, now, x, y):
        g, self.g = self.g, None
        if g is None or g.ignored:
            return
        self._track_moved(g, x, y)
        if g.field:
            self._field_lift(g, now, x, y)
            return
        if g.zone:
            return                          # pointer zone: nothing but motion
        guarded = self.guard_until is not None and now < self.guard_until
        if g.ever_scrolled:
            self.last_scroll_lift = now
        if g.scrolling and g.inertia and not g.scroll_off and not guarded:
            self._start_fling(g, now, x, y)
        if (g.mode != "scroll" and not g.moved and not g.frozen and not guarded):
            self.o.click()                  # a short tap outside the pointer zone

    # ---- text field

    def _cursor(self, g, x, y):
        dx, dy = x - g.refx, y - g.refy
        if abs(dx) >= AXIS_RATIO * abs(dy):
            if abs(dx) >= ARROW_H:
                self.o.key(KEY_RIGHT if dx > 0 else KEY_LEFT)
                g.refx, g.refy = x, y
        elif abs(dy) >= AXIS_RATIO * abs(dx):
            if abs(dy) >= ARROW_V:
                self.o.key(KEY_DOWN if dy > 0 else KEY_UP)
                g.refx, g.refy = x, y
        else:
            g.refx, g.refy = x, y           # diagonal: no key, new reference

    def _field_lift(self, g, now, x, y):
        if g.frozen:
            # A gesture frozen by a key neither counts as a lift for the double
            # tap nor picks a suggestion (the key already forgot the previous lift)
            return
        was_cursor = self.cursor_mode
        prev, self.last_lift = self.last_lift, (now, x, y)
        if (prev is not None and now - prev[0] < TAP_MS and
                abs(x - prev[1]) <= TAP_DIST and abs(y - prev[2]) <= TAP_DIST):
            self.cursor_mode = not self.cursor_mode
            return
        if not was_cursor:
            rise = g.y0 - y
            if rise > SUGG_RISE and rise > 2 * abs(x - g.x0) and self.o.has_suggestions():
                self.o.pick_suggestion()

    # ---- pointer

    def _pointer(self, g, x, y):
        # The guard only blocks scrolling and clicks, so pointer motion is not guarded;
        # a key press does not stop the pointer either (Shift/Ctrl + click needs it).
        self.racc[0] += (x - g.px) * g.mouse_speed
        self.racc[1] += (y - g.py) * g.mouse_speed
        dx, dy = int(self.racc[0]), int(self.racc[1])
        self.racc[0] -= dx
        self.racc[1] -= dy
        if dx or dy:
            self.o.rel(dx, dy)

    # ---- wheel

    def _scroll(self, g, now, x, y):
        if g.scroll_off:
            return
        if self.guard_until is not None and now < self.guard_until:
            # nothing during the guard; the first movement after it is a new start
            g.have_start = g.scrolling = False
            g.samples = []
            return
        if not g.have_start:
            # the first movement report is the start point; it scrolls nothing
            g.have_start = True
            g.sx, g.sy = g.rx, g.ry = x, y
            g.samples = [(now, x, y)]
            if g.skip_slop:
                g.scrolling = g.ever_scrolled = True
            return
        g.samples.append((now, x, y))
        while g.samples and g.samples[0][0] < now - 4 * FLING_WINDOW_MS:
            g.samples.pop(0)
        if not g.scrolling:
            # touch slop: the distance from the start point along the chosen axis piles up
            axis, d = _axis(x - g.sx, y - g.sy)
            if abs(d) <= SLOP:
                return
            d -= SLOP if d > 0 else -SLOP
            g.scrolling = g.ever_scrolled = True
        else:
            axis, d = _axis(x - g.rx, y - g.ry)
        g.rx, g.ry = x, y
        g.last_axis = axis
        self._wheel(axis, d, g.speed, g.reverse)

    def _wheel(self, axis, d, speed, reverse):
        """Scroll by d pad units on one axis: 0.012 per unit, 120 hi-res units per detent.
        +y (finger toward the user) is positive REL_WHEEL (scroll up, content follows the
        finger); +x is positive REL_HWHEEL."""
        # Finger right is positive horizontal scroll; on Linux positive REL_HWHEEL scrolls right
        # (content moves left, as with a tilt wheel). Same sign as Android AXIS_HSCROLL.
        val = d * SCROLL_PER_UNIT * HIRES * speed
        if reverse:
            val = -val
        a = self.acc[axis] + val
        n = int(a)
        self.acc[axis] = a - n
        if n:
            if axis == "v":
                self.o.wheel(n, 0)
            else:
                self.o.wheel(0, n)

    # ---- fling

    def _start_fling(self, g, now, x, y):
        pts = [p for p in g.samples if p[0] >= now - FLING_WINDOW_MS]
        if not pts or pts[-1][0] != now:
            pts.append((now, x, y))         # the lift frame is the last sample
        if len(pts) < 2:
            return
        vx = _slope([(t, px) for t, px, _ in pts])
        vy = _slope([(t, py) for t, _, py in pts])
        # a component against the total displacement from the start point is dropped
        if vx * (x - g.sx) < 0:
            vx = 0.0
        if vy * (y - g.sy) < 0:
            vy = 0.0
        # the last movement decides the axis if the velocity disagrees with it
        if g.last_axis == "h" and abs(vy) > abs(vx):
            vy = 0.0
        elif g.last_axis == "v" and abs(vx) > abs(vy):
            vx = 0.0
        if max(abs(vx), abs(vy)) > FLING_MIN_V:
            self.fling = [vx, vy, now, g.speed, g.reverse]
            self.fling_next = now + FLING_STEP_MS

    def _stop_fling(self):
        self.fling = None
        self.fling_next = None
