# Pinned Linux Turnip builds

Builds selectable glibc/AArch64 Vulkan ICDs for SkyNetEmu's Linux runtime. These are separate from the Android/bionic
drivers used by the renderer. All recipes are experimental until tested on the relevant phone and game.

| Recipe | Source | Intended GPU family | Renderer counterpart |
|---|---|---|---|
| `gen8-v30` | `whitebelyash/mesa-tu8`, `d185f62a24305a2ea0d2fa7f3d156e0595025d54`, Mesa 26.2.0-devel | A8xx | StevenMXZ `gen_8_v30`, checksum pinned in `recipes.json` |
| `mesa-26.2.2` | Official Mesa 26.2.2 release archive, checksum pinned | A6xx/A7xx | Select an appropriate Android driver independently |

GPU-family labels describe the recipe's intended scope; they are not device validation or a guarantee of support for every
GPU within a family. Different Mesa versions and fork patches can regress individual games; keep working drivers available.

## Build

On a machine running Docker (native ARM64 preferred):

```sh
docker build -t skynet-turnip-builder:v1 tools/linux-turnip
mkdir -p build/linux-turnip
docker run --rm \
  --mount type=bind,source="$PWD/tools/linux-turnip",target=/recipe,readonly \
  --mount type=bind,source="$PWD/build/linux-turnip",target=/work \
  skynet-turnip-builder:v1 python3 /recipe/build.py gen8-v30
```

Use `mesa-26.2.2` for the other recipe; `--jobs 4` controls compilation concurrency. The manual `Linux Turnip` GitHub workflow
can also build either or both recipes and retain artifacts. It does not publish releases or change the driver catalog.

Outputs in `build/linux-turnip/out/` include the Linux ZIP, build report and, for V30, the original checksum-verified Android
ZIP. Import the Linux ZIP through the Linux driver installer and choose it in the Linux shortcut's driver setting. Import/select
the Android ZIP through AdrenoTools for the renderer. Neither ZIP can replace the other: they use different C libraries.

Published catalog entries are downloadable from **Linux shortcut/container settings → Graphics → Turnip driver** in builds with
the Linux catalog picker. Older builds can import the ZIP manually; catalog publication alone does not add the picker to an older APK.

Run `python3 tools/linux-turnip/verify.py build/linux-turnip/out` to verify the finished ZIPs against their reports, source identity,
checksums, ELF architecture and the app's Linux importer format.

The build verifies the source revision/archive checksum, applies patches with zero fuzz and fails on rejected hunks. It verifies
the ELF architecture, glibc linkage, ICD exports and direct dependency presence in the build sysroot. Native ARM64 builders also
load the driver with the sysroot's dynamic linker and negotiate its ICD interface. This smoke test does not exercise a GPU,
DMA-BUF sharing or actual presentation. Those require a phone with the target KGSL device and compatible runtime dependencies.

Reports record minimum required glibc, dependencies and sysroot package versions. The cached Arch Linux ARM sysroot is assembled
from live package databases on its first build; retaining that cache preserves the selected packages. A clean future build can
select newer runtime libraries, so the source is pinned but the entire toolchain/sysroot is not bit-for-bit reproducible. Compare
the report against the phone's installed runtime before distribution. No runtime libraries are bundled in a driver ZIP.

## V30 provenance and intentional differences

The Android archive comes from:

https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/gen_8_v30

Its embedded version is `Mesa 26.2.0-devel (git-d185f62a24)`. Mesa encodes that development version numerically as 26.1.99.
The release's script clones `whitebelyash/mesa-tu8` and checks out `gen8`; this recipe pins the binary's actual full commit rather
than today's branch head. Its empty `TUGEN8_DRV_VERSION` definition and A7xx early-preamble edit are preserved. The recipe's
device-name substitution matches no text in this source revision. Its Vulkan beta extension build option is preserved as well.
Android native-handle edits are excluded from a glibc build. Driconf is compiled in, as on Android; Linux XML configuration is disabled.

`gen8-v30-linux.patch` rebases the Linux KGSL compatibility fixes onto that exact source: device identity for dma-buf WSI and
disabling calibrated timestamps/present timing where KGSL has no GPU-counter implementation. Thus the Linux driver shares the
V30 rendering core, with documented platform changes; it is not an identical binary or guaranteed to have identical performance.
No newer Gen8 feature patches, unrelated mesh-shader changes or current Mesa-main changes are added.

The baseline recipe carries the same two compatibility fixes against Mesa 26.2.2. Linux helper/patch provenance:

- `The412Banner/Banners-Turnip`, commit `cb840f547d4866d9a1a2cb04258da0e16a19e84d`, `build_turnip_linux.sh` and `patches/linux/`.
- These fixes originate in Bannerlator's `tools/linuxfs/turnip`. The sysroot/scanner helper is adapted from that builder and carries
  its GPL-3.0 license in `LICENSE.builder`. Mesa remains under its own source licenses; the driver ZIP includes its license document.

## Adding another Turnip

Add a named entry to `recipes.json` with a full source commit or archive checksum, intended GPU family, exact Mesa version and an
explicit patch series. Preserve fork-specific source and shared build edits; do not turn a release label such as "V30" into a Mesa
version guess. When pairing an Android build, pin its URL and checksum and verify its embedded source commit. Rebase platform
patches explicitly when context changes, compile/link-test the result, then test it on hardware. Add to `linux.json` only once a real
release URL and the output ZIP's checksum/size are available; do not replace existing choices or mark a new build stable automatically.
