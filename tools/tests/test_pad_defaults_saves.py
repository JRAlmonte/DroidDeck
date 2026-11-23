import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-pad-defaults"


class PadDefaultsSavesTest(unittest.TestCase):
    def run_script(self, home):
        env = {"PATH": "/usr/bin:/bin", "HOME": str(home)}
        return subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60)

    def keys(self, path):
        return dict((l.split("=", 1)[0].strip(), l.split("=", 1)[1].strip().strip('"'))
                    for l in path.read_text().splitlines() if "=" in l)

    def test_a_new_retroarch_config_keeps_saves_in_its_own_folders(self):
        with tempfile.TemporaryDirectory() as home:
            self.run_script(home)
            keys = self.keys(Path(home) / ".config/retroarch/retroarch.cfg")
        self.assertEqual("~/.config/retroarch/saves", keys["savefile_directory"])
        self.assertEqual("false", keys["sort_savefiles_enable"])

    def test_save_folders_the_user_chose_in_retroarch_are_kept(self):
        with tempfile.TemporaryDirectory() as home:
            cfg = Path(home) / ".config/retroarch/retroarch.cfg"
            cfg.parent.mkdir(parents=True)
            cfg.write_text('input_joypad_driver = "udev"\nsavefile_directory = "/root/ROMs/saves"\nsavefiles_in_content_dir = "true"\n')
            self.run_script(home)
            keys = self.keys(cfg)
        self.assertEqual("sdl2", keys["input_joypad_driver"])
        self.assertEqual("/root/ROMs/saves", keys["savefile_directory"])
        self.assertEqual("true", keys["savefiles_in_content_dir"])
        self.assertEqual("~/.config/retroarch/states", keys["savestate_directory"])

    def duckstation(self, home, cards):
        ini = Path(home) / ".config/duckstation/settings.ini"
        ini.parent.mkdir(parents=True)
        ini.write_text("[Main]\nSetupWizardIncomplete = false\n\n[MemoryCards]\n" + cards + "\n")
        self.run_script(home)
        return ini.read_text()

    def test_duckstation_without_cards_names_them_after_the_games_file(self):
        with tempfile.TemporaryDirectory() as home:
            self.assertIn("Card1Type = PerGameFileTitle", self.duckstation(home, "Card1Type = PerGameTitle"))

    def test_duckstation_with_cards_in_a_folder_of_its_own_keeps_its_setting(self):
        with tempfile.TemporaryDirectory() as home:
            text = self.duckstation(home, "Card1Type = PerGameTitle\nDirectory = /root/ROMs/psx/cards")
        self.assertIn("Card1Type = PerGameTitle", text)
        self.assertNotIn("PerGameFileTitle", text)

    def test_duckstation_with_cards_already_written_keeps_its_setting(self):
        with tempfile.TemporaryDirectory() as home:
            cards = Path(home) / ".config/duckstation/memcards"
            cards.mkdir(parents=True)
            (cards / "Game_1.mcd").write_bytes(b"card")
            ini = Path(home) / ".config/duckstation/settings.ini"
            ini.write_text("[Main]\nSetupWizardIncomplete = false\n\n[MemoryCards]\nCard1Type = PerGameTitle\n")
            self.run_script(home)
            self.assertNotIn("PerGameFileTitle", ini.read_text())


if __name__ == "__main__":
    unittest.main()
