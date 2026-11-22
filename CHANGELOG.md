# Changelog

## Unreleased

## 1.0.0-beta.5

- Coming up: better compatibility with other mods will be the main focus of the next few betas,
  along with smoothing out the experience on more hardware setups.
- Generated frames are cheaper to make: the second real frame is only searched where it is used,
  and each search stops once it has found its pixel. On a Radeon 660M at 1080p a generated frame
  went from about 9 ms to about 4.3 ms of GPU time, with the same picture.
- Below 100% render scale, generated frames are drawn at the render scale and upscaled like the real
  frames (same upscaler, sharpening and anti-aliasing), with the GUI and held item kept at full
  resolution. With the Temporal upscaler, which generated frames can't use, the new
  "Generated-frame upscaler" setting picks Full resolution, Bicubic or Edge-adaptive.
- README: on integrated graphics, use only upscaling and anti-aliasing (frame generation costs
  almost as much as a real frame there).
- Adaptive mode keeps generated frames within a share of the GPU's time. On GPUs where a generated
  frame is expensive (a laptop iGPU at 1080p), asking for more of them to reach the target slowed
  the real frames, which then asked for even more: real fps could fall from 60 to 15.

## 1.0.0-beta.4

- Shader optical flow ignores blocks with almost no texture (sky, fog, plain walls), where its matches were noise.
- Optical flow on every GPU (experimental where there is no optical flow hardware: AMD, Intel, or
  NVIDIA on OpenGL). It is measured with shaders, as a coarse-to-fine block search in the spirit of
  FSR 3's optical flow (draw passes, since the game's renderer has no compute shaders), with a
  median filter against wrong matches. Its measuring resolution follows its GPU time (about 1 ms at
  most), so slower GPUs measure smaller. NVIDIA RTX on Vulkan keeps using its optical flow hardware.
  The setting is now "Optical flow (beta)"; its tooltip says which kind your GPU uses.
- The Quality preset turns optical flow on, and the Low-end preset turns it off (its GPU time goes to
  the real frames instead).

## 1.0.0-beta.3

- Hardware optical flow on NVIDIA RTX (20 series and newer, Vulkan), in beta: the GPU's optical flow unit
  measures how the picture moved between the two real frames, and generated frames follow the part
  depth reprojection cannot see (mob limbs, fire, particles, water, animated textures). Camera and
  whole-entity motion still come from depth; the flow is used only where both directions agree and
  it differs from depth by more than a couple of pixels. On by default where supported (setting
  "Hardware optical flow", greyed out on other GPUs); interpolated mode only. About 0.25 ms of
  render-thread time per real frame. Beta: tested on an RTX 4050 only so far; turn it off if
  generated frames look wrong.
- Screenshots (F2) with render scale below 100% (for example dynamic render scale with a shader
  pack) were the small world image without the GUI. They are the full-size picture again.
- Adaptive frame generation can go up to 8x (fixed multipliers stay at 2x to 6x), for very low
  real frame rates.
- Adaptive mode asks for fewer generated frames whenever some get dropped (drawn, but no swapchain
  image free when due): they cost GPU time and were never shown. The ceiling moves in tenths and
  recovers within seconds once drops stop.
- Fixed input lag and the game slowing down and catching up in waves with the present queue: frames
  could queue two or three real frames deep. The queue is now kept about one real frame deep
  (generated frames beyond that are not drawn), which also gave real frames their GPU time back.
- README: "What to expect" (real frame rate cost, adaptive targets, input delay, upscaling with
  shader packs).

- Generated frames no longer go missing with a GPU-heavy shader pack on Vulkan (NVIDIA): each one
  now takes a swapchain image only when it is due, on a second queue of its own, instead of holding
  one while it waits. Before, with the render thread a frame ahead, the swapchain ran out of images
  and adaptive mode fell well short of its target (for example ~130 of 180 fps). Used only with a
  plain 8-bit sRGB swapchain (not with an HDR mod) and when the GPU has a spare graphics queue;
  otherwise frames go through the swapchain as before.

## 1.0.0-beta.2

- Blocks next to moving entities no longer flicker in generated frames. Only pixels an entity
  actually drew move with it: the depth is kept from before entities are drawn (they get a pass of
  their own), so the floor under a mob and walls beside it stay put. Works with shader packs too
  (with Iris the entities are drawn just before the held item, so depth of field still focuses on
  them). The padded box around each entity also no longer reaches below its feet or into blocks
  beside it.
- Motion blur no longer smears moving entities the camera follows: each entity's own motion since
  the previous shown frame is taken out of the blur on the pixels it drew (interpolated mode).

## 1.0.0-beta.1

First beta: all planned features are in.

- Moving entities in generated frames: mobs, players, boats and minecarts now move with their own
  motion between real frames instead of only the camera's (they used to jump to the next real frame's
  position, a stutter). Root motion of the 32 nearest entities, from their bounding boxes, so it works
  the same with model mods (EMF/ETF, Fresh Animations).
- Temporal upscaling (Upscaler: Temporal): the world renders with a sub-pixel jitter that changes
  every frame, and a full-resolution history accumulates the samples (reprojected with depth, clipped
  to the current frame's colours so changes don't ghost). Cleaner edges than the spatial upscalers,
  and it anti-aliases (FXAA/SMAA are skipped), also at 100% render scale. The terrain sampler gets a
  matching mip bias. Shaders off only for now (edge-adaptive with a pack). The Quality preset uses it.

- An unreadable config file is no longer lost: a copy (vframegen.properties.broken-<time>) is kept
  before settings are first saved over it; if the copy can't be made, that save is skipped.

- First-launch setup: on a fresh install, the first seconds in a world are measured, and a preset
  and target frame rate (the display's refresh rate) are picked and announced in a toast. Existing
  configs are left alone; set firstRunPending=true in the config to run it again.

- Presets (General, on the Sodium page too): Low-end, Balanced and Quality set frame generation,
  render scale, upscaling and anti-aliasing together; changing any of them by hand shows Custom.

- Dynamic render scale (Upscaling, off by default): lowers the render scale (down to 50%) when real
  frames are too slow for the target frame rate, and raises it again when there is room, in 5% steps
  (at most 15% at a time, at most every two seconds). It shares the target frame rate with adaptive
  frame generation, aiming for half of it in real frames while frame generation is on; the render
  scale slider is greyed out while it is on.

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
