import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-pad-defaults"

KEYBOARD = {"backend": "WindowKeyboard", "id": "0", "player_index": "Player1", "controller_type": "ProController", "version": 1}


def crc16_arc(data):
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


class PadDefaultsRyujinxTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name) / "home"
        self.sysfs = Path(self.tmp.name) / "input"
        self.home.mkdir()
        self.sysfs.mkdir()

    def pad(self, node, name="Xbox 360 Controller (%d)", vendor="045e", product="028e"):
        base = self.sysfs / f"input{node}"
        (base / "id").mkdir(parents=True)
        (base / "name").write_text((name % node if "%d" in name else name) + "\n")
        for key, value in (("bustype", "0003"), ("vendor", vendor), ("product", product), ("version", "0110")):
            (base / "id" / key).write_text(value + "\n")

    def config(self, folder=".config/Ryujinx", players=(KEYBOARD,)):
        cfg = self.home / folder / "Config.json"
        cfg.parent.mkdir(parents=True, exist_ok=True)
        cfg.write_text(json.dumps({"version": 70, "start_fullscreen": False, "input_config": list(players)}))
        return cfg

    def run_script(self):
        env = {"PATH": "/usr/bin:/bin", "HOME": str(self.home), "DROIDDECK_INPUT_CLASS": str(self.sysfs)}
        return subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60)

    def player1(self, cfg):
        players = [c for c in json.loads(cfg.read_text())["input_config"] if c["player_index"] == "Player1"]
        self.assertEqual(1, len(players))
        return players[0]

    def expected_id(self, name):
        return "0-%04x0003-045e-0000-8e02-000010010000" % crc16_arc(name.encode())

    def test_the_checksum_is_the_one_sdl_uses(self):
        self.assertEqual(0xBB3D, crc16_arc(b"123456789"))

    def test_the_stock_keyboard_gives_way_to_the_pad(self):
        self.pad(3)
        cfg = self.config()
        out = self.run_script()
        self.assertIn("Ryujinx: Player 1 on the controller", out.stdout)
        player = self.player1(cfg)
        self.assertEqual("GamepadSDL2", player["backend"])
        self.assertEqual(self.expected_id("Xbox 360 Controller (3)"), player["id"])
        self.assertEqual("B", player["right_joycon"]["button_a"])
        self.assertEqual("LeftTrigger", player["left_joycon"]["button_zl"])
        self.assertEqual(70, json.loads(cfg.read_text())["version"])

    def test_a_flatpak_install_is_found_too(self):
        self.pad(0)
        cfg = self.config(".var/app/io.github.ryubing.Ryujinx/config/Ryujinx")
        self.run_script()
        self.assertEqual(self.expected_id("Xbox 360 Controller (0)"), self.player1(cfg)["id"])

    def test_the_lowest_pad_is_player_one_and_other_devices_are_not_pads(self):
        self.pad(7)
        self.pad(4)
        self.pad(1, name="Some Keyboard", vendor="1234", product="5678")
        cfg = self.config()
        self.run_script()
        self.assertEqual(self.expected_id("Xbox 360 Controller (4)"), self.player1(cfg)["id"])

    def test_a_controller_the_user_set_up_is_never_replaced(self):
        self.pad(0)
        own = dict(KEYBOARD, backend="GamepadSDL2", id="0-own")
        cfg = self.config(players=(own,))
        self.run_script()
        self.assertEqual("0-own", self.player1(cfg)["id"])
        cfg.write_text(json.dumps({"input_config": [KEYBOARD]}))
        self.run_script()
        self.assertEqual("WindowKeyboard", self.player1(cfg)["backend"])

    def test_the_id_follows_the_pad_until_the_user_changes_it(self):
        self.pad(0)
        cfg = self.config()
        self.run_script()
        (self.sysfs / "input0").rename(self.sysfs / "input2")
        (self.sysfs / "input2/name").write_text("Xbox 360 Controller (2)\n")
        self.run_script()
        self.assertEqual(self.expected_id("Xbox 360 Controller (2)"), self.player1(cfg)["id"])
        data = json.loads(cfg.read_text())
        data["input_config"][0]["id"] = "0-mine"
        cfg.write_text(json.dumps(data))
        (self.sysfs / "input2").rename(self.sysfs / "input5")
        self.run_script()
        self.assertEqual("0-mine", self.player1(cfg)["id"])

    def test_nothing_is_written_without_a_pad_or_a_config(self):
        cfg = self.config()
        before = cfg.read_text()
        self.run_script()
        self.assertEqual(before, cfg.read_text())
        self.pad(0)
        cfg.unlink()
        self.run_script()
        self.assertFalse(cfg.exists())

    def test_the_switch_folder_is_listed_once_and_without_a_pad(self):
        cfg = self.config()
        data = json.loads(cfg.read_text())
        data["game_dirs"] = []
        cfg.write_text(json.dumps(data))
        out = self.run_script()
        self.assertIn("Ryujinx: games listed from the ROMs folder", out.stdout)
        self.assertEqual(["/root/ROMs/switch"], json.loads(cfg.read_text())["game_dirs"])
        self.assertEqual("WindowKeyboard", self.player1(cfg)["backend"])
        data["game_dirs"] = []
        cfg.write_text(json.dumps(data))
        self.run_script()
        self.assertEqual([], json.loads(cfg.read_text())["game_dirs"])

    def test_game_folders_the_user_chose_are_kept_and_the_pad_is_still_bound(self):
        self.pad(0)
        cfg = self.config()
        data = json.loads(cfg.read_text())
        data["game_dirs"] = ["/root/Storage/Switch"]
        cfg.write_text(json.dumps(data))
        out = self.run_script()
        self.assertEqual(["/root/Storage/Switch"], json.loads(cfg.read_text())["game_dirs"])
        self.assertEqual("GamepadSDL2", self.player1(cfg)["backend"])
        self.assertNotIn("games listed", out.stdout)

    def test_other_players_are_kept(self):
        self.pad(0)
        second = dict(KEYBOARD, player_index="Player2", id="1")
        cfg = self.config(players=(KEYBOARD, second))
        self.run_script()
        players = json.loads(cfg.read_text())["input_config"]
        self.assertEqual(["Player2", "Player1"], [p["player_index"] for p in players])


if __name__ == "__main__":
    unittest.main()
