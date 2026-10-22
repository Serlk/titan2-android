# SPDX-License-Identifier: GPL-3.0-or-later
# plektra-linux: a minimal pure-Python D-Bus client (the Lindroid rootfs has no python3-dbus/gi).
# Enough for: an own name on the session bus and receiving calls (the focused window from a KWin script),
# method calls (KWin Scripting, Klipper) and signal subscriptions.

import os
import socket
import struct

ALIGN = {"y": 1, "b": 4, "n": 2, "q": 2, "i": 4, "u": 4, "x": 8, "t": 8, "d": 8, "s": 4,
         "o": 4, "g": 1, "a": 4, "(": 8, "{": 8, "v": 1, "h": 4}
FMT = {"y": "B", "b": "I", "n": "h", "q": "H", "i": "i", "u": "I", "x": "q", "t": "Q", "d": "d",
       "h": "I"}


def _split(sig):
    """'sa{sv}i' -> ['s', 'a{sv}', 'i']"""
    out = []
    i = 0
    while i < len(sig):
        j = _end(sig, i)
        out.append(sig[i:j])
        i = j
    return out


def _end(sig, i):
    c = sig[i]
    if c == "a":
        return _end(sig, i + 1)
    if c in "({":
        close = ")" if c == "(" else "}"
        depth = 0
        for j in range(i, len(sig)):
            if sig[j] in "({":
                depth += 1
            elif sig[j] in ")}":
                depth -= 1
                if depth == 0:
                    return j + 1
        raise ValueError(sig)
    return i + 1


class _W:
    def __init__(self):
        self.b = bytearray()

    def pad(self, a):
        self.b += b"\0" * ((-len(self.b)) % a)

    def put(self, t, v):
        self.pad(ALIGN[t[0]])
        c = t[0]
        if c in FMT:
            if c == "b":
                v = 1 if v else 0
            self.b += struct.pack("<" + FMT[c], v)
        elif c in "so":
            e = v.encode()
            self.b += struct.pack("<I", len(e)) + e + b"\0"
        elif c == "g":
            e = v.encode()
            self.b += struct.pack("<B", len(e)) + e + b"\0"
        elif c == "v":
            sig, val = v
            self.put("g", sig)
            self.put(sig, val)
        elif c == "a":
            et = t[1:]
            self.pad(4)
            lpos = len(self.b)
            self.b += b"\0\0\0\0"
            self.pad(ALIGN[et[0]])
            start = len(self.b)
            if et[0] == "{":
                kt, vt = _split(et[1:-1])
                for k, val in v.items():
                    self.pad(8)
                    self.put(kt, k)
                    self.put(vt, val)
            else:
                for item in v:
                    self.put(et, item)
            struct.pack_into("<I", self.b, lpos, len(self.b) - start)
        elif c == "(":
            self.pad(8)
            for st, val in zip(_split(t[1:-1]), v):
                self.put(st, val)


class _R:
    def __init__(self, b, p=0):
        self.b, self.p = b, p

    def pad(self, a):
        self.p += (-self.p) % a

    def get(self, t):
        self.pad(ALIGN[t[0]])
        c = t[0]
        if c in FMT:
            f = "<" + FMT[c]
            v = struct.unpack_from(f, self.b, self.p)[0]
            self.p += struct.calcsize(f)
            return bool(v) if c == "b" else v
        if c in "so":
            n = struct.unpack_from("<I", self.b, self.p)[0]
            s = self.b[self.p + 4:self.p + 4 + n].decode("utf-8", "replace")
            self.p += 5 + n
            return s
        if c == "g":
            n = self.b[self.p]
            s = self.b[self.p + 1:self.p + 1 + n].decode()
            self.p += 2 + n
            return s
        if c == "v":
            sig = self.get("g")
            return self.get(sig)
        if c == "a":
            n = struct.unpack_from("<I", self.b, self.p)[0]
            self.p += 4
            et = t[1:]
            self.pad(ALIGN[et[0]])
            end = self.p + n
            if et[0] == "{":
                kt, vt = _split(et[1:-1])
                d = {}
                while self.p < end:
                    self.pad(8)
                    k = self.get(kt)
                    d[k] = self.get(vt)
                return d
            out = []
            while self.p < end:
                out.append(self.get(et))
            return out
        if c == "(":
            self.pad(8)
            return tuple(self.get(st) for st in _split(t[1:-1]))
        raise ValueError(t)


class Bus:
    """A synchronous client. fileno() for select; process() parses incoming messages."""

    def __init__(self, address=None):
        address = address or os.environ.get("DBUS_SESSION_BUS_ADDRESS") or \
            "unix:path=/run/user/%d/bus" % os.getuid()
        path = None
        for part in address.split(";")[0].split(":", 1)[1].split(","):
            k, _, v = part.partition("=")
            if k == "path":
                path = v
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(path)
        self.sock.sendall(b"\0AUTH EXTERNAL " + str(os.getuid()).encode().hex().encode() + b"\r\n")
        line = self._line()
        if not line.startswith(b"OK"):
            raise OSError("dbus auth: %r" % line)
        self.sock.sendall(b"BEGIN\r\n")
        self.serial = 0
        self.buf = b""
        self.pending = {}           # serial -> reply
        self.methods = {}           # (path, iface, member) -> fn(*args) -> (sig, values)|None
        self.signals = []           # (iface, member, fn)
        self.unique = self.call("org.freedesktop.DBus", "/org/freedesktop/DBus",
                                "org.freedesktop.DBus", "Hello")[0]

    def _line(self):
        b = b""
        while not b.endswith(b"\r\n"):
            c = self.sock.recv(1)
            if not c:
                raise OSError("dbus: closed")
            b += c
        return b

    def fileno(self):
        return self.sock.fileno()

    def _send(self, mtype, fields, sig="", args=(), flags=0):
        self.serial += 1
        body = _W()
        for t, v in zip(_split(sig), args):
            body.put(t, v)
        if sig:
            fields[8] = ("g", sig)
        h = _W()
        h.b += struct.pack("<cBBBII", b"l", mtype, flags, 1, len(body.b), self.serial)
        h.put("a(yv)", [(k, v) for k, v in fields.items()])
        h.pad(8)
        self.sock.sendall(bytes(h.b) + bytes(body.b))
        return self.serial

    def call(self, dest, path, iface, member, sig="", args=(), wait=True):
        s = self._send(1, {1: ("o", path), 2: ("s", iface), 3: ("s", member), 6: ("s", dest)},
                       sig, args, flags=0 if wait else 1)
        if not wait:
            return None
        while s not in self.pending:
            self._read()
        ok, val = self.pending.pop(s)
        if not ok:
            raise OSError("dbus %s.%s: %s" % (iface, member, val))
        return val

    def emit(self, path, iface, member, sig="", args=()):
        self._send(4, {1: ("o", path), 2: ("s", iface), 3: ("s", member)}, sig, args)

    def add_match(self, rule):
        self.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                  "AddMatch", "s", (rule,))

    def request_name(self, name):
        return self.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                         "RequestName", "su", (name, 4))[0]

    def _read(self):
        d = self.sock.recv(65536)
        if not d:
            raise OSError("dbus: closed")
        self.buf += d
        self._parse()

    def process(self):
        """Call when fileno() is ready for reading."""
        self._read()

    def _parse(self):
        while len(self.buf) >= 16:
            blen, serial, flen = struct.unpack_from("<III", self.buf, 4)
            hlen = 16 + flen
            hlen += (-hlen) % 8
            total = hlen + blen
            if len(self.buf) < total:
                return
            msg = self.buf[:total]
            self.buf = self.buf[total:]
            mtype = msg[1]
            r = _R(msg, 12)
            fields = dict(r.get("a(yv)"))
            sig = fields.get(8, "")
            br = _R(msg[hlen:])
            try:
                args = [br.get(t) for t in _split(sig)]
            except Exception:
                args = []
            if mtype in (2, 3):
                self.pending[fields.get(5)] = (mtype == 2, args)
            elif mtype == 4:
                for iface, member, fn in self.signals:
                    if fields.get(2) == iface and fields.get(3) == member:
                        fn(*args)
            elif mtype == 1:
                key = (fields.get(1), fields.get(2), fields.get(3))
                fn = self.methods.get(key)
                rep = {5: ("u", serial), 6: ("s", fields.get(7))}
                if fn is None:
                    rep[4] = ("s", "org.freedesktop.DBus.Error.UnknownMethod")
                    self._send(3, rep, "s", ("unknown method",))
                    continue
                try:
                    res = fn(*args)
                except Exception as e:
                    rep[4] = ("s", "org.freedesktop.DBus.Error.Failed")
                    self._send(3, rep, "s", (str(e),))
                    continue
                if not (msg[2] & 1):           # NO_REPLY_EXPECTED
                    sig, vals = res if res else ("", ())
                    self._send(2, rep, sig, vals)
