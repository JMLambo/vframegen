# Changelog

## 0.1.0-alpha.1

First pre-release.

- Interpolated frame generation, 2x to 6x, plus a low-latency (extrapolating) mode.
- Works on the Vulkan renderer and OpenGL, through Minecraft's own renderer abstraction.
- Vulkan present thread: each frame is shown at its exact time, without a frame cap.
- Auto frame cap at display refresh ÷ multiplier, with a manual refresh override.
- Iris shader packs, Sodium and Distant Horizons supported; the held item and GUI are not warped.
- View bobbing interpolated correctly.
- Render scale (50–100%) with bicubic upscaling and contrast-adaptive sharpening; GUI stays sharp.
- Controlify bindings for the toggle and the debug capture.
- Mod Menu / Cloth Config settings, including a Debug category (all off by default).
