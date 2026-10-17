# Shared Linux container

Linux games and Steam use one protected container named **Linux**. Windows containers keep their existing creation,
cloning and deletion behavior. The Linux creation and import APIs reuse the shared container, and deletion, duplication
and the legacy Steam reset API cannot remove or duplicate it.

Every Linux shortcut has its own runtime, Box64/FEX build and preset, rootfs/thunks, glibc/bionic choice, proot switch,
CPU selection, environment and launch options, resolution, scaling/upscaler, Turnip/OpenGL/output settings, HUD/FPS,
input options and frame-generation multiplier, flow scale, performance mode and present mode. These are stored in the
shortcut, including the Steam shortcut. Explicit environment variables override launch-option assignments and profile
variables. Editing one shortcut or its sidebar settings leaves other shortcuts and shared defaults unchanged.

The Linux settings screen configures defaults for new shortcuts and the shared desktop. There is no Reset Steam action.
Linux `.wcfg` imports install missing components and return settings for the selected shortcut; they do not replace the
shared container's defaults. The Add Game flow applies the selected config to the new shortcut. An import from Settings
lets the user choose an existing shortcut. Cloning a Linux shortcut makes another shortcut in the same container.

## Existing installations

`SharedLinuxContainers` prefers the existing managed Steam container, otherwise the lowest Linux container ID. Before
consolidating homes it snapshots each shortcut's effective settings. It merges missing files using links and keeps
conflicting files in the original home. Old homes are retained and reachable from the shared home through
`.skynet/legacy-homes/<old-id>`; copied shortcut paths under `/root` are translated to that location. Colliding shortcut
names gain an ID suffix, so both versions survive.

The old `.container` becomes `.container.before-shared-linux`, and `.linux-shared-id` redirects pinned Android shortcuts
using an old ID to the shared container. Original shortcut files remain usable and have `.before-shared-linux` backups.
The original data directories are backing storage for the migrated files and remain part of the app's data. A failed
migration leaves its source registered rather than hiding inaccessible data. Repeating migration is safe.

## Verification

- Host filesystem test: `JAVA_HOME=/path/to/jdk python3 tools/shared-linux-test/run.py`.
  Checks saves/program access, conflicting settings, symbolic links and repeat migration.
- Device integration: build `:app:assembleDebug :app:assembleDebugAndroidTest`, install both APKs with `adb install -r`,
  then run `adb shell am instrument -w com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation`.
  Tests singleton creation/import, protected APIs, independent shortcut settings, Steam resolution, CPU/proot/runtime,
  environment precedence, frame-generation overrides and Windows cloning/deletion with isolated test data.
- The phone migration preserved all 17 existing shortcut entries and their original fields, with every entry pointing to
  container 1. Steam login data and Tomb Raider preferences matched their pre-migration SHA-256 hashes.
- UI check on the phone: one Linux container with Edit/Properties only, no Reset Steam, runtime/proot and CPU/FPS/HUD
  controls visible on a migrated shortcut, and Steam's Configure action opens its full shortcut editor.
- BeamNG 0.34 startup: the migrated `BeamNG.drive (2)` shortcut reached the rendered main menu in about 22 s (60 FPS
  in the later screenshot), with Box64 Performance and all eight CPU cores. Its live `SKY_PATHMAP` maps `/root` to
  `imagefs/home/xuser-1/linux-home`; it quit through its own menu. This verifies startup after migration, not map loading
  or a controlled performance benchmark.
- Steam startup: its own shortcut opened the signed-in Big Picture home, with the live proot bind
  `imagefs/home/xuser-1/linux-home:/root`, matching BeamNG's home mapping. Steam shortcut settings opened normally.
- Final installed build: all device integration assertions passed, including generic creation API protection,
  editable Steam settings, frame-generation overrides, old pinned ID redirection and Windows clone/delete operations.
  Temporary shortcuts and Windows fixtures were removed by the test runner.
