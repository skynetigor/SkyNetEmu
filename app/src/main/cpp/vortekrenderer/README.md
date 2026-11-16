# Vortek renderer (server)

The app-side half of [Vortek](https://github.com/brunodev85/vortek) by brunodev85, licensed LGPL-2.1 (`LICENSE`). The guest-side
client library is built separately and ships as `assets/graphics_driver/vortek-client.tzst`.

Taken from `cpp/vortekrenderer` and `cpp/winlator/include` of https://github.com/brunodev85/winlator-app at commit
`ecd5ab2980de79eb955621cef26a3b4672013875` (2026-11-14). Changes made for SkyNetEmu:

- JNI symbols renamed to `com.winlator.cmod.xenvironment.components.VortekRendererComponent`.
- The shader and texture caches live under the app's cache directory (passed to `initVulkanWrapper`) instead of a hard-coded
  `/data/data/com.winlator/cache`.
- `src/shm_compat.c` provides `ashmemCreateRegion` (the one in `sysvshared_memory.c` is private to the winlator library).
- Only BGRA surface formats are offered: SkyNetEmu's X windows are BGRA hardware buffers.
- `winlator-src/` (`arrays.c`, `ring_buffer.c`, unchanged) and `src/ahb_compat.c` (`AHardwareBuffer_getFd`) are the parts of the winlator library of Winlator that the renderer calls.
- The shared helper headers Vortek includes are in `winlator-include/` (unchanged copies) so they do not clash with the winlator library.
- The target no longer links the `winlator` library.
