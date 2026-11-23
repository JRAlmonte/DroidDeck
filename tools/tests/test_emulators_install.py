import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-emulators"


class EmulatorsInstallTest(unittest.TestCase):
    def run_install(self, failure="", cores="", command="install", present=()):
        with tempfile.TemporaryDirectory(prefix="emulator install 'quoted' ") as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            calls = root / "calls"
            launcher = root / "PPSSPPSDL"
            helper = root / "droiddeck-flatpak"
            helper.write_text(
                "import os, sys\n"
                "with open(os.environ['CALLS'], 'a') as f:\n"
                "    f.write('helper ' + ' '.join(sys.argv[1:]) + '\\n')\n"
                "sys.exit(1 if os.environ['FAILURE'] == 'helper' else 0)\n"
            )
            stubs = {
                "droiddeck-root":
                    'echo "apt $*" >> "$CALLS"\n'
                    '[ "$FAILURE" != "apt-$2" ]',
                "flatpak":
                    'echo "flatpak $*" >> "$CALLS"\n'
                    'if [ "$1" = install ]; then\n'
                    '  echo "Could not connect: No such file or directory" >&2\n'
                    '  exit 1\n'
                    'fi\n'
                    '[ "$FAILURE" != remote ]',
            }
            for name, body in stubs.items():
                path = bin_dir / name
                path.write_text("#!/bin/sh\n" + body + "\n")
                path.chmod(0o755)
            script = root / "install"
            libretro = root / "libretro"
            libretro.mkdir()
            for core in present:
                (libretro / (core + "_libretro.so")).write_text("old")
            script.write_text(SCRIPT.read_text()
                              .replace("libretro=/usr/lib/aarch64-linux-gnu/libretro", "libretro=" + shlex.quote(str(libretro)))
                              .replace("/usr/local/bin/droiddeck-flatpak install", shlex.quote(str(helper)) + " install")
                              .replace("/usr/local/bin/PPSSPPSDL", shlex.quote(str(launcher))))
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                       CALLS=str(calls), FAILURE=failure, DROIDDECK_CORES_URL=cores)
            result = subprocess.run(["bash", str(script), command], env=env,
                                    capture_output=True, text=True, timeout=30)
            return result, calls.read_text() if calls.exists() else "", launcher.read_text() if launcher.exists() else None, \
                os.access(launcher, os.X_OK)

    def test_install_without_a_system_bus_uses_the_helper_and_creates_the_launcher(self):
        result, calls, launcher, executable = self.run_install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("helper install org.ppsspp.PPSSPP\n", calls)
        self.assertNotIn("flatpak install ", calls)
        self.assertIn('exec /usr/local/bin/droiddeck-flatpak-run org.ppsspp.PPSSPP "$@"', launcher)
        self.assertTrue(executable)

    CORES = ("mupen64plus_next", "flycast", "fbneo", "stella", "a5200", "prosystem", "virtualjaguar")

    LIBRARY = b"\x7fELF\x02\x01\x01" + bytes(9) + b"\x03\x00\xb7\x00" + bytes(44)

    def published(self, directory, cores, broken=()):
        import zipfile
        for core in cores:
            with zipfile.ZipFile(Path(directory) / (core + "_libretro.so.zip"), "w") as z:
                z.writestr(core + "_libretro.so", self.LIBRARY if core not in broken else b"core")
        return Path(directory).as_uri()

    def test_the_fetched_cores_are_put_with_the_other_cores(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls, _, _ = self.run_install(cores=self.published(directory, self.CORES))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for core in self.CORES:
            self.assertRegex(calls, r"apt /usr/bin/install -m 644 \S*/%s_libretro\.so .*/libretro/\.%s_libretro\.so\.new\n"
                                    r"apt /bin/mv -f .*/libretro/\.%s_libretro\.so\.new .*/libretro/%s_libretro\.so\n" % (core, core, core, core))
        self.assertNotIn("== NOTE", result.stdout)

    def test_a_missing_core_does_not_fail_the_install_or_the_other_cores(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls, launcher, _ = self.run_install(cores=self.published(directory, ("flycast",)))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("== NOTE fbneo core not downloaded", result.stdout)
        self.assertNotIn("== NOTE flycast", result.stdout)
        self.assertIn("flycast_libretro.so", calls)
        self.assertNotIn("fbneo_libretro.so", calls)
        self.assertIsNotNone(launcher)

    def test_cores_says_when_a_core_did_not_come(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls, _, _ = self.run_install(cores=self.published(directory, ("flycast",)), command="cores")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("flycast_libretro.so", calls)
        self.assertIn("== NOTE fbneo core not downloaded", result.stdout)

    def test_a_download_that_is_not_an_arm64_library_is_not_installed(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls, _, _ = self.run_install(cores=self.published(directory, self.CORES, broken=("stella",)), command="cores")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("stella_libretro.so", calls)
        self.assertIn("== NOTE stella core not downloaded", result.stdout)
        self.assertIn("prosystem_libretro.so", calls)

    def test_an_install_leaves_the_cores_it_already_has(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls, _, _ = self.run_install(cores=self.published(directory, self.CORES), present=("flycast",))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("flycast_libretro.so", calls)
        self.assertIn("fbneo_libretro.so", calls)

    def test_no_core_address_fetches_nothing(self):
        result, calls, launcher, _ = self.run_install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("/usr/bin/install", calls)
        self.assertNotIn("core", result.stdout)

    def test_cores_fetches_only_the_missing_ones_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as directory:
            result, calls, launcher, _ = self.run_install(cores=self.published(directory, self.CORES), command="cores",
                                                          present=("flycast", "mupen64plus_next"))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("fbneo_libretro.so", calls)
        self.assertNotIn("flycast_libretro.so", calls)
        self.assertNotIn("apt-get", calls)
        self.assertNotIn("helper ", calls)
        self.assertIsNone(launcher)

    def test_helper_failure_does_not_publish_a_launcher(self):
        result, calls, launcher, _ = self.run_install("helper")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("helper install org.ppsspp.PPSSPP\n", calls)
        self.assertIn("== FAIL org.ppsspp.PPSSPP", result.stdout)
        self.assertIsNone(launcher)

    def test_apt_failure_stops_before_flatpak(self):
        for stage in ("update", "install"):
            with self.subTest(stage=stage):
                result, calls, launcher, _ = self.run_install("apt-" + stage)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("== FAIL apt-get " + stage, result.stdout)
                self.assertNotIn("flatpak ", calls)
                self.assertNotIn("helper ", calls)
                self.assertIsNone(launcher)

    def test_remote_failure_stops_before_the_helper(self):
        result, calls, launcher, _ = self.run_install("remote")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("== FAIL flathub", result.stdout)
        self.assertNotIn("helper ", calls)
        self.assertIsNone(launcher)


if __name__ == "__main__":
    unittest.main()
