#!/usr/bin/env python3
"""Run without an Android device: JAVA_HOME=/path/to/jdk python3 tools/linux-launch-test/run.py."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent


class LinuxLaunchTest(unittest.TestCase):
    def test_program_paths(self):
        java_bin = Path(os.environ["JAVA_HOME"]) / "bin" if "JAVA_HOME" in os.environ else Path("/usr/bin")
        with tempfile.TemporaryDirectory() as work:
            subprocess.run([
                str(java_bin / "javac"), "-d", work,
                str(ROOT / "app/src/main/java/com/winlator/cmod/linux/LinuxProgramPaths.java"),
                str(TOOLS / "LinuxProgramPathsTest.java"),
            ], check=True)
            subprocess.run([str(java_bin / "java"), "-cp", work, "LinuxProgramPathsTest", work], check=True)

    def test_options(self):
        java_bin = Path(os.environ["JAVA_HOME"]) / "bin" if "JAVA_HOME" in os.environ else Path("/usr/bin")
        with tempfile.TemporaryDirectory() as work:
            subprocess.run([
                str(java_bin / "javac"), "-d", work,
                str(ROOT / "app/src/main/java/com/winlator/cmod/linux/LinuxLaunchOptions.java"),
                str(TOOLS / "LinuxLaunchOptionsTest.java"),
            ], check=True)
            subprocess.run([str(java_bin / "java"), "-cp", work, "LinuxLaunchOptionsTest"], check=True)

    def run_steam_game(self, is_32bit):
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            home = work / "home"
            config = home / ".skynet/steam-emulator"
            config.mkdir(parents=True)
            logs = work / "logs"
            # Redirect only the launcher's shared-storage log into the fixture.
            source = (ROOT / "app/src/main/assets/linux/skynet-launcher.sh").read_text()
            launcher = work / "launcher.sh"
            launcher.write_text(source.replace("/storage/emulated/0/Winlator/logs", str(logs)))
            game_dir = work / "game with spaces"
            game_dir.mkdir()
            game = game_dir / "game.sh"
            keys = ["FEX_TSOENABLED", "FEX_THUNKCONFIG", "MESA_LOADER_DRIVER_OVERRIDE", "GALLIUM_DRIVER",
                    "LIBGL_ALWAYS_SOFTWARE", "LD_PRELOAD", "BOX64_LD_PRELOAD", "EMPTY", "LITERAL", "WRAPPER_USED", "GL32_APPLIED"]
            game.write_text("#!/bin/bash\n" + "\n".join(f'echo "TEST_{key}=${{{key}}}"' for key in keys)
                            + '\nprintf "TEST_ARG=%s\\n" "$@"\nexit 23\n')
            game.chmod(0o755)
            (game_dir / "libmimalloc.so.3").touch()
            stub = work / "mimalloc-stub.so"
            stub.touch()
            gl32 = work / "gl32.sh"
            gl32.write_text("sky_gl32_mesa_env() { export GL32_APPLIED=1 MESA_LOADER_DRIVER_OVERRIDE=gl32 GALLIUM_DRIVER=gl32; }\n")
            wrapper = work / "wrapper.sh"
            wrapper.write_text('#!/bin/bash\nexport WRAPPER_USED=1\nexec "$@"\n')
            wrapper.chmod(0o755)
            (config / "42.env").write_text("export FEX_TSOENABLED=0\n")
            explicit = {
                "FEX_TSOENABLED": "1", "FEX_THUNKCONFIG": "shortcut", "MESA_LOADER_DRIVER_OVERRIDE": "shortcut",
                "GALLIUM_DRIVER": "shortcut", "LIBGL_ALWAYS_SOFTWARE": "1", "LD_PRELOAD": "",
                "BOX64_LD_PRELOAD": "shortcut", "EMPTY": "", "LITERAL": "$HOME\\path'quote",
            }
            (config / "42.launch").write_text(
                "".join(f"export {key}={shlex.quote(value)}\n" for key, value in explicit.items())
                + f"sky_wrap=({shlex.quote(str(wrapper))})\nsky_args=(-windowed 'two words')\n")
            env = dict(os.environ, HOME=str(home), SteamAppId="42", FEX_TSOENABLED="container",
                       SKY_GAME_GL="zink", SKY_FEX_GL32="1", SKY_FEX_THUNKCONFIG32="runtime",
                       SKY_FEX_GL32_SCRIPT=str(gl32), SKY_MIMALLOC_STUB=str(stub), EMPTY="container",
                       FEX_ROOTFS=str(work / "rootfs"))
            env.pop("GL32_APPLIED", None)
            command = [str(game), "steam argument"]
            if is_32bit:
                binaries = game_dir / "bin"
                binaries.mkdir()
                elf = binaries / "game32"
                elf.write_bytes(b"\x7fELF\x01")
                elf.chmod(0o755)
                runtime = work / "SteamLinuxRuntime"
                # The outer entry point is dropped first; run-in-scout identifies the old 32-bit runtime.
                command = [str(runtime / "_v2-entry-point"), "--", str(runtime / "run-in-scout"), "--"] + command
            result = subprocess.run(["bash", str(launcher), "run", "--"] + command, env=env, capture_output=True, text=True)
            log = (logs / "steam-game-launch.log").read_text()
            self.assertEqual(23, result.returncode, (log, result.stderr))
            for key, value in explicit.items():
                self.assertIn(f"TEST_{key}={value}", log.splitlines())
            self.assertIn("TEST_WRAPPER_USED=1", log.splitlines())
            self.assertIn("TEST_GL32_APPLIED=" + ("1" if is_32bit else ""), log.splitlines())
            self.assertEqual(["TEST_ARG=steam argument", "TEST_ARG=-windowed", "TEST_ARG=two words"],
                             [line for line in log.splitlines() if line.startswith("TEST_ARG=")])

    def test_steam_64bit_overrides_runtime(self):
        self.run_steam_game(False)

    def test_steam_32bit_overrides_graphics_thunks_and_preloads(self):
        self.run_steam_game(True)


if __name__ == "__main__":
    unittest.main()
