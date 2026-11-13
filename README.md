# Super Resolution VMR

NVIDIA DLSS Super Resolution for [VulkanMod Reforged](https://github.com/TrulyRin/VulkanMod-Reforged) on Minecraft 1.21.1 NeoForge, as a mod of its own. It works with
and without [Opal Compat](https://github.com/matybachy/opal-shaders-compat) shader packs. Nothing of VulkanMod Reforged or Opal Compat is patched or copied: the mod hooks
them with mixins and reflection.

Status: **alpha.** DLSS has run on a GeForce RTX 5060 Ti with VulkanMod Reforged 0.3.5 (Performance, 1280x720 to 2560x1440); other GPUs, packs and
settings are untried.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1, NeoForge 21.1 |
| VulkanMod Reforged | exactly `0.3.5-ALPHA+1.21.1` |
| GPU | NVIDIA GeForce RTX (DLSS runs nowhere else; elsewhere the mod stands aside) |
| OS | Windows x64 |
| Opal Compat | optional, 0.5.0-alpha or newer, for shader packs |

## Use

1. Build the jar (see [Building](#building); there is none to download) and put it in `mods`.
2. In VulkanMod Reforged's Video Settings, on the **DLSS** page, turn DLSS on and set **Render Scale** below 100 %: 65-70 % for DLSS Quality,
   55-60 % Balanced, 45-50 % Performance, 33-40 % Ultra Performance. VulkanMod Reforged's own floor is 50 %; with DLSS on the mod lets the scale go
   down to 33 % (`RenderScaleMixin`), and with DLSS off or failed the 50 % floor holds again. DLSS picks the mode that fits the scale unless one is
   chosen. The same Render Scale is on VulkanMod Reforged's own page, which shows 50 % at most for a lower scale.
3. With a shader pack, turn the pack's own TAA off if it has one: DLSS does that job, and the two jitter the picture against each other. Complementary
   (option *Temporal Filtering*, which its detail quality *High* turns on) keeps at least 75 % of the last frame on Distant Horizons' terrain
   (`lib/antialiasing/taa.glsl`, `lodChunk`), so with DLSS on top the far terrain blurs more than anything else.

DLSS runs while the world is drawn at the Render Scale, which is when no screen is open. With a screen open (inventory, chat) VulkanMod Reforged draws at full
size and DLSS waits.

## How it works

VulkanMod Reforged draws the world into a target of its Render Scale and, before the GUI, stretches it onto the swapchain
(`DefaultMainPass.resolveScaledFramebufferToSwapchain`). This mod:

- adds the instance and device extensions DLSS needs when VulkanMod Reforged creates its Vulkan instance and device (`VulkanLifecycleMixin`,
  `DeviceExtensionsMixin`), asking NGX which ones. Nothing of NVIDIA's is loaded without an NVIDIA driver, and nothing is added to the device of another
  vendor's GPU;
- moves the game's projection by a sub-pixel jitter each frame (`GameRendererJitterMixin`), a Halton (2, 3) sequence;
- works out motion vectors and a depth for DLSS (`MotionPass`, a compute shader) from the depth of the world, the camera and the entities: every pixel is put back
  where it was in the last upscaled frame, and a pixel inside the box of an entity that moved (`EntityMotion`) is moved back by as much as the entity moved;
- takes the depth of the world from Opal Compat (`depthtex0`) with a pack, Distant Horizons' far terrain from Opal Compat's DH depth and the planes DH drew
  with, and without a pack copies VulkanMod Reforged's depth just before the game clears it to draw the hand (with VulkanMod Reforged's own
  `MainDepthSnapshot`); the hand gets no motion, since it moves with the camera;
- with Distant Horizons, hands DLSS one depth for the near and the far terrain, with the game's near plane and DH's far plane: the game's own depth stops at its
  far plane, where the sky is too, and DLSS could not tell far terrain from sky when deciding which history to throw away;
- gives textures a mip bias of log2(Render Scale) on top of VulkanMod Reforged's own -0.5 (`SamplerManagerMixin`), so they are sampled at the mip level the
  screen would take and not one for the smaller picture (at 33 % that is 1.6 levels sharper); the log says `texture mip bias` when it changes;
- in the resolve, has DLSS write a full-size picture from the scaled colour, the depth and the motion vectors, and binds that picture where VulkanMod Reforged
  would bind the scaled colour (`DefaultMainPassMixin`). The GUI is drawn over it at full size, as before.

When VulkanMod Reforged shuts its Vulkan down, DLSS releases its feature and NGX first. Its feature and images are also released after 60 s without
an upscaled frame (Render Scale back at 100 %), and after 120 frames in a row it could not upscale it waits 30 s before trying again. If the device cannot
be created with DLSS's extensions, it is created again without them and DLSS stays off.

Any failure turns DLSS off for the session and leaves the frame to VulkanMod Reforged. The log says what happened in lines starting with `[dlss]`; NGX's own log is
in `srvmr/` in the game folder.

## Settings

On the DLSS page of VulkanMod Reforged's video settings, and in `config/srvmr.properties`, written at the first start and by the page. Changes
from the page take effect at once; DLSS starts its history afresh with them.

| Key | Default | |
|---|---|---|
| `enabled` | `true` | `false` leaves everything to VulkanMod Reforged |
| `quality` | `auto` | `auto`, `quality`, `balanced`, `performance`, `ultra_performance`, `dlaa`; a mode the Render Scale is not made for gives way to the one it is (NGX has a model for each mode's own ratio) |
| `jitter` | `true` | the sub-pixel jitter; DLSS needs it for detail |
| `invertJitterX`, `invertJitterY` | `false` | the sign of the jitter DLSS is told; try if the picture shimmers when standing still |
| `invertMotionY` | `false` | the sign of the vertical motion; try if the picture smears when looking up and down |
| `packDepth` | `forward` | how a pack's depth is stored: `forward`, `direct`, `reversed` |
| `packDepthUpsideDown` | `true` | whether a pack's depth has its rows the other way round from the picture |
| `vanillaDepth` | `direct` | how VulkanMod Reforged's own depth is stored |
| `handDepth` | `0.5625` | with a pack, depth below this is the hand, which moves with the camera |

The jitter sign, the motion sign and the depth layouts are read from the code of VulkanMod Reforged and Opal Compat, not seen in a game; they are settings so that a
wrong one can be fixed without a new build.

## Known limits

- Entities move as a whole: mobs, players, items, arrows, minecarts, boats and Create's contraptions get their own motion, but what an entity does in place (a
  turning head, swinging legs, a contraption that rotates) does not, and may leave a faint trail. Boxes are the hit box grown sideways and upwards by a margin, so
  a little of what is right beside a moving entity moves with it. Only moving entities in view count, at most 256, nearest first.
- Particles, falling rain and snow, and moving block entities (pistons) are not entities and get the motion of what is behind them.
- Without a shader pack, Distant Horizons' far terrain is taken as lying at the far plane, which is a fraction of a pixel off while walking.
- Only the exact VulkanMod Reforged version above.

## Building

The NVIDIA DLSS SDK is not in this repository, and nothing built from it may be committed: NVIDIA's licence does not allow the SDK to become subject to an
open-source licence (section 3 e of the SDK's `LICENSE.txt`), and this repository is GPL. That is why there is no jar to download here: every jar with DLSS
is built from the steps below, and is for the builder's own use.

### What you need

- Windows x64 and [Git for Windows](https://git-scm.com/download/win) (with Git Bash).
- Visual Studio 2022 or newer, or its Build Tools, with the x64 C++ tools (the workload *Desktop development with C++*), and CMake 3.20 or newer: the
  workload's own or one on `PATH`.
- A JDK 21, whose JNI headers the bridge is built with.

### 1. Download the NVIDIA files

In Git Bash, in this repository:

```bash
bash native/get-sdk.sh
```

[`native/get-sdk.sh`](native/get-sdk.sh) downloads only the files the build uses, about 60 MB, into `dlss-build-deps` next to this repository (another folder
can be given as its argument):

| Folder | From | Files |
|---|---|---|
| `dlss-build-deps/DLSS` | [NVIDIA/DLSS](https://github.com/NVIDIA/DLSS), tag `v310.9.16` | `LICENSE.txt`, `include/`, `lib/Windows_x86_64/x64/nvsdk_ngx_s.lib` (and `_d`), `lib/Windows_x86_64/rel/nvngx_dlss.dll` |
| `dlss-build-deps/Vulkan-Headers` | [KhronosGroup/Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers), tag `v1.4.368` | `include/`, `LICENSE.md`, `LICENSES/` |

Downloading the DLSS SDK means accepting NVIDIA's licence, its `LICENSE.txt`. Without Git Bash, download both repositories at those tags by any other way and
keep the same folders.

### 2. Build the bridge

In cmd or PowerShell; the script prints this line with the paths filled in:

```
native\build-windows.cmd C:\path\to\dlss-build-deps\DLSS C:\path\to\dlss-build-deps\Vulkan-Headers "C:\path\to\jdk-21"
```

It writes `native\build\srvmr_ngx.dll`. The JDK folder can be left out when `JAVA_HOME` is a JDK 21. More in [native/README.md](native/README.md).

### 3. Build the jar

```
gradlew build -x test
```

`gradle.properties` already looks for the SDK in `../dlss-build-deps/DLSS` (`dlssSdkDir`) and for the bridge in `native/build/srvmr_ngx.dll`
(`nativeBridge`); for other folders set those two there or pass `-PdlssSdkDir=... -PnativeBridge=...`, with forward slashes. The jar,
`build/libs/srvmr-<version>.jar`, carries `srvmr_ngx.dll`, `nvngx_dlss.dll` and NVIDIA's licence in `natives/windows-x64/`. If either DLL is missing the build
says `srvmr: no native bridge ... the jar has no DLSS` and the jar runs without DLSS.

## Licence

The mod's code: GPL-3.0-or-later ([LICENSE](LICENSE)). NVIDIA DLSS: NVIDIA RTX SDKs licence, carried in the jar next to the DLSS runtime. NVIDIA and DLSS are
trademarks of NVIDIA Corporation. Not made by or with NVIDIA, the authors of VulkanMod Reforged or the authors of Super Resolution.

The earlier work on `main` (VulkanMod Reforged and Opal Compat sources patched in place) is replaced here by mixins; it stays on `main`.
