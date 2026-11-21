# Changelog

## Unreleased

- Adaptive frame generation: a toggle and a target frame rate; frames per real frame (1 to 6,
  fractional on average) follow the real frame rate to reach the target.
- New upscaler option for render scale: Edge-adaptive (FSR 1 style EASU + RCAS), next to the
  existing Bicubic.

## 0.1.0-alpha.2

- Much higher real frame rate at 3x and above with the present thread: the swapchain now has
  enough images for a real frame and all its generated frames (4x: about 30 → 44 real fps).
- Changing the multiplier in the settings recreates the swapchain.
- Fixed a frozen picture after the swapchain had to be recreated (for example after a failed
  frame generation), while the game kept running.
- Reprojection: no false matches from behind the camera after large turns, steadier depth edges,
  no copies of the held item at the screen edge, and a gap-free held-item mask.
- Auto frame cap rounds down and never exceeds refresh ÷ multiplier.
- Settings: an unreadable file is no longer overwritten, saves are atomic, invalid numbers fall
  back to defaults, and the refresh-rate slider goes to 500 Hz.
- No longer loads on Minecraft 26.4 snapshots.
- Reflex AntiLag works with the present thread and is recommended for lower input lag.

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
