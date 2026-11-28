from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-pad-defaults"


class PadDefaultsScreenTest(unittest.TestCase):
    def run_script(self, home, **env):
        full = {"PATH": "/usr/bin:/bin", "HOME": str(home), **env}
        return subprocess.run([sys.executable, str(SCRIPT)], env=full, capture_output=True, text=True, timeout=60)

    def section(self, path, name):
        lines = path.read_text().splitlines()
        start = lines.index(f"[{name}]") + 1
        out = {}
        for line in lines[start:]:
            if line.startswith("["):
                break
            if "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
        return out

    def test_a_ds_layout_is_written_for_the_game_and_put_back_after(self):
        with tempfile.TemporaryDirectory() as home:
            toml = Path(home) / ".config/melonDS/melonDS.toml"
            self.run_script(home)
            before = self.section(toml, "Instance0.Window0")
            self.run_script(home, DROIDDECK_DS_LAYOUT="1", DROIDDECK_DS_SIZING="4", DROIDDECK_DS_SWAP="true")
            during = self.section(toml, "Instance0.Window0")
            self.run_script(home)
            after = self.section(toml, "Instance0.Window0")
        self.assertEqual(("1", "4", "true"), (during["ScreenLayout"], during["ScreenSizing"], during["ScreenSwap"]))
        self.assertEqual(before["ScreenLayout"], after["ScreenLayout"])
        self.assertEqual(before["ScreenSizing"], after["ScreenSizing"])
        self.assertEqual("false", after["ScreenSwap"])

    def test_a_value_melonds_does_not_have_is_ignored(self):
        with tempfile.TemporaryDirectory() as home:
            toml = Path(home) / ".config/melonDS/melonDS.toml"
            self.run_script(home)
            before = self.section(toml, "Instance0.Window0")["ScreenSizing"]
            self.run_script(home, DROIDDECK_DS_SIZING="3")
            self.assertEqual(before, self.section(toml, "Instance0.Window0")["ScreenSizing"])

    def test_azahar_gets_the_layout_with_its_default_flag_and_loses_it_again(self):
        with tempfile.TemporaryDirectory() as home:
            qt = Path(home) / ".var/app/org.azahar_emu.Azahar/config/azahar-emu/qt-config.ini"
            qt.parent.mkdir(parents=True)
            qt.write_text("[Layout]\nlayout_option\\default=true\nlayout_option=0\nswap_screen\\default=true\nswap_screen=false\n\n[Renderer]\nresolution_factor=2\n")
            self.run_script(home, DROIDDECK_3DS_LAYOUT="3", DROIDDECK_3DS_SWAP="true")
            during = self.section(qt, "Layout")
            renderer = self.section(qt, "Renderer")
            self.run_script(home)
            after = self.section(qt, "Layout")
        self.assertEqual({"layout_option\\default": "false", "layout_option": "3", "swap_screen\\default": "false", "swap_screen": "true"}, during)
        self.assertEqual("2", renderer["resolution_factor"])
        self.assertEqual({"layout_option\\default": "true", "layout_option": "0", "swap_screen\\default": "true", "swap_screen": "false"}, after)

    def test_azahar_that_never_ran_is_left_alone(self):
        with tempfile.TemporaryDirectory() as home:
            self.run_script(home, DROIDDECK_3DS_LAYOUT="3")
            self.assertFalse((Path(home) / ".config/azahar-emu/qt-config.ini").exists())

    def test_duckstation_aspect_and_retroarch_integer_scaling_follow_the_game(self):
        with tempfile.TemporaryDirectory() as home:
            self.run_script(home)
            ini = Path(home) / ".config/duckstation/settings.ini"
            cfg = Path(home) / ".config/retroarch/retroarch.cfg"
            self.run_script(home, DROIDDECK_PS1_ASPECT="16:9", DROIDDECK_RA_INTEGER="true")
            aspect = self.section(ini, "Display").get("AspectRatio")
            integer = [l for l in cfg.read_text().splitlines() if l.startswith("video_scale_integer")]
            self.run_script(home)
            after = self.section(ini, "Display").get("AspectRatio")
            integer_after = [l for l in cfg.read_text().splitlines() if l.startswith("video_scale_integer")]
        self.assertEqual("16:9", aspect)
        self.assertEqual(['video_scale_integer = "true"'], integer)
        self.assertEqual("Auto (Game Native)", after)
        self.assertEqual(['video_scale_integer = "false"'], integer_after)


if __name__ == "__main__":
    unittest.main()
