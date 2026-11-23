# Contributing

Thanks for wanting to help. Bug reports with a clear way to reproduce them are the most useful
thing you can contribute: frame generation depends heavily on the GPU, driver, OS, shader pack and
other mods, and the more setups it is tested on, the better.

## Reporting bugs

Use the **Bug report** issue form. Before you file:

- Check whether it also happens with frame generation **off** (F9). Hitches, crashes and visual
  problems that happen either way usually come from Minecraft or another mod.
- For pacing or stutter problems, turn on **Mod Menu → VFrameGen → Debug → Stats in the log** and
  **Pacing in the log**, reproduce it, and attach `logs/latest.log`.
- For visual problems, turn on **Debug captures (F8)**, press F8 while it happens, and attach the
  images from `vframegen-debug/`.

## Building and running

- JDK 25.
- `./gradlew build` builds the jar into `build/libs/`.
- `./gradlew runClient` starts a development client. Put the mods you want to test with
  (Sodium, Iris, Distant Horizons, ...) in `run/mods/`, and select the Vulkan backend in
  Video Settings, or set `preferredGraphicsBackend:"vulkan"` in `run/options.txt`.

## Pull requests

- Keep a pull request to one change, and say what you tested it with (backend, GPU, shader pack,
  other mods), with frame generation on and off.
- `./gradlew build` must pass; GitHub Actions builds every pull request.
- Code that runs every frame should not allocate or log unless a Debug option is on.
- Say in the description whether AI wrote any of it (the **AI use** boxes in the template). AI-generated
  changes are welcome, but they must be thoroughly tested in game before you open the pull request;
  human-made ones are welcome too. A check asks pull requests from contributors to answer this: it
  only checks that you did, not whether it is true.

## Branch rules

`main` only changes through pull requests, for everyone including the maintainer: the build must
pass, and `main` cannot be force-pushed or deleted. The rule lives in `.github/rulesets/main.json`;
`scripts/apply-branch-ruleset.sh` applies it (or updates it) on GitHub. No approving review is
required, so a solo maintainer can still merge their own pull requests.

## Where things are

| Area | Files |
|---|---|
| Capturing real frames, building generated ones, pacing | `FrameGenerator.java`, `FrameSlot.java` |
| The warp/blend shader | `shaders/core/reproject.fsh`, `shaders/core/lockmask.fsh` |
| Vulkan present thread and its locking | `PresentThread.java`, `mixin/VulkanSurfaceMixin.java`, `mixin/VulkanQueue*Mixin.java`, `mixin/VulkanCommandEncoderMixin.java` |
| Render scale | `RenderScale.java`, `shaders/core/upscale.fsh`, `shaders/core/sharpen.fsh` |
| Settings | `FrameGenConfig.java`, `ClothConfigScreen.java`, `lang/en_us.json` |
| Debug tools | `DebugCapture.java`, `StallProbe.java` |

By contributing you agree that your contribution is licensed under the MIT License, like the rest
of the project.
