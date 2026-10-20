# Linux containers: game profiles and the Steam container

Written 2026-10-13. Nothing here was compiled or run yet when this was written; the checks are listed at the end.

## Native Vulkan output

Linux shortcuts expose **Renderer driver** when **Vulkan output** is **Native**. This searchable picker lists bundled,
installed and downloadable Android AdrenoTools drivers from Components. Installed drivers select immediately; downloads
select the installed package ID only after installation succeeds. Failure preserves the previous selection. The editor
waits for an active driver download before saving and closing; changing settings categories does not cancel it.

The renderer driver is stored in the shortcut's existing `rendererDriverId` field. It is separate from **Turnip driver**,
which selects a glibc driver for the Linux game. Native output validates the Android renderer's DMA-BUF import support,
reports custom-driver loading or frame-import failures, and does not fall back to the system driver or CPU output when
an explicitly chosen Android driver fails. A driver must support the device GPU and importing the Linux game's frames;
a successful installation alone does not establish compatibility.

## Automatic Android renderer selection

`Renderer driver: Auto (match GPU)` resolves through `registry/renderer-drivers.json`, with a bundled copy in the APK.
Rules list exact Adreno models/variants, Android API bounds, priority, DMA-BUF capability, an HTTPS package URL and its SHA-256.
The catalog records tested GPUs separately from candidates. Adreno 830 defaults to the AdrenoTools registry's standard
[Turnip v26.2.0-R8 package](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v26.2.0-R8), with its archive SHA-256 pinned.
This mapping still needs validation on Adreno 830: upstream release notes list A6xx/A7xx only. Adreno 840 retains DD-Turnip v0.1.1,
which has device validation. Unlisted variants and Android versions do not inherit a family-wide match.

Registry refresh downloads updates in the background. A launch reads the cached/bundled rules; if the selected package is missing,
a worker downloads it and checks the archive hash before importing its Android half. The loading screen reports that step. No game,
shortcut or shared-container setting is rewritten with the resolved driver ID. Explicit System/custom choices are preserved.

New Linux environments default to Auto. New imports of older native-output profiles also get Auto when the profile omitted a renderer;
existing shortcuts retain their choices. Existing affected shortcuts can select Auto in their Renderer driver picker.
Linux game-side Turnip remains separate. Native output still checks the actual driver's external-memory FD/DMA-BUF support;
a catalog match alone does not prove rendering works. Automatic custom-driver load failures never silently use System.
Initialization failures emit an opt-in error event with requested/resolved renderer IDs, including failures before the game starts.

Device check: `adb shell am instrument -w -e rendererRegistry 1 com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation`.
Publish this JSON under the components repository's `registry/` directory to update mappings without a new app build.

## Game profiles

The registry (`games.json` in the components repository, see its README, "Game profiles") says how to start a game and what works
for each version of it. `games.json` is a short index; a game's own file (`games/<id>.json`) is read only when something needs it.
The app keeps what it downloaded (`files/registry-cache/<branch>/`, 6 h for the index, 24 h for a game), falls back to the
last copy, and to the copy bundled in `assets/registry/` when there is nothing else (`contents/RegistryCache.java`). The launch
path never waits for the network.

A shortcut follows a profile through four extras (`gameprofile/GameProfiles.java`):

| Extra | Meaning |
| --- | --- |
| `gameProfile` | the game's id; `none` when the user took the profile off; empty: the registry's profile for a Steam game's app id |
| `gameProfileVersion` | a version the user chose; empty: it is recognised |
| `gameRoot` | the game's folder as the guest sees it, for a game Steam did not install |
| `noSteam` | empty: the profile's choice, `1` on, `0` off |

**Which version.** The profile's versions are tried in file order; a version is recognised when one of its detectors fits: the
Steam build id (only for Steam installs), the size or hash of files, or a version number found in the first or last bytes of a small
file (BeamNG: the end of `integrity.json`). Detectors only look at files, so a game that did not come from Steam is recognised the
same way. Nothing recognised: the profile's `default` version.

**What the profile gives a shortcut** (the shortcut's own setting always wins; an empty one means "profile default", then
"container default"): the executable, arguments, variables, resolution (`native` or `WxH`) and a few extras (scaling, upscaler, touch,
frame generation, see `GameProfile.ALLOWED_EXTRAS`).

**Launch without Steam.** A Steam game whose profile says so (BeamNG: `-nosteam`) is started as its own executable, not through the
client: the program of `steamapps/common/<installdir>/<executable>`, its folder as the working directory, through Box64 as any
program. Turning "Launch without Steam" off in the shortcut starts it through the client as before. A game that ships mimalloc
(BeamNG) gets the stub that keeps Box64 from mixing allocators either way (`SKY_MIMALLOC_STUB`, set by `LinuxSession.guestEnv` and used
by `assets/linux/skynet-launcher.sh` for a game the client starts).

**Resolution.** Every Linux shortcut, including Steam and Steam games, uses the shortcut's size, then the profile's,
then the shared container's (`GameProfiles.screenSize`). Games may also select their own mode through RandR.

Programs added by hand take a profile too: the "Add program" dialogs have a "Game profile" choice (game and version), and a program
picked from storage that is a registry game's executable is offered it.

## The Steam container

Steam and all Linux programs use one protected container named Linux (`steam/SteamContainer.java`), marked
`linuxManaged=steam` for compatibility. See [shared Linux container](linux-shared-container.md) for migration and shortcut settings. The shared container is created from the
`steam` block of the registry's `linux.json` (runtime, Box64 build and preset, Turnip driver, OpenGL driver, Vulkan present), when the
Linux runtime, Box64 and the Turnip driver are all installed. Settings, Steam: the components to download (one button, one bar per
package), then the container's settings; they are greyed out until the container exists. The Steam shortcut in the library exists only while
the container's runtime does; the games installed in Steam keep getting shortcuts of their own (`LinuxSteamGames.sync`).
The shared container cannot be removed or duplicated. The Reset Steam action has been removed, and the legacy reset API preserves its data.

The Steam shortcut defaults to native resolution and Big Picture mode; its resolution can be changed in shortcut settings. The old per-container Steam options (`linuxSteam`, `linuxSteamBigPicture`) are gone from
the container settings. Existing Linux containers are consolidated into the shared one while retaining their data.

## Settings screen

The app's Settings use the layout of the container editors (`ui/settings/GroupedSettings.kt`): the groups are listed on the left when the
width allows (720 dp or more), otherwise they are sections that open and close; what is chosen or open survives a fold or unfold.

## To check on the device

1. A Steam game with a profile (BeamNG): the library shortcut gets `gameProfile`; "Launch without Steam" starts `BeamNG.drive.x64 -nosteam`
   with no client; off, it goes through Steam and does not die with `free(): invalid size`.
2. Adding BeamNG from storage or by path with the "Game profile" choice: the version (0.34 / 0.39) is recognised from `integrity.json`.
3. The Steam screen from scratch (fresh app data): download, the container appearing by itself, the shortcut in the library, Steam native and
   in Big Picture.
4. Settings on the unfolded display (list on the left) and the cover display (sections).

## BeamNG.drive: the Linux profile (Box64, Performance)

`containers/beamng-linux-box64-performance.wcfg` (index entry `"platform": "linux"`) is a Linux container on Box64 0.4.4 with the
PERFORMANCE preset, the `bannerlator-r9` runtime, Turnip 26.3 and Zink. It is listed in every version of `games/beamng-drive.json`.
A version's single `preferredContainer` names a Windows config, so for the Linux platform `ConfigChooser` takes the first listed Linux
config that suits (a config made for any processor family counts when the preferred one belongs to the other platform): the Add-game
screen shows it as "Recommended" for `BinLinux/BeamNG.drive.x64`. Box64 reads the program, so it can stay on shared storage (no copy).
The registry lives in the components repository: these files must be published there, or the app's cached copy replaces them after its TTL.

## Tomb Raider (2013): Linux only (FEX Performance + TSO)

`games/tomb-raider-2013.json` replaces the former Windows profile, keeping its game id and Steam app id (`203160`).
Only `linux.executables` are listed (`TombRaider.sh`, `bin/TombRaider`); there is no Windows platform or Wine container.
The default version recognises Steam build `9573671`, checked on SM-F976B / Adreno 840 / Android 17. Other builds
inherit these settings as a fallback; their compatibility and long-session stability have not been checked.

The profile starts through Steam, adds `-nolauncher` (present in the installed game binary) to bypass Feral's options
page, and sets `FEX_TSOENABLED=1`. There is no `noSteam` mode: the shipped `TombRaider.sh` starts Steam when
`SteamAppId` is absent. The TSO override is per game; the shortcut's explicit environment editor still wins over it.
If Feral's options page still opens, set `GameOptionsDialogShouldShow=0` in the game's preferences, as in the device fix.

The recommended `containers/tomb-raider-2013-linux-fex-performance.wcfg` pins `bannerlator-r9`, FEX 2610 Performance,
Ubuntu 24.04 rootfs, 32-bit OpenGL thunks, NVIDIA GL vendor strings, Zink / Turnip a8xx and native Vulkan output.
It also sets `FEX_TSOENABLED=1` so importing the container alone retains the fix. FEX requires the game's executable
and libraries on internal storage; shared storage is mounted `noexec`. The old Tomb Raider Windows `.wcfg` and its
index entry are removed. `revision: 1` makes the bundled game profile supersede older downloaded copies without a revision.
The new registry files must also be published in the components repository for online config selection.
