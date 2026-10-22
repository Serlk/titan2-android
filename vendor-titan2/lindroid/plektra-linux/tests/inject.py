#!/usr/bin/python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Check on the phone: "physical" Titan 2 keyboard presses into the plektra-linux service
# via /run/plektra-linux/inject.sock (root). Example:
#   inject.py "W:400 ALT E SYM SYM Q +FN S -FN ENTER"
# W: press for 40 ms, W:400: hold 400 ms, +FN/-FN: press/release, ~300: pause.
import socket, sys, time
NAMES = {"SHIFT": 42, "ALT": 100, "SYM": 253, "FN": 251, "BACK": 158, "DEL": 14, "ENTER": 28,
         "SPACE": 57}
LET = dict(zip("QWERTYUIOP", range(16, 26)))
LET.update(zip("ASDFGHJKL", range(30, 39)))
LET.update(zip("ZXCVBNM", range(44, 51)))
NAMES.update(LET)
s = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
def ev(c, v):
    s.sendto(b"%d %d" % (c, v), "/run/plektra-linux/inject.sock")
for tok in " ".join(sys.argv[1:]).split():
    if tok.startswith("~"):
        time.sleep(int(tok[1:]) / 1000); continue
    if tok[0] in "+-":
        ev(NAMES[tok[1:]], 1 if tok[0] == "+" else 0); time.sleep(0.03); continue
    name, _, hold = tok.partition(":")
    ev(NAMES[name], 1); time.sleep(int(hold or 40) / 1000); ev(NAMES[name], 0); time.sleep(0.08)
