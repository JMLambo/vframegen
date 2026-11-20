# VFrameGen

Frame generation for Minecraft **26.3** (Fabric) that works on the **Vulkan** renderer, as well as OpenGL.

Other frame-generation mods call OpenGL directly. VFrameGen only uses Mojang's own renderer
abstraction (`com.mojang.renderpearl`): textures, a render pipeline with a GLSL shader that the game
compiles itself, and the window surface's `acquire → blit → present`. So it runs on any backend
the game runs on.

## How it works

Every pass through the game loop it shows **one generated frame between two real frames** (2x).

1. While a real frame renders, it copies the world colour, the world depth (taken just before the
   held item is drawn), the held item's depth, and the final image with the GUI.
2. On the next loop, right after the new camera has been worked out and before the real frame
   renders, it warps the previous frame to a camera pose **between** the two real frames, presents
   it, and gets a fresh swapchain image for the real frame.

The warp is a depth-aware reprojection (fixed-point search per pixel, starting from the
rotation-only answer, which is exact for the sky). The camera pose is interpolated between real
frames, not predicted, so there is no guesswork about input, but it does follow the real frames
instead of getting ahead of them. The held item and the GUI are kept still rather than reprojected.

## Compatibility

- **Sodium**: no conflict; Sodium changes chunk rendering, VFrameGen hooks the frame around it.
- **Iris**: works. Shader packs replace the depth buffer, so with `depthMode=AUTO` frames are
  reprojected by camera rotation only while a pack is on (exact when turning, approximate when
  moving). Set `DEPTH` to try full reprojection with your pack.
- **Distant Horizons**: LOD terrain that is not in the vanilla depth buffer is treated as distant
  (rotation only), which is what it nearly is.

## Settings

In game: **Mod Menu → VFrameGen** (needs Cloth Config), or `config/vframegen.properties`.
Toggle key: **F9** (rebind under Controls → VFrameGen).

- VSync caps the shown frame rate at your refresh rate, and real frames at half of it. Turn VSync
  off, or use VRR/FreeSync, to get the most out of it.
- The in-game frame rate limit applies to real frames. A limit of 60 shows ~120.
- The F3 FPS counter counts real frames only; the log prints real / generated / shown every 5 s.

## Limits (v0.1)

- Fixed 2x. No 3x/4x yet.
- Disocclusions (what was hidden behind something before you moved) are filled from neighbouring
  pixels, so fast sideways motion can smear edges a little.
- Transparent HUD parts (hotbar background, chat) show the world behind them as of the last
  real frame.

## Building

JDK 25, then `./gradlew build`. The jar is in `build/libs/`.
