# Changelog

## Unreleased

## 0.1.0-alpha.9

- Blocks seen through water no longer shake in generated frames (with or without shaders): they were moved by the
  water surface's depth, too far, and snapped back at each real frame. Generated frames now use the
  depth from before water and other translucents are drawn (with Iris, the pack's depthtex1 copy).
- Sodium integration: a VFrameGen page in Sodium's video settings with the main options (frame generation, upscaling, anti-aliasing, motion blur), and a button to the full settings screen.
- Anti-aliasing (Quality, off by default): FXAA or SMAA (the reference SMAA by Jorge Jimenez et al., MIT) on the world, before the GUI, so the HUD stays sharp
  and generated frames are built from the smoothed image. Works with or without frame generation.
- With render scale and shaders off, generated frames used the low-resolution image from before the
  upscaler, so they were softer than real frames: fine textures such as water flickered between
  sharp and soft (very visible with the edge-adaptive upscaler). They now use the upscaled image,
  and the low-resolution one only where the held item was.

## 0.1.0-alpha.8

- No more oval of moving world in a still frame with shaders off: vanilla's vignette (drawn with the
  GUI) was taken for GUI and kept unwarped. With frame generation on it is now drawn on every shown
  frame, under the GUI.

## 0.1.0-alpha.7

- The F3 crosshair (axis gizmo) no longer trails while turning: with frame generation on it is drawn
  on every shown frame, real and generated, for that frame's camera rotation.
- Motion blur (Quality, off by default): camera motion blur on every frame shown, real and
  generated, along that frame's own motion, so its length is right at any multiplier. GUI and the
  held item stay sharp. A toast warns when the shader pack's own motion blur is on (it ghosts).
- With render scale below 1.0, the log no longer fills with "Couldn't preload shader vframegen:…"
  errors during startup: the upscaler now waits until the game has loaded mod shaders.
- Tested with Sodium 0.9.3 and with a shader pack's depth of field (Complementary): no issues.

## 0.1.0-alpha.6

- VSync is turned off while frame generation is on: it made generated frames stutter heavily
  (seen on a tester's Windows PC). Turning it back on in any settings screen (vanilla, Sodium and
  others) shows a prompt after leaving that screen, to keep it on or turn it off.
- Tested on Windows: GTX 1060 3 GB, i7-7700, 8 GB RAM (vanilla renderer, Vulkan).

## 0.1.0-alpha.5

- The swapchain image reserve grows for a generated frame's acquire only past 3 ms (was 1 ms), so
  ordinary 1–2 ms waits no longer make it grow and shrink every few seconds. A real frame's wait
  still counts from 1 ms, while generated frames are queued.

## 0.1.0-alpha.4

- Generated frames no longer hold real frames back: when the estimated number of free swapchain
  images runs out, the rest of a pair's generated frames are skipped instead of waited for. The
  estimate counts queued presents plus a reserve for images the display may still hold, which it
  learns from acquire waits over 1 ms. While it learns (and once after the reserve is given back),
  a frame can still wait briefly. With a large modpack at a high
  adaptive multiplier this doubled the real frame rate (10–16 → 24–35 fps). The swapchain gets
  two pairs' worth of images where the driver allows it.
- Shader packs: blocks right next to the camera are no longer taken for the held item (only
  things nearer than 0.2 blocks are), so they warp instead of jumping between real frames.
- F8 debug captures also save images when the game renders in half float (RGBA16F).

## 0.1.0-alpha.3

- Adaptive frame generation: a toggle and a target frame rate (up to 250 fps). Frames per real
  frame (1 to 6, fractional on average, like 3.3x) follow the real frame rate to reach the target,
  and real frames are capped at it. F3 shows the current multiplier.
- New upscaler option for render scale: Edge-adaptive (FSR 1 style EASU + RCAS), next to the
  existing Bicubic.
- Settings: the multiplier is greyed out while adaptive is on, and the target while it is off.
  Display refresh rate starts at the monitor's detected rate, rounded up (179.96 Hz reads 180),
  and the auto cap uses that rounded rate.

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
