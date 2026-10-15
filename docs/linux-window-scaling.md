# Scaling the game's window (Linux containers)

**What it is for.** A container at native size (the device's own resolution) with a game that opens a smaller window (a game
set to 1280x720, a launcher): the screen was shown 1:1 and the game stayed a small rectangle. "Fit to screen" and the upscalers
(SGSR, FSR 1, Lanczos 2) scaled the whole container screen, which only helps when the container itself is smaller than the device.

**What was added.** Scaling option "Scale the game window to the screen" (shortcut extra `fullscreenStretched=2`; container extra
`scaleGameWindow=1` in the container editor). The Vulkan renderer then maps the game's window, not the X screen, onto the view
(`VulkanXServerView.updateTransform`), keeping its proportions; the rest is cut off by the scissor. The upscaler chosen for the
shortcut or container (`graphicsFilterMode`) works on the window's own resolution, because the filters take the size of the
window being drawn as their source.

**Which window.** `VulkanXServerView.pickGameWindow`: the biggest mapped window directly under the root that is at least 100x100,
is not override-redirect (menus, tooltips), is not a window of the Steam client (named "Steam", "Steam - ...", "Steam Big Picture...") and does not cover the X screen already (not the FPS window). A full-screen window
beneath the game (the Steam client in the Steam container) is passed over. The list of windows is copied in one step: it is changed
by the X server's thread, and walking it from the render thread's pool crashed the app (`ConcurrentModificationException`). Picked again when a window is mapped, unmapped, destroyed or changes its geometry; when
there is none, or the game is full size, the screen is shown as before.

**Touch.** `TouchpadView.updateXform` takes the same rectangle (`getGameZoomRect`) and maps a touch into the window's pixels;
`VulkanXServerView` tells it when the window changes (`setOnGameZoomChanged`).

**Checked on the device (2026-10-15).** `glxgears` (a 300x300 window) and Tomb Raider (2013, 1280x720 on FEX, started by the Steam
client) in the native-size Steam container with FSR 1 fill the view's width with their proportions kept; a touch on the game's OK
button dismissed its message. Not checked: a game that changes its window size while running, the Wine path (the option is
Linux-only; the OpenGL renderer ignores it).
