# CurseForge page draft (not committed)

## Project settings
- **Name:** VFrameGen
- **Summary (≤ 250 chars):** Frame generation, upscaling (FSR 1 / temporal) and anti-aliasing for Minecraft 26.3 on Vulkan and OpenGL. Works with Sodium, Iris shaders and Distant Horizons.
- **Class:** Mods
- **Categories:** Performance (main), Utility & QoL
- **License:** MIT
- **Source URL:** https://github.com/JMLambo/vframegen
- **Issues URL:** https://github.com/JMLambo/vframegen/issues
- **Logo:** src/main/resources/assets/vframegen/icon.png (upscale to 400×400)

## First file upload
- File: vframegen-1.0.0-beta.1+mc26.3.jar (from the GitHub release)
- Display name: VFrameGen 1.0 Beta 1
- Release type: **Beta**
- Game version: 26.3 · Loader: Fabric · Java 25 · Environment: Client
- Relations: **Required** Fabric API · **Optional** Sodium, Cloth Config, Mod Menu, Controlify
- Changelog: paste the 1.0.0-beta.1 section of CHANGELOG.md

## Description

**Smoother Minecraft on weaker PCs.** VFrameGen generates extra frames between the real ones, renders the world at a lower resolution and upscales it, and smooths jagged edges, all inside the game's own renderer.

### Why I made it
I originally made VFrameGen for myself, so I could run shaders at a good preset on my budget gaming laptop and still hit my display's refresh rate. Later I decided to publish it, to help players on similar or weaker PCs get better performance on their own setups.

### Features
- **Frame generation**, 2x to 6x, or **adaptive** to hit a target frame rate
- Works on the new **Vulkan** renderer (with exact frame pacing) and on **OpenGL**
- **Moving mobs and players** are tracked, so they don't smear (works with EMF/ETF and Fresh Animations)
- **Render scale** with Bicubic, Edge-adaptive (FSR 1) or **Temporal** upscaling
- **Dynamic render scale** that holds your target frame rate
- **FXAA / SMAA** anti-aliasing with a quality slider
- **Presets** (Low-end, Balanced, Quality), picked for you on first launch
- A page in **Sodium's** video settings

### Compatibility
Sodium, Iris shader packs, Distant Horizons, Controlify and Reflex all work. Temporal upscaling is for shaders off; with a shader pack it falls back to FSR 1.

### Getting started
1. Install Fabric Loader and Fabric API for 26.3.
2. Video Settings → Graphics Backend → **Vulkan**, turn **VSync off**.
3. Press **F9** to toggle frame generation.

### AI disclosure
The code for VFrameGen was written by an AI coding assistant (Claude), directed by me. I set the goals, tested every build in game on my own hardware, and reported problems until it ran well. The full source code is public on [GitHub](https://github.com/JMLambo/vframegen).

### Status
**Beta.** Please report problems on [GitHub](https://github.com/JMLambo/vframegen/issues), with your GPU, OS and mod list.
