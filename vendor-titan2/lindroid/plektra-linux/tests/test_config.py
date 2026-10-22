# SPDX-License-Identifier: GPL-3.0-or-later
# Settings: merging defaults/user, settings app commands, rereading by the service.
import importlib.util, json, os, subprocess, sys, tempfile, unittest
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
import plektra_config as C
spec = importlib.util.spec_from_file_location("plektra", os.path.join(ROOT, "plektra-linux.py"))
P = importlib.util.module_from_spec(spec)
spec.loader.exec_module(P)


def cli(home, *args):
    env = dict(os.environ, HOME=home)
    return subprocess.run([sys.executable, "-I", "-B", os.path.join(ROOT, "plektra_config.py")] +
                          list(args), env=env, capture_output=True, text=True, check=True).stdout


class ConfigTest(unittest.TestCase):
    def test_set_get_reset(self):
        with tempfile.TemporaryDirectory() as h:
            patch = json.dumps({"long_press_ms": 450, "touchpad": {"mode": "mouse_left"}})
            cli(h, "sethex", patch.encode().hex())
            d = json.loads(cli(h, "get"))
            self.assertEqual(d["effective"]["long_press_ms"], 450)
            self.assertEqual(d["effective"]["touchpad"]["mode"], "mouse_left")
            self.assertTrue(d["effective"]["touchpad"]["inertia"])       # merged by key
            st1 = C.stamp(h)
            cli(h, "sethex", json.dumps({"long_press_ms": None}).encode().hex())
            self.assertEqual(C.load(h)["long_press_ms"], 300)            # null = default
            self.assertNotEqual(st1, C.stamp(h))

    def test_user_map(self):
        with tempfile.TemporaryDirectory() as h:
            cli(h, "setmaphex", "sym_key_mappings.json", json.dumps({"KEYCODE_Q": "Ω"}).encode().hex())
            cfg = C.load(h)
            t = P.Tables(cfg)
            self.assertEqual(t.emoji[P.KEY["KEY_Q"]], "Ω")
            cli(h, "resetmap", "sym_key_mappings.json")
            self.assertEqual(P.Tables(C.load(h)).emoji[P.KEY["KEY_Q"]], "😀")

    def test_engine_reload(self):
        cfg = C.load(None)
        cfg["mappings_dirs"] = [os.path.join(ROOT, "mappings")]
        e = P.Engine(cfg, P.Tables(cfg), P.Output(lambda c, v: None, {}), None, lambda: 0)
        e.set_app("org.kde.konsole")
        cfg2 = dict(cfg, long_press_ms=700, profiles=[])
        e.reload(cfg2, P.Tables(cfg2))
        self.assertEqual(e.lp_ms, 700)
        self.assertEqual(e.cfg.get("fn_tap"), "auto")        # the terminal profile is gone


if __name__ == "__main__":
    unittest.main(verbosity=1)
