<p align="center">
  <img src="branding/banner.png" alt="SkyNetEmu" width="100%">
</p>

<p align="center">
  Run Windows (x64 and ARM64) and Linux PC games on your Android phone.
</p>

<p align="center">
  <a href="https://t.me/SkyNetEmu">Telegram @SkyNetEmu</a> ·
  <a href="https://www.youtube.com/@SkyNetEmu">YouTube</a> ·
  <a href="https://github.com/skynetigor/SkyNetEmu/releases">Releases</a>
</p>

---

## What it is

SkyNetEmu runs PC games natively on Android, with no streaming:

- **Windows x64 games** through Wine/Proton with Box64 or FEX.
- **Windows ARM64 games** through the ARM64 Wine path.
- **Linux games** in a sandboxed Linux environment (Box64 or FEX for x86 games), including Steam and its games as shortcuts.

## Features

- Wine/Proton, DXVK and VKD3D, with Turnip (Adreno) Vulkan drivers picked per GPU.
- Device-matched profiles: only game configs that fit your SoC and GPU are offered.
- Per-game shortcuts with their own settings, and per-container defaults.
- Frame generation through [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk): choose Off/2x/3x/4x in the game menu, or remove the layer entirely. You supply your own `Lossless.dll`; it is not bundled.
- Performance HUD with FPS, CPU, GPU and memory.
- Library with custom backgrounds, three themes, and copy or move of games to internal storage.
- Opt-in telemetry and crash traces to help fix device-specific problems.
- Components (Wine, drivers, Box64, FEX) are downloaded from a public registry, so they can be updated without a new APK.

## Tested devices

- Samsung Galaxy Z Fold 8 Ultra (Snapdragon 8 Elite Gen 5)
- POCO F6 (Snapdragon 8 Gen 3)
- POCO F8 Pro (Snapdragon 8 Elite)
- OnePlus 11 (Snapdragon 8 Gen 2)

Other Snapdragon devices with a recent Adreno GPU will probably work. Game compatibility varies; ask in the community channel.

## Install

1. Download the APK from [Releases](https://github.com/skynetigor/SkyNetEmu/releases).
2. Install it and let the first-run setup unpack the environment.
3. Add a game (Windows `.exe`, Linux program or script, or a Steam game) and launch it.

The package is `com.skynetemu.dev`. It is a separate app from earlier prerelease builds, so their games, shortcuts and settings are not migrated.

## Community

- Telegram: [@SkyNetEmu](https://t.me/SkyNetEmu)
- YouTube: [youtube.com/@SkyNetEmu](https://www.youtube.com/@SkyNetEmu)
- Bugs: [GitHub issues](https://github.com/skynetigor/SkyNetEmu/issues)

## Building from source

Requirements: JDK 17, Android SDK 35 with NDK 29.0.14206865.

```sh
git clone --recursive https://github.com/skynetigor/SkyNetEmu.git
cd SkyNetEmu
./gradlew :app:assembleRelease
```

## Credits

Built on the work of [Winlator](https://github.com/brunodev85/winlator) (brunodev85), [Winlator Bionic](https://github.com/Pipetto-crypto/winlator) (Pipetto-crypto) and [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) (StevenMXZ), with ideas from [GameNative](https://github.com/utkarshdalal/GameNative). Thanks to the Wine, Box64, FEX, Mesa Turnip, DXVK, VKD3D-Proton and lsfg-vk projects.

## License

GPL-3.0-only, see [LICENSE](LICENSE). Third-party components and upstream attribution are listed in [NOTICE.md](NOTICE.md).
