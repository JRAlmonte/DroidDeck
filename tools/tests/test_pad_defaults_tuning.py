from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-pad-defaults"
ELITE = "Snapdragon 8 Elite (SM8750)"
GEN1 = "Snapdragon 8 Gen 1 (SM8450)"


class PadDefaultsTuningTest(unittest.TestCase):
    def run_script(self, home, soc=None):
        env = {"PATH": "/usr/bin:/bin", "HOME": str(home)}
        if soc:
            env["DD_SOC_NAME"] = soc
        return subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60)

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

    def flatpak(self, home, app_id):
        (Path(home) / ".local/share/flatpak/app" / app_id).mkdir(parents=True)

    def test_the_resolution_follows_the_chip(self):
        for soc, scale in ((ELITE, "4"), (GEN1, "3"), (None, "2"), ("MediaTek MT6983", "2")):
            with tempfile.TemporaryDirectory() as home:
                self.run_script(home, soc)
                ini = Path(home) / ".config/duckstation/settings.ini"
                self.assertEqual(scale, self.section(ini, "GPU")["ResolutionScale"], soc)

    def test_a_resolution_the_user_chose_is_kept_and_tuning_happens_once(self):
        with tempfile.TemporaryDirectory() as home:
            ini = Path(home) / ".config/duckstation/settings.ini"
            ini.parent.mkdir(parents=True)
            ini.write_text("[GPU]\nResolutionScale = 6\n")
            self.run_script(home, ELITE)
            self.assertEqual("6", self.section(ini, "GPU")["ResolutionScale"])
        with tempfile.TemporaryDirectory() as home:
            ini = Path(home) / ".config/duckstation/settings.ini"
            self.run_script(home, ELITE)
            text = ini.read_text().replace("ResolutionScale = 4", "ResolutionScale = 1")
            ini.write_text(text)
            self.run_script(home, ELITE)
            self.assertEqual("1", self.section(ini, "GPU")["ResolutionScale"])

    def test_dolphin_gets_resolution_and_asynchronous_shaders(self):
        with tempfile.TemporaryDirectory() as home:
            (Path(home) / ".config/dolphin-emu").mkdir(parents=True)
            self.run_script(home, ELITE)
            gfx = self.section(Path(home) / ".config/dolphin-emu/GFX.ini", "Settings")
        self.assertEqual(("2", "2"), (gfx["InternalResolution"], gfx["ShaderCompilationMode"]))

    def test_ppsspp_from_the_store_opens_its_folder_in_vulkan(self):
        with tempfile.TemporaryDirectory() as home:
            self.flatpak(home, "org.ppsspp.PPSSPP")
            self.run_script(home, GEN1)
            ini = Path(home) / ".var/app/org.ppsspp.PPSSPP/config/ppsspp/PSP/SYSTEM/ppsspp.ini"
            self.assertEqual("/root/ROMs/psp", self.section(ini, "General")["CurrentDirectory"])
            graphics = self.section(ini, "Graphics")
        self.assertEqual(("3 (VULKAN)", "3"), (graphics["GraphicsBackend"], graphics["InternalResolution"]))

    def test_azahar_lists_the_3ds_folder_beside_its_own_entries(self):
        with tempfile.TemporaryDirectory() as home:
            self.flatpak(home, "org.azahar_emu.Azahar")
            self.run_script(home, ELITE)
            qt = Path(home) / ".var/app/org.azahar_emu.Azahar/config/azahar-emu/qt-config.ini"
            ui = self.section(qt, "UI")
            renderer = self.section(qt, "Renderer")
        self.assertEqual("3", ui["Paths\\gamedirs\\size"])
        self.assertEqual(["INSTALLED", "SYSTEM", "/root/ROMs/3ds"], [ui[f"Paths\\gamedirs\\{i}\\path"] for i in (1, 2, 3)])
        self.assertEqual("true", ui["Paths\\gamedirs\\3\\deep_scan"])
        self.assertEqual({"graphics_api": "2", "graphics_api\\default": "false", "resolution_factor": "3",
                          "async_shader_compilation": "true"}, {k: renderer[k] for k in
                          ("graphics_api", "graphics_api\\default", "resolution_factor", "async_shader_compilation")})

    def test_a_game_list_of_the_users_own_is_left_alone(self):
        with tempfile.TemporaryDirectory() as home:
            qt = Path(home) / ".config/azahar-emu/qt-config.ini"
            qt.parent.mkdir(parents=True)
            qt.write_text("[UI]\nPaths\\gamedirs\\1\\path=/sdcard/3ds\nPaths\\gamedirs\\size=1\n")
            self.run_script(home, ELITE)
            ui = self.section(qt, "UI")
        self.assertEqual(("1", "/sdcard/3ds"), (ui["Paths\\gamedirs\\size"], ui["Paths\\gamedirs\\1\\path"]))

    def test_eden_settings_carry_their_default_flags(self):
        with tempfile.TemporaryDirectory() as home:
            self.flatpak(home, "dev.eden_emu.eden")
            self.run_script(home, None)
            qt = Path(home) / ".var/app/dev.eden_emu.eden/config/eden/qt-config.ini"
            renderer = self.section(qt, "Renderer")
            ui = self.section(qt, "UI")
        self.assertEqual(("2", "false"), (renderer["resolution_setup"], renderer["resolution_setup\\default"]))
        self.assertEqual(("true", "false"), (renderer["use_asynchronous_shaders"], renderer["use_asynchronous_shaders\\default"]))
        self.assertEqual(["SDMC", "UserNAND", "SysNAND", "/root/ROMs/switch"], [ui[f"Paths\\gamedirs\\{i}\\path"] for i in (1, 2, 3, 4)])

    def test_a_switch_fork_that_never_ran_gets_nothing(self):
        with tempfile.TemporaryDirectory() as home:
            (Path(home) / ".config/citron").mkdir(parents=True)
            self.run_script(home, ELITE)
            self.assertFalse((Path(home) / ".config/citron/qt-config.ini").exists())

    def test_flycast_rmg_and_xemu_get_their_folders(self):
        with tempfile.TemporaryDirectory() as home:
            for app in ("org.flycast.Flycast", "com.github.Rosalie241.RMG", "app.xemu.xemu"):
                self.flatpak(home, app)
            self.run_script(home, ELITE)
            fly = self.section(Path(home) / ".var/app/org.flycast.Flycast/config/flycast/emu.cfg", "config")
            rmg = self.section(Path(home) / ".var/app/com.github.Rosalie241.RMG/config/RMG/mupen64plus.cfg", "Rosalie's Mupen GUI RomBrowser")
            xemu = (Path(home) / ".var/app/app.xemu.xemu/data/xemu/xemu/xemu.toml").read_text()
        self.assertEqual(("/root/ROMs/dreamcast", "4", "1440"), (fly["Dreamcast.ContentPath"], fly["pvr.rend"], fly["rend.Resolution"]))
        self.assertEqual('"/root/ROMs/n64"', rmg["Directory"])
        for line in ('games_dir = "/root/ROMs/xbox"', "show_welcome = false", 'renderer = "VULKAN"', "surface_scale = 2"):
            self.assertIn(line, xemu)

    def test_rpcs3_scales_up_on_a_fast_chip_and_lists_the_ps3_folder(self):
        with tempfile.TemporaryDirectory() as home:
            self.run_script(home, ELITE)
            cfg = (Path(home) / ".config/rpcs3/config.yml").read_text()
            vfs = (Path(home) / ".config/rpcs3/vfs.yml").read_text()
        self.assertIn("Video:\n  Resolution Scale: 150\n", cfg)
        self.assertIn("Shader Mode:", cfg)
        self.assertEqual("/games/: /root/ROMs/ps3/\n", vfs)


if __name__ == "__main__":
    unittest.main()
