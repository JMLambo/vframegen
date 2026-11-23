<p align="center">
  <img src="src/main/resources/assets/vframegen/icon.png" alt="VFrameGen icon" width="96">
</p>

<h1 align="center">VFrameGen</h1>

<p align="center">
  <a href="https://github.com/JMLambo/vframegen/actions/workflows/build.yml"><img src="https://github.com/JMLambo/vframegen/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/JMLambo/vframegen/releases"><img src="https://img.shields.io/github/v/release/JMLambo/vframegen?include_prereleases&label=release" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
</p>

Frame generation for Minecraft **26.3** (Fabric) that works on the **Vulkan** renderer, as well as
OpenGL, including with **Iris shader packs**, **Sodium** and **Distant Horizons**. It also has
**render scale** upscaling (spatial or temporal), **dynamic render scale** and **anti-aliasing**,
so a weaker PC can play smoothly at a decent resolution.

Other frame-generation mods call OpenGL directly. VFrameGen draws through Mojang's own renderer
abstraction (`com.mojang.renderpearl`): textures, render pipelines with GLSL shaders the game
compiles itself, and the window surface. On Vulkan it adds a few low-level hooks for exact frame
pacing.

> **Status: beta.** All planned features are in and it works well on the setups it was tested on
> (see [Tested with](#tested-with)). Expect rough edges on other hardware and mod combinations, and
> please [report them](https://github.com/JMLambo/vframegen/issues/new/choose).

> **Integrated graphics:** recommended to use only **upscaling and anti-aliasing** (render scale
> below 100%, an upscaler, and FXAA or SMAA) when playing on integrated graphics, with frame
> generation off. (If your PC doesn't have a dedicated graphics card, this applies to you.) There,
> a generated frame costs almost as much as a real one, so frame generation trades real frames for
> generated ones: the picture looks a little smoother, but the game responds worse.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 and
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download the jar from [Releases](https://github.com/JMLambo/vframegen/releases) and put it in
   your `mods` folder.
3. Optional: [Sodium](https://modrinth.com/mod/sodium) (VFrameGen adds a page to its video
   settings), [Mod Menu](https://modrinth.com/mod/modmenu) and
   [Cloth Config](https://modrinth.com/mod/cloth-config) for the full settings screen, and
   [Controlify](https://modrinth.com/mod/controlify) for controller bindings.
4. In game, select **Video Settings → Graphics Backend → Vulkan** for the best pacing, turn
   **VSync off**, and press **F9** to toggle frame generation.

On the first launch, VFrameGen measures your PC for a few seconds in the first world you join and
picks a [preset](#presets) and a target frame rate (your display's refresh rate). A message says
what it picked; change it any time in the settings.

Requires Java 25, which Minecraft 26.3 already uses.

## Tested with

| | |
|---|---|
| Minecraft | 26.3, Fabric Loader 0.19.5+ |
| Backend | Vulkan (present thread) and OpenGL, both with and without shader packs |
| GPU / OS | NVIDIA RTX 4050 Laptop and AMD Radeon 660M (integrated, RADV), Linux (Fedora, KDE Wayland); NVIDIA GTX 1060 3 GB with an i7-7700 and 8 GB RAM, Windows (vanilla renderer, Vulkan) |
| Mods | Sodium 0.9.3, Iris 1.12 (a local build with Distant Horizons 3.4 and Sodium 0.9.3 support), Distant Horizons 3.4, Entity Model Features / Entity Texture Features with Fresh Animations, Controlify 3.5.3, Reflex 1.1.6. Also tested without Sodium, Iris and Distant Horizons (vanilla renderer) on both backends. |
| Shader packs | Complementary Unbound r5.10 + Euphoria Patches 1.11.2 (RTX 4050), Mellow 3.5 (Radeon 660M: about 30 real fps on its Fancy preset) |

On integrated graphics a generated frame at 1080p costs about as much as a real one (on the 660M:
about 4 ms for VFrameGen's passes plus the copies and presenting), so use only upscaling and
anti-aliasing there (see the note at the top). Distant Horizons is also a big cost, so set its CPU
and Quality presets low.

Reports from other GPUs (Intel, desktop AMD) and other shader packs are very welcome.

## How it works

**Interpolated mode** (default, 2x to 6x). Each real frame is held back by one frame. The frames
between the one on screen (A) and the held one (B) are built from both, then B is shown:

1. While a real frame renders, VFrameGen copies the world colour and depth (taken just before the
   held item is drawn), a mask of the held item, and the final image with the GUI.
2. When the next real frame is finished, it warps A and B to cameras **between** them. This is a
   depth-aware reprojection: a fixed-point search per pixel, and rotation only for the sky. One of
   the two frames, the one nearer in time, builds the picture. The other only fills what that one
   could not see.
3. The camera between two frames blends position, rotation, projection and the view matrix's
   offset, so view bobbing stays smooth.
4. **Moving entities** (mobs, players, boats, minecarts) move with their own motion between the
   two frames, not just the camera's. VFrameGen tracks the 32 nearest by their position and
   bounding box, never their model, so it works the same with model mods such as Entity Model
   Features and Fresh Animations.
5. The held item and the GUI are not warped. They come from the frame nearer in time.
6. **Optical flow** (beta, on by default) measures how the picture actually moved between the two
   frames. Where it differs from what depth predicted (mob limbs, fire, particles, water, animated
   textures), generated frames follow it too, wherever the forward and backward measurements
   agree. NVIDIA RTX cards on Vulkan use their optical flow hardware; other GPUs use a shader
   version (experimental).

**Adaptive frame generation** (off by default). Instead of a fixed multiplier, VFrameGen picks
how many frames to show per real frame (1 to 8, two more than a fixed multiplier) so that real and generated frames together reach a
target frame rate. When the real frame rate drops, more frames are generated; when the real frames
reach the target alone, none are. Real frames are capped at the target. When generated frames get
dropped (drawn, but no swapchain image was free when due), it asks for fewer for a few seconds,
so that GPU time goes back to the real frames.

**Low-latency mode.** Nothing is held. The last real frame is warped toward the camera of the
frame about to render. This has less delay but is less accurate.

### Pacing

- **Vulkan, present thread** (default). The render thread renders everything and hands the final
  `vkQueuePresentKHR` calls to a dedicated thread. That thread presents each real frame as soon as
  it is ready. It then waits on the frame's GPU timeline semaphore to learn when that frame is
  really on screen, and presents each generated frame at its exact share of the measured interval.
  The queue and swapchain are shared under one lock. Generated frames are rendered right after the
  real frame, never during world rendering, so shader packs are not disturbed.
- **OpenGL, or present thread off.** Generated frames go out in Minecraft's frame-limiter sleep,
  timed from a GPU fence, or else at the very start of the next loop. That needs idle time, so the
  auto frame cap adapts real FPS until they fit.

**Auto frame cap.** With Max Framerate on Unlimited, real FPS is capped at display refresh ÷
multiplier, so no frame is produced that the screen cannot show. Some virtual displays (for example
Sunshine streaming) report 60 Hz while running at 120. In that case set **Display refresh rate** by
hand.

## What to expect

Frame generation makes motion smoother; it does not make the game itself run faster.

- **Real frame rate drops a little.** Every generated frame takes GPU time, so the game renders
  slightly fewer real frames with frame generation on (typically 10–20%, more at high multipliers
  with a heavy shader pack). For example, 41 real fps with it off became 37 real + 37 generated
  (75 shown) at 2x in one test with Complementary and Distant Horizons.
- **Adaptive mode may not reach its target.** When the GPU is already maxed out, more generated
  frames would only lower the real frame rate further, so adaptive mode settles below the target
  instead (and asks for fewer whenever generated frames are being dropped). A lower target, or
  lighter shader settings, gets closer to it.
- **Input delay is a bit higher in interpolated mode.** Each real frame is held back until the next
  one is ready, so the frames in between can be built from both; that adds about one real frame of
  delay (less noticeable at higher real frame rates). Low-latency mode does not hold frames back, at
  some cost in accuracy, and Reflex helps either way.
- **On integrated graphics, use upscaling and anti-aliasing instead.** (If your PC doesn't have a
  dedicated graphics card, this means you.) Integrated graphics share the computer's memory and
  have far less GPU power, and a generated frame needs nearly the same work as a real one to put on
  screen: reprojecting every pixel, copying the image, and presenting it. On a Radeon 660M at
  1080p, each generated frame cost about as much time as a real one, so 50 real fps with frame
  generation off became about 40 real + 20 generated: a little smoother, but the game itself
  responded worse. A render scale below 100% with an upscaler and FXAA or SMAA makes the real
  frames cheaper instead, which raises the real frame rate outright, with no artifacts. Adaptive
  mode keeps generated frames to a share of the GPU's time, so leaving frame generation on can't
  drag the real frame rate down far, but at 1080p it gains little. (It does better at lower
  window resolutions, where each generated frame costs less.)
- **With a shader pack, use upscaling too.** A render scale of about 75–85% (edge-adaptive, which
  is what Temporal falls back to with a pack) frees the most GPU time where it is scarcest: a
  higher real frame rate means less input delay, fewer artifacts, and generated frames that have
  less to guess. Much lower and shader packs get visibly grainy.
- **Optical flow follows more motion (beta).** Generated frames also follow what depth alone
  can't: mob limbs, fire, particles, water and animated textures. NVIDIA RTX cards (20 series and
  newer, Vulkan) measure it with their optical flow hardware. Other GPUs (AMD, Intel, or NVIDIA on
  OpenGL) measure it with shaders, which is experimental: less precise, and it costs some GPU time
  (it measures at a lower resolution on slower GPUs). If generated frames look wrong, turn off
  **Optical flow** and please report it.
- **Fast turns can show artifacts at low real frame rates.** The lower the real frame rate, the
  further each generated frame has to guess between two real ones. Above about 40 real fps it is
  hard to notice.

## Compatibility

- **Sodium**: works (tested with 0.9.2 and 0.9.3).
- **Iris**: works with shader packs on OpenGL (tested with Complementary + Euphoria Patches and Mellow;
  official Iris 1.11.7 and a local 1.12 build). Depth reprojection works with packs. The held item is
  masked by depth, because packs draw it themselves. Official Iris releases only support OpenGL: on
  Vulkan, shader packs need an Iris build with Vulkan support. With shaders off, the official Iris
  runs fine on both.
- **Distant Horizons**: works. LOD terrain reprojects with its depth (tested with 3.3.4, the
  official release, and 3.4.0 RC2). A few things to know:
  - On **Vulkan**, Distant Horizons works with or without Sodium, but not together with the
    official Iris: Distant Horizons switches itself to OpenGL to work with Iris, then refuses to run
    on Vulkan. Use OpenGL, or an Iris build with Vulkan support.
- **Voxy**: works on OpenGL with shaders off (tested with Voxy 0.2.20-beta, with Voxy Extra and
  Voxygen, at 100% and 67% render scale). With a shader pack on, Voxy 0.2.20-beta itself crashes
  with the official Iris 1.11.7 (inside Voxy's own Iris support), which VFrameGen can't change.
  Voxy only supports OpenGL.
- **Controlify**: optional. It adds controller bindings for the toggle and the debug capture.
- **Reflex**: works with the present thread, and recommended: it noticeably cuts input lag with
  frame generation on.

## Presets

**General → Preset** (also on the Sodium page) sets frame generation, render scale, upscaling and
anti-aliasing together. Changing any of them by hand shows **Custom**.

| Preset | Frame generation | Render scale | Upscaler | Anti-aliasing | Optical flow |
|---|---|---|---|---|---|
| Low-end | adaptive | dynamic | bicubic | FXAA | off |
| Balanced | adaptive | dynamic | edge-adaptive (FSR 1) | SMAA | left as set |
| Quality | adaptive | 100% | temporal (TAA) | from the temporal upscaler | on |

## Render scale

**Upscaling → Render scale** (50–100%). The world and the shader pack render at a fraction of the
window size. The image is then upscaled, and the GUI stays at full resolution. Three upscalers:

- **Bicubic** (default): Catmull-Rom, clamped so edges don't ring, then contrast-adaptive
  sharpening. The fastest.
- **Edge-adaptive (FSR 1)**: a port of AMD FidelityFX Super Resolution 1.0's EASU and RCAS. It
  estimates each edge's direction and filters along it, so edges stay sharp without jaggies, then
  sharpens without halos. A little more GPU time.
- **Temporal (TAAU)**: the world renders with a sub-pixel offset that changes every frame, and a
  full-resolution history gathers the samples (reprojected with depth; anything that changed is
  rejected so it doesn't ghost). Cleaner edges than the others, and it anti-aliases on its own,
  also at 100% render scale, where it is plain TAA. Shaders off only for now: with a shader pack it
  uses edge-adaptive.

**Dynamic render scale** lowers the render scale (down to 50%) when real frames are too slow for
the target frame rate, and raises it again when there is room. It aims for half the target in real
frames while frame generation is on (frame generation makes up the rest).

## Anti-aliasing

**Quality → Anti-aliasing**: **FXAA** (cheapest) or **SMAA** (the reference implementation by
Jorge Jimenez et al.; cleaner edges, sharper textures), with a quality setting from Low to Ultra.
It runs on the world before the GUI, so the HUD stays sharp. Not needed with the temporal upscaler.
Most shader packs have their own anti-aliasing.

## Settings

In game: **Video Settings → VFrameGen** (with Sodium) for the main options, **Mod Menu →
VFrameGen** (needs Cloth Config) for all of them, or `config/vframegen.properties`.
Toggle key: **F9** (rebind under Controls → VFrameGen).

- **General**: preset, on/off, mode, multiplier (2–6x), adaptive frame generation and the target
  frame rate, depth mode, pacing, present thread (Vulkan, needs a restart), auto frame cap,
  display refresh rate.
- **Upscaling**: dynamic render scale, render scale, upscaler (bicubic, edge-adaptive or temporal)
  and sharpening.
- **Limits**: when not to generate frames (camera jumps, fast turns, very slow real frames).
- **Quality**: sharpening of generated frames, anti-aliasing and its quality, motion blur, optical
  flow (beta; its tooltip says whether your GPU uses hardware or the experimental shader version),
  GUI detection threshold.
- **Debug** (all off by default): a magenta tint on generated frames, F8 captures (images and
  validation frames go to `vframegen-debug/`), a rolling log of the last ~10 s of presented frames,
  and stats, pacing and stall lines in the game log.

The F3 screen shows `VFrameGen: N real + M generated`, and the FPS counter includes generated
frames.

## Tips

- **Leave VSync off.** The present thread does the pacing, and VSync makes generated frames stutter
  (they get shown twice or dropped). With frame generation on, VFrameGen turns VSync off at startup,
  and if you turn it back on it asks whether to keep it.
- Leave **Max Framerate** on Unlimited and let the auto cap pick the real frame rate.
- Install [Reflex AntiLag](https://www.curseforge.com/minecraft/mc-mods/reflex-antilag) to cut input lag. Interpolation still adds about
  one real frame, so a lower multiplier (higher real frame rate) also feels more responsive.
- Hitches from Minecraft itself are amplified visually: with smooth frames on either side, a
  40 ms stall looks like a jump. This happens while exploring new terrain (chunk meshes, Distant
  Horizons LODs, shadow geometry). Pre-generating the world, lowering shadow distance or Distant
  Horizons' CPU load, and a low-pause collector (`-XX:+UseZGC`) all help.

## Known issues

- **OpenGL without Sodium: no optical flow, and render-scale frame generation draws at full
  resolution.** The game's own OpenGL renderer lets a draw read only 12 textures (Sodium raises
  that to 128). Optical flow needs 14 and drawing generated frames at the render scale needs 13, so
  VFrameGen checks the limit and leaves them off by itself (the setting is greyed out with a
  tooltip). Releases up to beta.5 failed on the first generated frame on this setup and could
  crash the game.

- Interpolation adds about one real frame of latency.
- Hitches from Minecraft itself (chunk loading, shader-pack shadow rebuilds, Distant Horizons LOD
  building) look worse with frame generation on: smooth motion, a pause, then a jump. They also
  happen with it off. See [Tips](#tips).
- Disocclusions (what was hidden behind something) are filled from neighbouring pixels, so fast
  sideways motion can smear edges a little. Things that move on their own without moving as a
  whole (waving leaves, water, swinging limbs) are only as smooth as the real frames, unless
  optical flow is on; the shader version (GPUs without optical flow hardware) is less precise.
- Entity motion only works in interpolated mode (low-latency mode has no next frame to take it
  from).
- The temporal upscaler is for shaders off (it uses edge-adaptive with a shader pack), and fast
  mobs can leave a faint trail with it.
- Transparent HUD parts show the world behind them as of the nearer real frame.
- Intel and desktop AMD GPUs have not been tested yet, and Windows only on one older PC.

## Building

JDK 25, then `./gradlew build`. The jar is in `build/libs/`. `./gradlew runClient` starts a dev
client with Sodium, Iris and Distant Horizons from `run/mods`, and uses ZGC.

Every push to `main` and every pull request is built on GitHub Actions. The jar is attached to
each run.

## Releasing

1. Set `mod_version` in `gradle.properties` (for example `0.2.0`) and commit it.
2. Tag that commit with the same version and push the tag:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

The **Release** workflow checks that the tag matches `mod_version`, builds the mod, and publishes a
GitHub Release with the jar and the version's section of `CHANGELOG.md` as its notes (changes to the mod
itself, so keep repo-only changes such as README, CI and templates out of it). A tag
with a suffix (`v0.2.0-beta.1`) is marked as a pre-release.

## Contributing

Bug reports and testing on other setups are the most helpful. See [CONTRIBUTING.md](CONTRIBUTING.md).
Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE). The edge-adaptive upscaler is based on AMD FidelityFX Super Resolution 1.0
(MIT, © Advanced Micro Devices, Inc.); its notice is in the shader files. SMAA is the reference
implementation by Jorge Jimenez, Jose I. Echevarria, Belen Masia, Fernando Navarro and Diego
Gutierrez (MIT); its notice is in `shaders/include/smaa.glsl`.
