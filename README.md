<img src="src/main/resources/assets/vframegen/icon.png" alt="" width="96" align="right">

# VFrameGen

[![Build](https://github.com/JMLambo/vframegen/actions/workflows/build.yml/badge.svg)](https://github.com/JMLambo/vframegen/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/JMLambo/vframegen?include_prereleases&label=release)](https://github.com/JMLambo/vframegen/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Frame generation for Minecraft **26.3** (Fabric) that works on the **Vulkan** renderer, as well as
OpenGL, including with **Iris shader packs**, **Sodium** and **Distant Horizons**. It also has
**render scale** upscaling.

Other frame-generation mods call OpenGL directly. VFrameGen draws through Mojang's own renderer
abstraction (`com.mojang.renderpearl`): textures, render pipelines with GLSL shaders the game
compiles itself, and the window surface. On Vulkan it adds a few low-level hooks for exact frame
pacing.

> **Status: alpha.** It works well on the setup it was developed on (see
> [Tested with](#tested-with)). Expect rough edges on other hardware and mod combinations, and
> please [report them](https://github.com/JMLambo/vframegen/issues/new/choose).

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 and
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download the jar from [Releases](https://github.com/JMLambo/vframegen/releases) and put it in
   your `mods` folder.
3. Optional: [Mod Menu](https://modrinth.com/mod/modmenu) and
   [Cloth Config](https://modrinth.com/mod/cloth-config) for the settings screen, and
   [Controlify](https://modrinth.com/mod/controlify) for controller bindings.
4. In game, select **Video Settings → Graphics Backend → Vulkan** for the best pacing, turn
   **VSync off**, and press **F9** to toggle frame generation.

Requires Java 25, which Minecraft 26.3 already uses.

## Tested with

| | |
|---|---|
| Minecraft | 26.3, Fabric Loader 0.19.5+ |
| Backend | Vulkan (present thread) and OpenGL, both with and without shader packs |
| GPU / OS | NVIDIA RTX 4050 Laptop and AMD Radeon 660M (integrated, RADV), Linux (Fedora, KDE Wayland); NVIDIA GTX 1060 3 GB with an i7-7700 and 8 GB RAM, Windows (vanilla renderer, Vulkan) |
| Mods | Sodium 0.9.3-beta.4, Iris 1.12 (a local build with Distant Horizons 3.4 support), Distant Horizons 3.4.0-RC2, Controlify 3.5.3, Reflex 1.1.6. Also tested without Sodium, Iris and Distant Horizons (vanilla renderer) on both backends. |
| Shader packs | Complementary Unbound r5.10 + Euphoria Patches 1.11.2 (RTX 4050), Mellow 3.5 (Radeon 660M: about 30 real fps on its Fancy preset) |

On integrated graphics, frame generation itself is cheap (about 0.4 ms per generated frame on the
660M); Distant Horizons is the biggest cost, so set its CPU and Quality presets low.

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
4. The held item and the GUI are not warped. They come from the frame nearer in time.

**Adaptive frame generation** (off by default). Instead of a fixed multiplier, VFrameGen picks
how many frames to show per real frame (1 to 6) so that real and generated frames together reach a
target frame rate. When the real frame rate drops, more frames are generated; when the real frames
reach the target alone, none are. Real frames are capped at the target.

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

## Compatibility

- **Sodium**: works.
- **Iris**: works with shader packs (tested with Complementary + Euphoria Patches). Depth
  reprojection works with packs. The held item is masked by depth, because packs draw it
  themselves.
- **Distant Horizons**: works. LOD terrain reprojects with its depth.
- **Controlify**: optional. It adds controller bindings for the toggle and the debug capture.
- **Reflex**: works with the present thread, and recommended: it noticeably cuts input lag with
  frame generation on.

## Render scale

**Upscaling → Render scale** (50–100%). The world and the shader pack render at a fraction of the
window size. The image is then upscaled, and the GUI stays at full resolution. Two upscalers:

- **Bicubic** (default): Catmull-Rom, clamped so edges don't ring, then contrast-adaptive
  sharpening. The fastest.
- **Edge-adaptive (FSR 1)**: a port of AMD FidelityFX Super Resolution 1.0's EASU and RCAS. It
  estimates each edge's direction and filters along it, so edges stay sharp without jaggies, then
  sharpens without halos. A little more GPU time.

## Settings

In game: **Mod Menu → VFrameGen** (needs Cloth Config), or `config/vframegen.properties`.
Toggle key: **F9** (rebind under Controls → VFrameGen).

- **General**: on/off, mode, multiplier (2–6x), adaptive frame generation with a target frame
  rate, depth mode, pacing, present thread (Vulkan,
  needs a restart), auto frame cap, display refresh rate.
- **Upscaling**: render scale, upscaler (bicubic or edge-adaptive) and sharpening.
- **Limits**: when not to generate frames (camera jumps, fast turns, very slow real frames).
- **Quality**: sharpening of generated frames, GUI detection threshold.
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

- Interpolation adds about one real frame of latency.
- Hitches from Minecraft itself (chunk loading, shader-pack shadow rebuilds, Distant Horizons LOD
  building) look worse with frame generation on: smooth motion, a pause, then a jump. They also
  happen with it off. See [Tips](#tips).
- Disocclusions (what was hidden behind something) are filled from neighbouring pixels, so fast
  sideways motion can smear edges a little. Things that move on their own (waving leaves, water,
  mobs) are only as smooth as the real frames.
- Transparent HUD parts show the world behind them as of the nearer real frame.
- The GUI is not warped, so HUD elements that turn with the camera (the F3 axis gizmo) only
  update at the real frame rate and trail behind the world while turning.
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
GitHub Release with the jar and notes generated from the commits since the last release. A tag
with a suffix (`v0.2.0-beta.1`) is marked as a pre-release.

## Contributing

Bug reports and testing on other setups are the most helpful. See [CONTRIBUTING.md](CONTRIBUTING.md).
Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE). The edge-adaptive upscaler is based on AMD FidelityFX Super Resolution 1.0
(MIT, © Advanced Micro Devices, Inc.); its notice is in the shader files.
