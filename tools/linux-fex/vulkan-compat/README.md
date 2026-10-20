# FEX 2610 Vulkan debug callback routing

BeamNG.drive 0.40 creates a Vulkan debug messenger for device-address binding
notifications using a function returned by `vkGetInstanceProcAddr`.
FEX 2610 already replaces guest debug callbacks with native no-op callbacks for
direct API calls, but its proc-address lookup bypasses those custom wrappers.
The ARM driver then calls an x86 callback, producing SIGILL during startup.

`../patches/0001-route-vulkan-debug-callback-proc-addresses.patch` routes debug-utils
and debug-report proc-address queries through the existing custom implementations.
It does not disable Vulkan, change GPU memory budgets, or change CPU presets.
The existing FEX policy of suppressing these diagnostic callbacks is preserved.

The app stages the corrected host thunk in an overlay only when both installed
host and guest Vulkan libraries match the supported FEX 2610 SHA-256 hashes.
Other host libraries and the 32-bit thunk directory link to the original package;
the installed component is never rewritten. Unknown/custom builds use their own
thunks. No renderer-driver replacement or Vulkan layer is needed.

Build on an ARM64 Mac/Linux machine with Docker, git and Zig:

```
tools/linux-fex/build-vulkan-compat.sh
```

`WORK` chooses a persistent build folder; `IMAGE` chooses an existing builder.
The script pins FEX commit `14c92681f4d62cf84d901460e0358de09c8847a7`, checks out
its matching Vulkan headers, applies the source patch and rebuilds the host thunk.
It also builds the x86 callback regression probe. The FEX MIT license is packaged
beside the library in app assets.

After installing the debug and Android test APKs on a phone with FEX 2610 and its
Linux root filesystem installed:

```
adb shell am instrument -w -e fexVulkan 1 \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
```

The check runs the x86 probe through the app's real proot/FEX launch. The stock
direct API route must pass, the stock proc-address route must reproduce SIGILL,
and both corrected routes must pass. It verifies that the installed component
and unrelated thunk paths remain unchanged and restores the original FEX config.
