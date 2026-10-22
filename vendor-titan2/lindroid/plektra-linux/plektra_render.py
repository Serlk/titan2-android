# SPDX-License-Identifier: GPL-3.0-or-later
# plektra-linux: drawing the keyboard bar without external libraries (the Lindroid rootfs has no
# PIL/cairo/PyQt). A TrueType (glyf) rasterizer for regular characters and extraction of color
# emoji from NotoColorEmoji (CBDT/CBLC, PNG inside) in pure Python + zlib.

import struct
import zlib

FONT_PATHS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
              "/usr/share/fonts/TTF/DejaVuSans.ttf",
              "/usr/share/fonts/dejavu/DejaVuSans.ttf"]
EMOJI_PATHS = ["/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
               "/usr/share/fonts/noto/NotoColorEmoji.ttf",
               "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf"]


def _first(paths):
    import os
    for p in paths:
        if os.path.isfile(p):
            return p
    return None


class _Font:
    def __init__(self, path):
        with open(path, "rb") as f:
            self.d = f.read()
        d = self.d
        n = struct.unpack_from(">H", d, 4)[0]
        self.t = {}
        for i in range(n):
            tag, _, off, ln = struct.unpack_from(">4sIII", d, 12 + 16 * i)
            self.t[tag.decode("latin-1")] = (off, ln)
        self.cmap = self._cmap()

    def _cmap(self):
        d = self.d
        off = self.t["cmap"][0]
        n = struct.unpack_from(">H", d, off + 2)[0]
        best = None
        for i in range(n):
            pid, eid, so = struct.unpack_from(">HHI", d, off + 4 + 8 * i)
            fmt = struct.unpack_from(">H", d, off + so)[0]
            if fmt == 12 and (best is None or best[0] != 12):
                best = (12, off + so)
            elif fmt == 4 and best is None and pid in (0, 3):
                best = (4, off + so)
        m = {}
        if not best:
            return m
        fmt, o = best
        if fmt == 12:
            ng = struct.unpack_from(">I", d, o + 12)[0]
            for i in range(ng):
                a, b, g = struct.unpack_from(">III", d, o + 16 + 12 * i)
                if b - a > 70000:
                    continue
                for c in range(a, b + 1):
                    m[c] = g + c - a
        else:
            segx2 = struct.unpack_from(">H", d, o + 6)[0]
            seg = segx2 // 2
            ends = o + 14
            starts = ends + segx2 + 2
            deltas = starts + segx2
            ranges = deltas + segx2
            for i in range(seg):
                e = struct.unpack_from(">H", d, ends + 2 * i)[0]
                s = struct.unpack_from(">H", d, starts + 2 * i)[0]
                dl = struct.unpack_from(">h", d, deltas + 2 * i)[0]
                ro = struct.unpack_from(">H", d, ranges + 2 * i)[0]
                for c in range(s, e + 1):
                    if c == 0xFFFF:
                        continue
                    if ro == 0:
                        g = (c + dl) & 0xFFFF
                    else:
                        ga = ranges + 2 * i + ro + 2 * (c - s)
                        g = struct.unpack_from(">H", d, ga)[0]
                        if g:
                            g = (g + dl) & 0xFFFF
                    if g:
                        m[c] = g
        return m


class OutlineFont(_Font):
    def __init__(self, path):
        super().__init__(path)
        d = self.d
        ho = self.t["head"][0]
        self.upem = struct.unpack_from(">H", d, ho + 18)[0]
        self.loca_long = struct.unpack_from(">h", d, ho + 50)[0] == 1
        hh = self.t["hhea"][0]
        self.asc, self.desc = struct.unpack_from(">hh", d, hh + 4)
        self.nhm = struct.unpack_from(">H", d, hh + 34)[0]
        self.cache = {}

    def _loca(self, g):
        o = self.t["loca"][0]
        if self.loca_long:
            a, b = struct.unpack_from(">II", self.d, o + 4 * g)
        else:
            a, b = struct.unpack_from(">HH", self.d, o + 2 * g)
            a, b = a * 2, b * 2
        return a, b

    def advance(self, g):
        o = self.t["hmtx"][0]
        i = min(g, self.nhm - 1)
        return struct.unpack_from(">H", self.d, o + 4 * i)[0]

    def contours(self, g, depth=0):
        """List of contours [(x, y, on_curve), ...] in font units."""
        a, b = self._loca(g)
        if a == b or depth > 4:
            return []
        d = self.d
        o = self.t["glyf"][0] + a
        nc = struct.unpack_from(">h", d, o)[0]
        if nc < 0:
            res = []
            p = o + 10
            while True:
                flags, gi = struct.unpack_from(">HH", d, p)
                p += 4
                if flags & 1:
                    dx, dy = struct.unpack_from(">hh", d, p)
                    p += 4
                else:
                    dx, dy = struct.unpack_from(">bb", d, p)
                    p += 2
                sx = sy = 1.0
                s01 = s10 = 0.0
                if flags & 8:
                    sx = sy = struct.unpack_from(">h", d, p)[0] / 16384.0
                    p += 2
                elif flags & 0x40:
                    sx, sy = (v / 16384.0 for v in struct.unpack_from(">hh", d, p))
                    p += 4
                elif flags & 0x80:
                    sx, s01, s10, sy = (v / 16384.0 for v in struct.unpack_from(">hhhh", d, p))
                    p += 8
                for c in self.contours(gi, depth + 1):
                    res.append([(x * sx + y * s10 + dx, x * s01 + y * sy + dy, on)
                                for x, y, on in c])
                if not flags & 0x20:
                    break
            return res
        ends = struct.unpack_from(">%dH" % nc, d, o + 10)
        npts = ends[-1] + 1 if nc else 0
        p = o + 10 + 2 * nc
        il = struct.unpack_from(">H", d, p)[0]
        p += 2 + il
        flags = []
        while len(flags) < npts:
            f = d[p]
            p += 1
            flags.append(f)
            if f & 8:
                r = d[p]
                p += 1
                flags.extend([f] * r)
        flags = flags[:npts]
        xs, ys = [], []
        for coords, short, same in ((xs, 2, 16), (ys, 4, 32)):
            v = 0
            for f in flags:
                if f & short:
                    dv = d[p]
                    p += 1
                    v += dv if f & same else -dv
                elif not f & same:
                    v += struct.unpack_from(">h", d, p)[0]
                    p += 2
                coords.append(v)
        res = []
        s = 0
        for e in ends:
            res.append([(xs[i], ys[i], flags[i] & 1) for i in range(s, e + 1)])
            s = e + 1
        return res

    @staticmethod
    def _flatten(c):
        """A contour with quadratic curves -> a polyline."""
        n = len(c)
        if n < 2:
            return []
        pts = []
        # start at an on-curve point
        start = 0
        while start < n and not c[start][2]:
            start += 1
        if start == n:
            x = (c[0][0] + c[1][0]) / 2
            y = (c[0][1] + c[1][1]) / 2
            c = [(x, y, 1)] + c
            n += 1
            start = 0
        seq = c[start:] + c[:start] + [c[start]]
        prev = seq[0]
        pts.append((prev[0], prev[1]))
        ctrl = None
        for x, y, on in seq[1:]:
            if on:
                if ctrl is None:
                    pts.append((x, y))
                else:
                    OutlineFont._quad(pts, prev, ctrl, (x, y))
                    ctrl = None
                prev = (x, y)
            else:
                if ctrl is not None:
                    mid = ((ctrl[0] + x) / 2, (ctrl[1] + y) / 2)
                    OutlineFont._quad(pts, prev, ctrl, mid)
                    prev = mid
                ctrl = (x, y)
        if ctrl is not None:
            OutlineFont._quad(pts, prev, ctrl, seq[0][:2])
        return pts

    @staticmethod
    def _quad(pts, p0, p1, p2, steps=6):
        for i in range(1, steps + 1):
            t = i / steps
            u = 1 - t
            pts.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                        u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))

    def glyph(self, ch, px):
        """-> (width, height, coverage bytes, left, top_from_baseline, advance) in pixels."""
        key = (ch, px)
        if key in self.cache:
            return self.cache[key]
        g = self.cmap.get(ord(ch))
        if g is None:
            self.cache[key] = None
            return None
        sc = px / self.upem
        polys = [self._flatten(c) for c in self.contours(g)]
        polys = [[(x * sc, y * sc) for x, y in p] for p in polys if len(p) > 2]
        adv = self.advance(g) * sc
        if not polys:
            r = (0, 0, b"", 0, 0, adv)
            self.cache[key] = r
            return r
        minx = min(x for p in polys for x, _ in p)
        maxx = max(x for p in polys for x, _ in p)
        miny = min(y for p in polys for _, y in p)
        maxy = max(y for p in polys for _, y in p)
        x0, y0 = int(minx) - 1, int(miny) - 1
        w, h = int(maxx) - x0 + 2, int(maxy) - y0 + 2
        edges = []
        for p in polys:
            for i in range(len(p)):
                ax, ay = p[i]
                bx, by = p[(i + 1) % len(p)]
                if ay == by:
                    continue
                dirn = 1 if by > ay else -1
                if ay > by:
                    ax, ay, bx, by = bx, by, ax, ay
                edges.append((ay, by, ax, (bx - ax) / (by - ay), dirn))
        SS = 4
        acc = [0] * (w * h)
        for row in range(h * SS):
            # y grows upwards in the font; row 0 is the top of the bitmap
            sy = y0 + h - (row + 0.5) / SS
            xs = []
            for ay, by, ax, k, dirn in edges:
                if ay <= sy < by:
                    xs.append((ax + (sy - ay) * k, dirn))
            if not xs:
                continue
            xs.sort()
            wind = 0
            base = (row // SS) * w
            for i in range(len(xs) - 1):
                wind += xs[i][1]
                if wind:
                    xa = (xs[i][0] - x0) * SS
                    xb = (xs[i + 1][0] - x0) * SS
                    a, b = int(xa + 0.5), int(xb + 0.5)
                    for s in range(max(a, 0), min(b, w * SS)):
                        acc[base + s // SS] += 1
        full = SS * SS
        cov = bytes(min(255, v * 255 // full) for v in acc)
        r = (w, h, cov, x0, y0 + h, adv)
        self.cache[key] = r
        return r


def _png_decode(data):
    """PNG -> (w, h, RGBA bytes). 8-bit support: RGBA, RGB, palette (+tRNS), gray."""
    p = 8
    w = h = 0
    ctype = 0
    pal = b""
    trns = b""
    idat = []
    while p < len(data):
        ln, typ = struct.unpack_from(">I4s", data, p)
        body = data[p + 8:p + 8 + ln]
        p += 12 + ln
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack_from(">IIBB", body)
            if depth != 8:
                raise ValueError("PNG depth %d" % depth)
        elif typ == b"PLTE":
            pal = body
        elif typ == b"tRNS":
            trns = body
        elif typ == b"IDAT":
            idat.append(body)
        elif typ == b"IEND":
            break
    raw = zlib.decompress(b"".join(idat))
    bpp = {6: 4, 2: 3, 3: 1, 0: 1, 4: 2}[ctype]
    stride = w * bpp
    out = bytearray(h * stride)
    prev = bytearray(stride)
    i = 0
    for y in range(h):
        ft = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        if ft == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 255
        elif ft == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif ft == 3:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif ft == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    if ctype == 6:
        return w, h, bytes(out)
    rgba = bytearray(w * h * 4)
    for k in range(w * h):
        if ctype == 3:
            idx = out[k]
            rgba[4 * k:4 * k + 3] = pal[3 * idx:3 * idx + 3]
            rgba[4 * k + 3] = trns[idx] if idx < len(trns) else 255
        elif ctype == 2:
            rgba[4 * k:4 * k + 3] = out[3 * k:3 * k + 3]
            rgba[4 * k + 3] = 255
        elif ctype == 0:
            v = out[k]
            rgba[4 * k:4 * k + 4] = bytes((v, v, v, 255))
        else:
            v = out[2 * k]
            rgba[4 * k:4 * k + 4] = bytes((v, v, v, out[2 * k + 1]))
    return w, h, bytes(rgba)


class EmojiFont(_Font):
    """NotoColorEmoji: CBLC/CBDT, glyph data of formats 17/18/19 (PNG)."""

    def __init__(self, path):
        super().__init__(path)
        self.cache = {}
        self.loc = {}
        d = self.d
        lo = self.t["CBLC"][0]
        nsz = struct.unpack_from(">I", d, lo + 4)[0]
        # take the first (usually the only, 109 ppem) size table
        best = None
        for i in range(nsz):
            bo = lo + 8 + 48 * i
            arr_off, _, n_sub = struct.unpack_from(">III", d, bo)
            ppem = d[bo + 45]
            if best is None or ppem > best[2]:
                best = (arr_off, n_sub, ppem)
        self.ppem = best[2]
        arr_off, n_sub, _ = best
        cdt = self.t["CBDT"][0]
        for j in range(n_sub):
            first, last, add = struct.unpack_from(">HHI", d, lo + arr_off + 8 * j)
            so = lo + arr_off + add
            ifmt, dfmt, img_off = struct.unpack_from(">HHI", d, so)
            if ifmt == 1:
                for g in range(first, last + 1):
                    a, b = struct.unpack_from(">II", d, so + 8 + 4 * (g - first))
                    if b > a:
                        self.loc[g] = (dfmt, cdt + img_off + a)
            elif ifmt == 3:
                for g in range(first, last + 1):
                    a, b = struct.unpack_from(">HH", d, so + 8 + 2 * (g - first))
                    if b > a:
                        self.loc[g] = (dfmt, cdt + img_off + a)
            elif ifmt == 2:
                size = struct.unpack_from(">I", d, so + 8)[0]
                for g in range(first, last + 1):
                    self.loc[g] = (dfmt, cdt + img_off + size * (g - first))
            elif ifmt in (4, 5):
                # 4: (glyph, offset) pairs; 5: a fixed size + a glyph list
                if ifmt == 4:
                    ng = struct.unpack_from(">I", d, so + 8)[0]
                    for k in range(ng):
                        g, a = struct.unpack_from(">HH", d, so + 12 + 4 * k)
                        self.loc[g] = (dfmt, cdt + img_off + a)
                else:
                    size = struct.unpack_from(">I", d, so + 8)[0]
                    ng = struct.unpack_from(">I", d, so + 20)[0]
                    for k in range(ng):
                        g = struct.unpack_from(">H", d, so + 24 + 2 * k)[0]
                        self.loc[g] = (dfmt, cdt + img_off + size * k)

    def has(self, ch):
        g = self.cmap.get(ord(ch))
        return g is not None and g in self.loc

    def bitmap(self, ch, px):
        """-> (w, h, RGBA premultiplied) of px x px size, or None."""
        key = (ch, px)
        if key in self.cache:
            return self.cache[key]
        g = self.cmap.get(ord(ch))
        if g is None or g not in self.loc:
            self.cache[key] = None
            return None
        fmt, o = self.loc[g]
        d = self.d
        if fmt == 17:
            ln = struct.unpack_from(">I", d, o + 5)[0]
            png = d[o + 9:o + 9 + ln]
        elif fmt == 18:
            ln = struct.unpack_from(">I", d, o + 8)[0]
            png = d[o + 12:o + 12 + ln]
        elif fmt == 19:
            ln = struct.unpack_from(">I", d, o)[0]
            png = d[o + 4:o + 4 + ln]
        else:
            self.cache[key] = None
            return None
        w, h, rgba = _png_decode(png)
        r = (px, px, _scale_rgba(rgba, w, h, px, px))
        self.cache[key] = r
        return r


def _scale_rgba(src, w, h, tw, th):
    """Downscaling with averaging (box filter) -> BGRA premultiplied (wl_shm ARGB8888 LE order)."""
    s = max(w / tw, h / th)
    ox = (tw - w / s) / 2
    oy = (th - h / s) / 2
    out = bytearray(tw * th * 4)
    for ty in range(th):
        y0 = int((ty - oy) * s)
        y1 = int((ty + 1 - oy) * s)
        if y1 <= 0 or y0 >= h:
            continue
        y0, y1 = max(y0, 0), min(max(y1, y0 + 1), h)
        for tx in range(tw):
            x0 = int((tx - ox) * s)
            x1 = int((tx + 1 - ox) * s)
            if x1 <= 0 or x0 >= w:
                continue
            x0, x1 = max(x0, 0), min(max(x1, x0 + 1), w)
            r = g = b = a = 0
            n = 0
            for yy in range(y0, y1, max(1, (y1 - y0) // 3)):
                row = yy * w * 4
                for xx in range(x0, x1, max(1, (x1 - x0) // 3)):
                    i = row + xx * 4
                    al = src[i + 3]
                    r += src[i] * al
                    g += src[i + 1] * al
                    b += src[i + 2] * al
                    a += al
                    n += 1
            if not n:
                continue
            o = (ty * tw + tx) * 4
            out[o] = b // (255 * n)
            out[o + 1] = g // (255 * n)
            out[o + 2] = r // (255 * n)
            out[o + 3] = a // n
    return bytes(out)


class Canvas:
    """A BGRA premultiplied buffer (wl_shm ARGB8888 on little-endian)."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.buf = bytearray(w * h * 4)

    @staticmethod
    def px(argb):
        a = (argb >> 24) & 255
        r = ((argb >> 16) & 255) * a // 255
        g = ((argb >> 8) & 255) * a // 255
        b = (argb & 255) * a // 255
        return bytes((b, g, r, a))

    def fill(self, argb):
        self.buf[:] = self.px(argb) * (self.w * self.h)

    def rect(self, x, y, w, h, argb, radius=0):
        x0, y0 = max(0, int(x)), max(0, int(y))
        x1, y1 = min(self.w, int(x + w)), min(self.h, int(y + h))
        if x1 <= x0 or y1 <= y0:
            return
        p = self.px(argb)
        r = int(radius)
        for yy in range(y0, y1):
            inset = 0
            if r:
                dy = 0
                if yy < y + r:
                    dy = y + r - yy - 0.5
                elif yy >= y + h - r:
                    dy = yy + 0.5 - (y + h - r)
                if dy > 0:
                    inset = int(r - (max(0.0, r * r - dy * dy)) ** 0.5 + 0.5)
            a, b = max(x0, int(x) + inset), min(x1, int(x + w) - inset)
            if b > a:
                self.buf[(yy * self.w + a) * 4:(yy * self.w + b) * 4] = p * (b - a)

    def blend_mask(self, x, y, w, h, cov, argb):
        cr, cg, cb = (argb >> 16) & 255, (argb >> 8) & 255, argb & 255
        ca = (argb >> 24) & 255
        buf = self.buf
        for yy in range(h):
            ty = y + yy
            if ty < 0 or ty >= self.h:
                continue
            for xx in range(w):
                c = cov[yy * w + xx]
                if not c:
                    continue
                tx = x + xx
                if tx < 0 or tx >= self.w:
                    continue
                a = c * ca // 255
                o = (ty * self.w + tx) * 4
                ia = 255 - a
                buf[o] = (cb * a + buf[o] * ia) // 255
                buf[o + 1] = (cg * a + buf[o + 1] * ia) // 255
                buf[o + 2] = (cr * a + buf[o + 2] * ia) // 255
                buf[o + 3] = a + buf[o + 3] * ia // 255

    def blend_bgra(self, x, y, w, h, src):
        buf = self.buf
        for yy in range(h):
            ty = y + yy
            if ty < 0 or ty >= self.h:
                continue
            for xx in range(w):
                i = (yy * w + xx) * 4
                a = src[i + 3]
                if not a:
                    continue
                tx = x + xx
                if tx < 0 or tx >= self.w:
                    continue
                o = (ty * self.w + tx) * 4
                ia = 255 - a
                buf[o] = src[i] + buf[o] * ia // 255
                buf[o + 1] = src[i + 1] + buf[o + 1] * ia // 255
                buf[o + 2] = src[i + 2] + buf[o + 2] * ia // 255
                buf[o + 3] = a + buf[o + 3] * ia // 255


class TextRenderer:
    def __init__(self, font_path=None, emoji_path=None):
        fp = font_path or _first(FONT_PATHS)
        ep = emoji_path or _first(EMOJI_PATHS)
        self.font = OutlineFont(fp) if fp else None
        try:
            self.emoji = EmojiFont(ep) if ep else None
        except Exception:
            self.emoji = None

    def draw_centered(self, cv, text, cx, cy, px, argb):
        """Draws a string (usually one character or emoji) centered at (cx, cy)."""
        items = []
        width = 0
        for ch in text:
            if ord(ch) in (0xFE0F, 0xFE0E, 0x200D):
                continue
            if self.emoji and (ord(ch) >= 0x2190 or not (self.font and ord(ch) in self.font.cmap)) \
                    and self.emoji.has(ch):
                items.append(("e", ch, px))
                width += px
            elif self.font:
                g = self.font.glyph(ch, px)
                if g:
                    items.append(("g", ch, g))
                    width += g[5]
        x = cx - width / 2
        for kind, ch, g in items:
            if kind == "e":
                bm = self.emoji.bitmap(ch, px)
                if bm:
                    cv.blend_bgra(int(x), int(cy - px / 2), bm[0], bm[1], bm[2])
                x += px
            else:
                w, h, cov, left, top, adv = g
                base = cy + px * 0.36       # baseline: center ~ height of lowercase/digits
                if w:
                    cv.blend_mask(int(x + left), int(base - top), w, h, cov, argb)
                x += adv

    def draw_text(self, cv, text, x, base, px, argb):
        for ch in text:
            g = self.font.glyph(ch, px) if self.font else None
            if not g:
                continue
            w, h, cov, left, top, adv = g
            if w:
                cv.blend_mask(int(x + left), int(base - top), w, h, cov, argb)
            x += adv
        return x


def png_encode(cv):
    """For checking on a PC: Canvas -> PNG (RGBA)."""
    raw = bytearray()
    for y in range(cv.h):
        raw.append(0)
        row = cv.buf[y * cv.w * 4:(y + 1) * cv.w * 4]
        for i in range(0, len(row), 4):
            b, g, r, a = row[i:i + 4]
            if a:
                r, g, b = r * 255 // a, g * 255 // a, b * 255 // a
            raw += bytes((r, g, b, a))

    def chunk(t, body):
        return struct.pack(">I", len(body)) + t + body + struct.pack(">I", zlib.crc32(t + body))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", cv.w, cv.h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))
