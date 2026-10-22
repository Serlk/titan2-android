# SPDX-License-Identifier: GPL-3.0-or-later
# plektra-linux: Android properties from inside the Lindroid container without getprop/setprop.
# Lindroid passes /dev/__properties__ (bionic property areas, read-only) into the container
# and /dev/socket (the init property_service socket). The area format is bionic prop_area/prop_bt/
# prop_info (system/core/libc/system_properties).

import mmap
import os
import socket
import struct

PROP_DIR = "/dev/__properties__"
HDR = 128                       # prop_area: bytes_used, serial, magic, version, reserved[28]
MAGIC = 0x504F5250
PROP_VALUE_MAX = 92
LONG_FLAG = 1 << 16


class _Area:
    def __init__(self, path):
        with open(path, "rb") as f:
            self.m = mmap.mmap(f.fileno(), 0, prot=mmap.PROT_READ)
        if struct.unpack_from("<I", self.m, 8)[0] != MAGIC:
            raise ValueError(path)

    def _u32(self, off):
        return struct.unpack_from("<I", self.m, HDR + off)[0]

    def _bt(self, off):
        namelen, prop, left, right, children = struct.unpack_from("<5I", self.m, HDR + off)
        name = bytes(self.m[HDR + off + 20:HDR + off + 20 + namelen])
        return name, prop, left, right, children

    def _find_child(self, off, part):
        _, _, _, _, child = self._bt(off)
        cur = child
        for _ in range(4096):           # guards against a cycle in a corrupted area
            if not cur:
                break
            name, _, left, right, _ = self._bt(cur)
            if len(part) < len(name) or (len(part) == len(name) and part < name):
                cur = left
            elif part == name:
                return cur
            else:
                cur = right
        return 0

    def find(self, key):
        """The value or None. Offsets come from a live, shared area: anything out of bounds or
        a loop in the trie means "not found", never an exception."""
        try:
            return self._find(key)
        except (struct.error, IndexError, ValueError):
            return None

    def _find(self, key):
        off = 0
        for part in key.encode().split(b"."):
            off = self._find_child(off, part)
            if not off:
                return None
        prop = self._bt(off)[1]
        if not prop:
            return None
        if HDR + prop + 4 + PROP_VALUE_MAX > len(self.m):
            return None
        # init updates a short value in place: serial bit 0 ("dirty") is set while it writes,
        # the previous value is kept in the backup area right after the root prop_bt (20 bytes);
        # a changed serial after the copy means a concurrent update: read again
        for _ in range(8):
            serial = self._u32(prop)
            if serial & LONG_FLAG:
                loff = struct.unpack_from("<I", self.m, HDR + prop + 4 + 56)[0]
                start = HDR + prop + loff
                end = self.m.find(b"\0", start)
                if start >= len(self.m) or end < 0:
                    return None
                return self.m[start:end].decode("utf-8", "replace")
            ln = min(serial >> 24, PROP_VALUE_MAX - 1)
            src = HDR + 20 if serial & 1 else HDR + prop + 4
            val = bytes(self.m[src:src + ln])
            if self._u32(prop) == serial:
                return val.decode("utf-8", "replace")
        return None


class Props:
    """get(name): searches all areas (property_info is not parsed), caches the area."""

    def __init__(self, base=PROP_DIR):
        self.base = base
        self.areas = {}
        self.where = {}

    def _area(self, fn):
        a = self.areas.get(fn)
        if a is None:
            try:
                a = _Area(os.path.join(self.base, fn))
            except (OSError, ValueError):
                a = False
            self.areas[fn] = a
        return a

    def get(self, name, default=""):
        fn = self.where.get(name)
        if fn:
            a = self._area(fn)
            v = a.find(name) if a else None
            if v is not None:
                return v
        try:
            names = os.listdir(self.base)
        except OSError:
            return default
        for fn in names:
            if fn == "property_info":
                continue
            a = self._area(fn)
            if not a:
                continue
            v = a.find(name)
            if v is not None:
                self.where[name] = fn
                return v
        return default


def set_prop(name, value, sock="/dev/socket/property_service"):
    """PROP_MSG_SETPROP2 to init. True: init accepted it. Permissions are checked by init SELinux."""
    n, v = name.encode(), value.encode()
    msg = struct.pack("<II", 0x00020001, len(n)) + n + struct.pack("<I", len(v)) + v
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.settimeout(2)
        s.connect(sock)
        s.sendall(msg)
        r = s.recv(4)
        return len(r) == 4 and struct.unpack("<I", r)[0] == 0
    except OSError:
        return False
    finally:
        s.close()
