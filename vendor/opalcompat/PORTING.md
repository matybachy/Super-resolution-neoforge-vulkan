# PORTING.md

Porting log: status, decisions, known problems and the origin of taken code. The initial analysis is
in [ANALYSIS.md](ANALYSIS.md).

Up to version 0.3.0 the mod was called Vitrail VK, with Vitrail Bridge next to it. Since 0.4.0 it is one mod, `opalcompat`, at the user's request.
Older commits, tags and jars in `release/` carry the old names. The code taken from Vitrail stays in `dev/vitrail` and its origin is in `NOTICE`.

## Status

Note: "done in code" means compiled and statically checked against the sources, not working. What really ran in the game is
in Phase 2 results and Results on Vulkan.

| Item | Status |
|---|---|
| 1 References and build | done, `./gradlew build` passes |
| 2 Mixins and runtime | done, ran in the game (`test_final`, `test_composite`, `test_terrain`) |
| 3 Options, noisetex, mipmaps, blends | done in code |
| 4 Shadows: terrain, entities, block entities, `shadowcomp` (graphics), mipmaps | done in code |
| 5 Sky, entities, hand, particles, weather, clouds | done in code |
| 6 Custom textures, image.*, SSBO, compute | done in code |
| 7 GUI for choosing a pack and its settings | done in code |
| A Flywheel, Create | fallback done in code: Flywheel off, Create through BERs; no backend of our own |
| B Other mods including Mekanism | done in code: mapping by Iris's table, bytecode checked |
| C Distant Horizons including `dh_*` | done, ran in the game with LODs, see Results on Vulkan |
| D Opal Compat: Opal and Vectors on VMR, shared GUI | done, ran in the game, see below |

**Restoration, 2026-11-02:** the original `vulkan-shaders` folder, including its local git history (M1-M5), disappeared
from the disk (it was not in the Recycle Bin either). The project was restored from the conversation and the same revisions of the
references, and the history starts over. `ANALYSIS.md` is restored in a shortened form.

## How to build and run

- Java 21: the system `JAVA_HOME` on this PC points to JDK 8. Set it to `C:\Program Files\Zulu\zulu-21`.
- `./gradlew build`: `build/libs/opalcompat-<version>.jar`. The first build downloads VMR into `libs/` and checks its SHA-256
  (`gradle.properties`).
- `scripts/release.py "text"`: after a commit and a build, checks the jar's metadata, compares its classes with the jar the tests ran on, and copies
  it to `release/`, recording it in `release/LATEST.txt`.
- `./gradlew checkPacks [-Ppacks=...] [-PdumpAll=true]`: without the game, translates and compiles all programs of the packs
  in `run/shaderpacks`, including mesh families the way the renderer loads them (entities, sky, particles, `shadow_entities`),
  graphics `shadowcomp` and compute. It also checks that every vertex input has a source. It used to skip the graphics half
  of a path with both `.csh` and `.fsh`.
- `scripts/autotest.sh [pack] [-Pautotest.options=A=b,C=d] [-Pautotest.camera=x,z,yaw,pitch,height]
  [-Pautotest.time=6000]`: build, client in autotest mode, summary. The output is in `run/autotest/`.
- `scripts/instance.py create|run NAME`: a clean instance with NeoForge in `test-instance/NAME` (outside git, jars into `mods/`),
  no dev run. `run` takes `--pack`, `--options`, `--script`, `--camera`, `--time`. The output is in `test-instance/NAME/autotest`.
- `scripts/instance.py install NAME [--renderer gl] [--extra DIR]`: the fresh jar, optionally without VMR, and the jars
  from `test-instance/jars/DIR`. A run on OpenGL is driven by `compat/GlAutoTest` with the same properties and scripts.
- Autotest script (`--script`, lines: `cmd`, `wait`, `settle`, `shot`, `dump`, `fps`, `gui`, `slot`, `graphics`, `resize`,
  `minimize`, `restore`, `pack`, `reload`, `screen`, `click`, `press`, `emptyupload`, `error`, `note`, `quit`), see `dev/AutoTest`. Scenes are
  in `scripts/scenes/`. The test window is a normal window: the autotest closes a screen the script did not open, but moving the mouse
  still affects the result.
  `dump` saves all targets of the current pack as PNG (`render/TargetDump`).
- `scripts/glscan.py jar...`: direct GL calls in a mod's bytecode, including nested jars.
- `./gradlew checkPacks -PdhVariant=true`: everything with `DISTANT_HORIZONS` defined; the `dh_*` programs are always checked this way.
- `scripts/pixel.py file.png x,y ...`: pixel values, no dependencies.
- `scripts/take_from_vitrail.py`: takes group a from Vitrail into `src/main/java/dev/vitrail`. **Careful:** it would overwrite
  the changes in `PackProgram`, `ShaderProperties` and `TargetPlan` (marked `OpalCompat:`), add them back by hand afterwards.
- `-Pvalidation=false` turns off validation layers (`tools/vvl`, Khronos nightly, outside git).

A pack is chosen in the game: VMR's video settings, group **Shaders** on the left (saved to
`config/opalcompat.properties`, key `shaderPack`). A pack's settings are in `shaderpacks/<pack>.txt`, the same file
as with Iris. The system properties `opalcompat.pack`, `opalcompat.profile` and `opalcompat.options` (autotest) take precedence
until a pack is chosen in the game.

## Opal, Vectors and VMR (package `compat`, since 2026-11-03)

Everything is one client mod, with VMR, Opal and Vectors all optional. The Vulkan part starts from `VulkanSide` only with VMR in the game and its
mixins with it (`OpalCompatMixinPlugin`); without it only the part for Opal on OpenGL runs. Nothing is copied from Opal or Vectors: `src/stubs` only
names the members called, for compiling, and does not go into the jar. The old jars `vitrailvk` and `vitrailbridge` are incompatible in `neoforge.mods.toml`,
and the game says to remove them.

**Installation on Vulkan:** VMR, `opalcompat`, Opal, Vectors, LibLibLib, NeoForge at least 21.1.259 (Opal requires it). Not `create-dyn-light`,
see `BLOCKERS.md`.

**Why Opal does not draw on VMR.** First log: Opal started through VMR's GL emulation, `glTexImage2D` of a depth texture failed
(`Invalid texture source byte size`) and a minute later `VK_ERROR_DEVICE_LOST` followed. In `GlFunctionRegistry` the functions `glDrawBuffers`,
`glBindImageTexture`, `glDispatchCompute` and `glTexImage3D` are empty, so its pipeline can't stand there. `OpalOnVulkanMixin` cancels
`Opal.rebuild`, the pipeline never comes to exist and all its hooks are empty. Its screens and keys stay: `OpalLink` keeps the selection of both mods the
same and `Opal.requestReload` loads the pack in Vitrail after a change of the selection or the settings file. Without Vitrail
`OpalClient.conflict` reports and there are no shaders.

**"Too many custom images".** Second log, Opal on OpenGL: it gives every image a fixed unit and takes at most 7, the user's Complementary
Unbound + Euphoria has 8 (`Opal custom images` in the log). `OpalImagesMixin` removes the limit and `CustomImageUnits` assigns units per
program at each bind, like Iris. Verified: Opal + Vectors on OpenGL, that pack, `opal_active=true`.

**GUI.** The basis is the registry `ModSettingsRegistry`, which VMR has for this. `ShaderPages`: on/off, pack, settings, list,
folder, state; takes effect after Apply. `VectorsPages`: Workload and Tuning pages, words and ranges from the Vectors screen, without the
OpenGL terrain renderer options. With Opal the buttons lead to its screens, which have the Vectors look. Vectors got a VulkanMod page
and its two extra buttons disappeared from the Options screen. `VectorsOnVulkanMixin`: Vectors counts Opal as a shader mod and would turn off
limits that make sense here.

**Terrain.** `TerrainFormat` decided before the mod was constructed, so outside tests (where the pack comes as `-D`) it ended up off
and a pack would want a restart. Now it asks the `shaderpacks` folder: with a pack in it the mesh is extended and turning one on at run time takes nothing.

## Results on Vulkan (2026-11-03)

NeoForge 21.1.265, RTX 5060 Ti, validation layers, 854x480 window, fresh jars. The pack is Complementary Unbound + Euphoria Patches
with the settings from the user's instance unless stated otherwise.

| Test | Result |
|---|---|
| VMR, `opalcompat`, Opal, Vectors: startup, world | works, the first log does not repeat |
| Only VMR and `opalcompat`, without Opal and Vectors | works (0.4.0) |
| Clouds | work after the fix below |
| Hand, enchanted sword, torch, chest, sign, banner, campfire, fire, mobs | work after the fix below |
| Sunset, night, rain, cave, underwater, block outline | work, validation clean |
| Window resize, minimizing | work, validation clean |
| BSL, Complementary Reimagined, Bliss: scene `world3` | run, log clean; of the images, day was inspected |
| Turning off, turning on, another pack from VMR and from Opal, no restart | works |
| Pack settings from Opal: saving, reloading in Vitrail | works |
| Vectors switch on the VMR page, Apply | saved to `vectors-1.21.1.json` |
| Create 6.0.10 + addons: motor, shafts, gears, belt, mixer, press, water wheel, tank | drawn with the pack and with shadows, `flywheel:off` from the log |
| Create: mechanical piston as a contraption | moves, both plank and pole slide |
| Mekanism 10.7.19 + addons: machines, cube, tanks, cables, generators, Atomic Disassembler in hand | drawn with the pack |
| DH 3.3.3: LODs by day and night, through `dh_*` | work |

**Clouds, the proof.** Debug modes in a copy of the pack (output from `deferred1` into the unused `colortex11`): noise is fine (`noisetex` and
`GetCloudNoise` give structure), `z0` = 1, `cameraPosition.y` = 78, `cloudAlt1i` = 200. The direction was wrong: `viewPos.z` > 0 on the sky
and `nPlayerPos.y` = -0.91 when looking up. Cause: VMR gives `getDepthFar` = infinity and JOML builds such a matrix with an epsilon of
1e-6, so `w` at depth 1 is negative and the sky lies behind the camera. `GetVolumetricClouds` then returns 0 right at `maxPlaneDistance < 0`.
An attempt with an injection into `getDepthFar` did not work: the mixin export showed VMR's handler first and returning, so `GameRendererDepthFarMixin`
redirects the call in `getProjectionMatrix`. After the fix `w` = 1/510, `nPlayerPos.y` = +0.5 in the center when looking 30 degrees up, `clouds.a` > 0.

**Shape of the matrices.** The same measurement gave `P[3][2]` = -0.2 instead of -0.1. The mixin export has nothing under `org/joml`, so VMR's `Matrix4fM`
is not applied and the matrices are OpenGL [-1,1]. `forwardToReversed` made 2z-w of them: near 0.1 and entity shadows with a different depth than terrain.
`glToReversed` publishes the matrix unchanged, the hand multiplies the z row by 0.125 like Iris, DH has its volume in the same form.

**Purple sky with an enchanted sword.** A plain sword changes nothing, an enchanted one changes `colortex0` on the sky from (51,91,227) to (5,1,15), and
back once put away. A hard red glint in a copy of the pack coloured the whole sky, so the glint program was drawing it. Cause: vanilla calls
`setShader(position)` (`LevelRenderer:962`), right after it `renderSky` (`:963`), and that takes `RenderSystem.getShader()` (`:1618`). The shadow pass
runs in between, the player holds the sword in it and the last type is `glint`; `Families.resolve` tests `glint` before `Phase.SKY`. `LevelRendererMixin`
restores the shader after the pass.

**"Not building!".** An enchanted item in hand took the pack down at once (the fallback to plain VMR worked): the hand and shadows had a `MultiBufferSource`
with one shared buffer and the glint ended the item being written. `PassBuffers` is a set like vanilla's.

## Crashes in the Stellarity mod set (2026-11-04)

A copy of the 356 jars and configs of the user's instance in `test-instance/stellarity`, memory as there (`--jvm=-Xmx15360m`), the user's folders unchanged.

| Crash | Cause and proof | Solution |
|---|---|---|
| `Texture is null at index: 0` (report 22.24.34, in a test 18.18.39) | Railways uploads a sprite from the tick of the block atlas (`PhantomSpriteManager.renderTick`), until the load is over the atlas has an id but no image, and `VTextureSelector.uploadSubTexture` throws an NPE. OpenGL and VMR's `GlTexture.texSubImage2D` skip it. | `VTextureSelectorMixin` |
| `Cannot get config value before config is loaded` (22.17.11, in a test 18.20.19, there without `opalcompat`) | `ConcurrentModificationException` in `EveryCompat.forAllModules`: `addModule` is `synchronized`, walking is not, and VanillaBackport, No Man's Land and Gems Realm add modules from their constructors. NeoForge then loads no configs and the first tick crashes. | `EveryCompatModulesMixin` |
| `OutOfMemoryError` / hang on a full heap | The set has no FerriteCore, see `BLOCKERS.md`. | Put FerriteCore back in `mods` |
| `Failed to create mod instance. ModID: createdeco` (`2026-11-11_17-23-37.log`) | `ConcurrentModificationException` from `HashMap.computeIfAbsent` in Ponder's `StitchedSprite.<init>`, reached from Create Deco's `SpriteShifts.<clinit>` through Create's `CTSpriteShifter.getCT` on a `modloading-worker` thread. `StitchedSprite.ALL` (a map of `ArrayList`s), Ponder's `SpriteShifter.ENTRY_CACHE` and Create's `CTSpriteShifter.ENTRY_CACHE` are plain `HashMap`s (`javap` of `ponder-neoforge-1.0.82+mc1.21.1.jar` inside the Create jar, and of `create-1.21.1-6.0.10.jar`), filled from the constructors of every Create addon at once. | `CreateStitchedSpriteMixin`, `CreateSpriteShifterMixin`, `CreateCtSpriteShifterMixin`: every read and write of the three, and the add to a list, under one lock (`compat/CreateSprites`); the stitch walks a copy. Built, not run |
| `Chunky is not loaded` when creating a world (19.18.02) | DH 3.3.3 binds to Chunky on the first chunk, Chunky comes to exist only in `ServerStartingEvent`, after the first chunks have loaded. | `DhChunkyMixin` |

**Result.** Final jar, FerriteCore, G1, 15 GB: atlas 16384x8192, new world, joined, `status=OK`, no crash. Regression: pack, Create,
Mekanism, DH, Railways `status=OK`, log without errors from the mod.

**Timing of `Texture is null`.** VMR lets an upload through only in the last tick of a frame (`SpriteUtil.setDoUpload(j == n)`, `MSpriteContents.checkUpload`), otherwise it
drops it. So the crash depends on how long the frame is at that moment: of the six runs with the mod that got that far it happened in two (both with the old jar), in the single
run without the mod it did not. The mod's code is not on the stack and it does not call `SpriteUtil`. The exact test is the command `emptyupload` (scene `emptyupload.txt`), which works without Railways: without the mixin
`NullPointerException: Texture is null at index: 0`, with it `empty upload passed`, log without errors.

**Every Compat.** Without the fix it happened in 2 of 6 starts, with it in 0 of 4. That alone says little; the proof is the bytecode export: `forAllModules` walks
`redirect$...$opalcompat$copy`, `private static synchronized`, so a copy made under the lock of the class, the same one as `addModule`.

## How DH and chunks looked (2026-11-08)

No crash and no pack error in the logs of the Create instance: what was wrong showed only in the picture. Tested on a copy of its 184 jars and settings in
`test-instance/createset` (Fast graphics, no AO, render distance 8, DH 3.3.3, Complementary Unbound + Euphoria), in singleplayer.

| Symptom | Cause and proof | Solution |
|---|---|---|
| Dots, jagged edges, flicker, dark water | `ChainPlan.swapBack()` was never called. Dumps: `colortex2` main half blank, the image only in `_alt`. The pack reads last frame from the main half: TAA in `composite6`, reflections from `colortex7` at `composite.glsl:210`, both with `Clear = false`. | `PackRenderer.swapBack`, ALT to MAIN after the final pass, as in Iris's final pass. Dumps now show main equal to alt. |
| Dark wall around the player where vanilla chunks end, water changing colour there | Order of hooks. The exported `LevelRenderer.renderSectionLayer` calls `vulkanmod$renderSectionLayer`, then `distanthorizons$renderChunkLayer`, so far water came after near water. `dh_water` discards where `depthtex0 < 1`, which then held the near water's depth, while `water.glsl:210` fades near water with no floor under it towards `far` to let the far water through. So nothing was there and the sea bed showed. A debug output from `dh_water` to `colortex11` (red material, green depth under the water, blue alpha) was black inside the ring, and is filled after the fix. | `DhSupport.waterBeforeWorld` from `terrainLayer`; `VkDhBridge.takeWater` drops DH's own later call. |
| Stops of 0.5 to 1.3 s at the first mob, the first broken block | Timed: translating and SPIR-V about 100 ms a program, `vkCreateGraphicsPipelines` 500 to 1259 ms, 33 pipelines 11.8 s, nothing kept between runs. | `PipelineCache`, `config/opalcompat_pipeline_cache.bin`, header checked against the GPU. Next run: 21 pipelines in 29 ms. The log says `[pack] N pipelines built in M ms`. The first run of a new pack or new settings still pays. |
| One log line of 1.8 MB, one hint repeated per program | 49,788 names of dropped blocks; the same two unanswered uniforms in every program. | 20 names in the log, all of them at debug level; said once per pack. |

**Not verified:** a multiplayer server, which is where the user's log came from; whether the flicker is gone to the user's eye (two frozen frames differ by 0.71 per channel on
average, and water and clouds keep moving; no comparison with the old jar, the user's decision); the first run of a pack, whose stops the cache does not help.

## Flicker and slow far terrain (2026-11-09)

The user's log of 2026-11-08 21:59 is from a server, render distance 5, 2560x1440, DH 256 chunks. No error of this mod in it; the flicker was seen in the
window of `test-instance/createset` while moving. A still camera does not show it: two frozen frames differed by 0.44 per channel at most, and 0.44 is what the old
measurement took for good. `scripts/scenes/blink.txt` with `render/BlinkProbe` measures it with the camera moving: every frame shrunk to cells of 64 px, and a cell
that jumps and jumps back counts as a blink.

| Symptom | Cause and proof | Solution |
|---|---|---|
| Every shadow on the screen jumps for one frame while walking | Vitrail fills the shadow map at the end of a frame and samples it through the next, which is what `mapShadowModelView` is for: last frame's matrix moved onto this camera. This mod fills the map at the head of the frame that samples it (Iris's order) but still published that pair. The fresh matrix is snapped to `shadowIntervalSize`, so the two part by a whole cell on the one frame the camera crosses into another: every 2 blocks. Slow drift over 10 blocks, 5 crossings: 5 frames of 856 with a blink before, 0 of 894 after, same scene in one run. | `ViewMatrices.advanceShadow`: a frame that fills the map publishes the pair it draws with. |
| The water turns white beyond a hard straight line, and light and history swim, only while walking | The game multiplies the walk bob, the damage tilt and the nausea into its projection. Iris moves them to the model view (`MixinModelViewBobbing`: "causes severe issues with most shaderpacks"), Vitrail splits them where a pack reads them (`CameraBob`). This port passed the identity, with a comment that Iris does the same. A pack's `ScreenToView` reads the projection as a diagonal, so the position it rebuilds from depth swings with each step. Same path in one run (`scripts/scenes/water_line.txt`): `wl_bob3.png` white plate and a hard line, `wl_nobob2.png` none. | `GameRendererMixin` keeps the first `getProjectionMatrix` of `renderLevel`; `LevelCapture.bob()` is that matrix's inverse times what the level is drawn with. A pack gets the clean projection and `bob * view`, whose product is the same matrix, so the same pixels. After: `wl_bob3.png` as standing. |
| Far terrain loads slowly | `RenderThreadTaskHandler` allows half a frame at the frame limit, and `1000 / 260 / 2` is 1.5 ms; it stops once `total + 2 * task` passes that. `LodBuffer` went through `TransferQueue.copyBufferCmd`, one `vkQueueSubmit` for each buffer, so about one task a frame. Loader threads wait in `.join()` in `LodRenderSection`, and they hold slots of the one pool of `numberOfThreads` (`PriorityTaskPicker.occupiedThreadsRef`) that file reads and LOD building need as well. | `DhRenderTasksMixin`: 5 ms, once a frame. `LodUploads`: one frame's copies in one submission to the graphics queue through `UploadManager`, ahead of the frame that draws them. Measured after: 900 to 1200 buffers and 260 to 360 MB per 10 s from the database. No number from before: not compared with the old jar, the user's decision. |
| Far terrain's indices fetched over the bus | `LodIndices` lay in host memory, and a far view reads millions of them a frame. | On the device, 256 K quads to begin with. |
| `Unexpected error in [LodRequestModule] tick loop` on joining a server | Two threads unload the same level, `DhClientWorld.unloadLevel` takes null out of the map. | `DhClientWorldMixin`. |
| 21 warnings `reads colortexN from the half it writes` | `lib/uniforms.glsl` of Complementary declares every colortex in every program. What reads white there is mostly a name nothing reads. | Debug level. |

With both fixes, in `blink.txt` at render distance 5: standing 0 of 441 frames with a blink, slow drift 0 of 929, flying high 0 of 866, turning 6 of 888 and none wide,
no near or far section gone for a few frames and back in any of these. Flying low and walking still count blinks (555 of 978, 775 of 1305), and the frames saved for
them show near walls and buildings going by, a fine pattern passing through a cell: the image detector cannot tell that from a blink close to the ground. There 1 near
and 17 far sections of hundreds were gone and back. Sections at the edge of the frustum, which the bob moves to and fro, would count like that; not verified.

Two more from reading the code, not observed in a run: `buildRenderList` copies the list of enabled sections and then asks each whether it is still enabled, while the
quad tree swaps the list and disables the old sections from its own thread (`DhRenderListMixin`); and a section closed by that thread only has its flag cleared, its
buffers staying until a task on the render thread, so the draw no longer skips it. `LodWatch` writes a line when far terrain goes missing for a frame, for logs from
a server, which the tests cannot reach.

`FpsReducer2` is held out of `test-instance/createset` (`test-instance/jars/createset-held`): without focus it slows the render thread so far that a load takes tens of minutes.

**Not verified:** a server, where the user's log came from; the user's own instance; whether anything still flickers to the eye. The black reflection of mountains in water is seen in `wl_still.png` and was not looked into.

The upload idea comes from [distant-horizons-vulkanmod](https://github.com/Braffolk/distant-horizons-vulkanmod) (LGPL-3.0-only, Fabric on 1.20.6 and 1.21.11, so it cannot
run here): keep DH's upload task cheap, and an index buffer big enough from the start. No code of it is taken.

## Far terrain at the speed of the frame rate (2026-11-09)

The user's log of 2026-11-09 17:25 (`logs/2026-11-09_17-25-08.log`): a new world (`saves/New World (1)`, its DH database 6.4 MB), Complementary Unbound, 2560x1440,
render distance 5, `maxFps:260`, ZGC with 15 GB. `[dh] 598 LOD buffers, 122 MB uploaded in 10 s`, then `920 LOD buffers, 128 MB uploaded in 24 s`; slow next to the
same set with Sodium. Built, not run: the user tests it in the game.

| Where it waits | Proof | Change |
|---|---|---|
| Every section waits for a frame | `LodRenderSection.getAndBuildRenderDataRunnable` builds the mesh on a loader thread and then `uploadToGpuAsync(...).join()`. `LodBufferContainer.tryMakeAndUploadBuffersAsync` puts its "LodBufferContainer Setup" and "LodBufferContainer VBO Upload" on `RenderThreadTaskHandler`'s queue, which runs once a frame from `ClientApi.renderLodLayer`. | `DhRenderTasksMixin` runs those two (and "IBO Upload") on the thread that queues them, where `LodTransfer` can take them. |
| The waiting thread holds a slot of DH's one pool | `PriorityTaskPicker.startNextTask` starts a task while `occupiedThreadsRef < numberOfThreads`; IO, LOD Builder, Update Propagator and World Gen take from the same slots, and "Render Loader" has priority 6 against their 5 (`ThreadPoolUtil.setupThreadPools`). | The loader waits for its own copy only, well under a millisecond, and the slot is free for the rest. |
| So the frame rate set the pace | At 40 FPS a section waits 25 ms on average plus its share of the 1.5 ms task budget; with no pack on OpenGL at several times the frame rate the same wait is that much shorter. | `LodTransfer`: the vertices go into a pooled staging buffer and through VulkanMod's transfer queue, with a command pool of its own; the thread waits for that fence, and DH lists the section after that, as before. |

Safety of the new road: the device buffers come from VMA directly (`LodDeviceBuffer`), which locks itself, because `MemoryManager.createBuffer` keeps its books in a map that
`MemoryManager.freeBuffer(long, long)` changes without the lock (`Drawer`, `ImageUtil`). They are shared between the graphics and the transfer family (`VK_SHARING_MODE_CONCURRENT`), and go
through VulkanMod's queue of frames when DH lets them go (`addFrameOp`). Submissions hold the transfer queue's monitor, as VulkanMod's `Queue.submitCommands` does, and `QueueLock`,
which VulkanMod's `Vulkan.waitIdle` and this mod's waits for the device take too (`VulkanIdleMixin`); `Vulkan.cleanUp` stops the copies. Each far terrain draw starts with a barrier
that makes the copies visible to vertex input. Where the transfer queue is the graphics queue, or a copy fails, the uploads go back to the render thread (`LodUploads`), and the log
says so once. The log line every 10 s now says how many buffers came from DH's threads.

Not verified: all of it at run time; whether DH's world generation on a new world, which these numbers may be limited by as well, is now the slower part.

## Freezes at first sight (2026-11-09)

The user's log `logs/2026-11-09_20-05-59.log`, on a server with Complementary Unbound: 214 printed frames over 25 ms in 100 s, 12 of them over 300 ms. In VulkanMod's
own breakdown of those frames (`upload`, `sched`, `graph`, `fence`, `gpuWait`, `present`) nearly nothing is spent, so the time is this mod's. Built, not run.

| Freeze | Proof | Change |
|---|---|---|
| 1501 ms at 20:15:38, 738 ms at 20:16:27 | `[pack] 12 pipelines built in 1404 ms` printed at 20:15:51 and `3 pipelines built in 633 ms` at 20:16:37; `PipelineCache` prints ten seconds after the last pipeline. The driver builds a pipeline of this pack it has not seen in up to a second, on the frame that asked. | `PackPipeline.readyPipeline`: mesh pipelines are built on the shader thread, and the draw asking is left out until its pipeline is there. |
| 1142, 654 and 533 ms at 20:15:11 to 20:15:16, just after joining | Pipelines came from the cache then (`28 pipelines built in 44 ms`). In the debug log of 18:26 the same frames follow the first use of `gbuffers_entities`, `gbuffers_textured`, `gbuffers_block`. Timed outside the game, a mesh program of this pack takes 50 to 790 ms to translate and 26 to 280 ms to compile. | `MeshPrograms`: both happen on the shader thread; what asks for a program still being prepared is left out. |

The shader thread is the only reader of the open pack once it is loaded (`ShaderPackSource` keeps memos for one thread), and `MeshPrograms.close` waits for it before the pack closes.
`FrameStats` prints every ten seconds what the mesh draws cost a frame and how many draws were left out waiting: the 25 to 40 ms frames, 170 of the 214, are not explained yet,
and this is the number to start from.

Left as it is: the 5.4 s of `[pack] ... loaded in 5392 ms`, which is on the loading screen while joining.

## Blocks late or invisible: VulkanMod Reforged's upload ceiling (2026-11-11)

Built, not run: the user tests in the game.

The report: blocks invisible, and blocks appearing very late. The logs `2026-11-10_20-35-33.log` and `2026-11-11_15-58-59.log` of the user's instance, and
`config/vulkanmod_settings.json` there, which has `"chunkUploadsPerFrame": 1` ("Chunk Upload Ceiling", 1 to 16; VMR's presets use 3, 5, 8 and 16).

- VMR's `TaskDispatcher.updateSections` uploads at most that many finished sections a frame (`getMaxUploadsPerFrame`, through `AdaptiveChunkUploadBudget.chooseBudget`,
  which returns the ceiling when there is no pressure). In the spike lines of the first log 673 frames uploaded one section and 418 none.
- Finished meshes wait in an `ArrayBlockingQueue` of 64 (`ChunkSchedulingPolicy.MAX_PENDING_COMPILE_RESULTS`). It stood at 64 after 160 of the 1121 spike frames.
- A builder that finishes while it is full waits in `scheduleSectionUpdate`, offering every 10 ms, and that wait is inside what VMR times as the build: 164 ms on
  average, 95.7 s at most.
- While a mesh waits, the next change to its section cancels it (`RenderSection.createCompileTask` calls `cancelTasks`), so a section that keeps changing can keep
  its old mesh, or none, for as long as the changes go on.
- The second log is two short stays of about two and about half a minute, at 16 to 47 frames a second: at one section a frame, not enough for the 121 columns of
  render distance 5. The first join of the session also rebuilt the world once (`BlockStateIds.rebuildIfMoved`, "The block ids moved, from 0 states"), but VMR's
  buffers were still empty then (`[ChunkDiag] buffers: Vertex Buffers: 0/0 MB` a second later), so that cost nothing.

What Opal Compat draws of the terrain was compared with VMR's own draw and is the same: the area queue, `DrawParameters`, the layers VMR folds with
`uniqueOpaqueLayer` (`BuildTask.compactRenderTypes` and `TerrainRenderType.COMPACT_RENDER_TYPES`). No error or warning about chunks is in either log.

`terrain/ChunkFlow`, so that the ceiling no longer decides it:

- `TaskDispatcherFlowMixin` raises the ceiling to the number of meshes waiting, up to 64, while more are waiting than it allows. VMR's own budgets still end the
  frame's uploads: 2 ms and 6 MB, 1 to 1.5 ms and 3 to 4 MB under pressure (`AdaptiveChunkUploadBudget`). In the first log an upload took 0.1 to 0.2 ms.
- A section VMR calls urgent (`SectionGraph.isUrgentRebuild`: changed by the player, or within 27 blocks of the camera) is marked on its task
  (`SectionGraphUrgencyMixin`, `BuildTaskUrgencyMixin`). It is built before every queued task, and its mesh goes into a queue of its own that the upload empties
  first, without waiting for room in VMR's. `clearBatchQueue` empties that queue too, and `isIdle` counts it.
- `[chunks]` every 10 s, from VMR's own count (`ChunkUploadCountMixin` on `ChunkFrameDiagnostics.recordUploads`): sections and MB uploaded, frames after which
  the queue was full, how soon the urgent ones were ready after they were scheduled, and how long builders spent handing meshes over.

Off with `chunkFlow=false` in `config/opalcompat.properties`.

Not verified: all of it at run time, and whether invisible blocks are all of this kind; a block missing for another reason would show up as one that stays missing
while `[chunks]` says the queue is empty.

## Invisible blocks in a dense section: VulkanMod Reforged's 16-bit quad indices (2026-11-11)

Built, not run: the user tests in the game.

The report, after three builds that did not help: still invisible, and it is hoppers too, "simply more blocks", only around the user's base, where they join. Jade
names the blocks and their outline is drawn; with the shader pack off it is the same; breaking a block beside one changes nothing. The section below made such
sections get built, and the log of 18:11 says they were. So it is not whether a section is built but what is drawn of it.

What VMR does, in the source under `refs/` and the same in the 0.3.5 jar (`javap`):

- `Drawer`'s constructor makes `quadsIndexBuffer = new AutoIndexBuffer(QUAD_U16_MAX_VERTEX_COUNT, QUADS)`: 16-bit indices for 65536 vertices, 16384 quads.
- `WorldRenderer.renderSectionLayer` binds exactly that buffer for the opaque layers and draws each section with its own `indexCount`
  (`DrawBuffers.buildDrawBatchesDirect` and `buildDrawBatchesIndirect`).
- `DrawBuffers.stageUpload` sets `staged.indexCount = buffer.indexCount` and asks nothing of the index buffer; the only callers of
  `AutoIndexBuffer.checkCapacity` are `Drawer` and `VBO`, for immediate draws, and there `Drawer` switches to a second buffer of 32-bit indices past the 16-bit
  reach (`count > U16_MAX_INDEX_COUNT ? quadsIntIndexBuffer : quadsIndexBuffer`). Terrain has no such switch.

So a section layer with more than 16384 quads is drawn with indices read past the end of the buffer, which 16 bits could not express anyway: the first 16384
quads of the layer appear and every block meshed after them does not. This mod's own terrain draw (`PackRenderer.drawSections`, `shadowLayer`) bound the same
buffer, so it was the same with a pack.

That the user's base has such layers is in their debug log of 18:39: `Needed to grow BufferBuilder buffer: Old size 4194304 bytes` on all eight builder threads
between 3.3 and 4.4 s after the first frame, which is when the sections nearest the camera are built, and `Old size 8388928 bytes` on two. The 4 MB buffer is the
opaque layer's (`RenderType.cutoutMipped().bufferSize()`; with `uniqueOpaqueLayer` every opaque block goes there, `BuildTask.compactRenderTypes`), a builder's buffer
is emptied after each section (`RenderedBuffer.release`), and a vertex is 40 bytes here (`TerrainFormat.STRIDE`): more than 26214 quads in one layer, and more
than 52430 in two builds. Without this mod's extended vertex the same layers would have 20 bytes a vertex and not outgrow the buffer, and would pass 16384 quads
all the same: this is VMR's limit, not the extended format's.

`terrain/TerrainIndices`: `DrawBuffersUploadMixin` reports each uploaded layer's `indexCount`; the largest is kept, and from the first layer past 65536 vertices
terrain is bound to VMR's own 32-bit quad indices (`Drawer.quadsIntIndexBuffer`, through `DrawerAccessor`), grown to that size with `checkCapacity` as VMR grows it
for its immediate draws. `WorldRendererMixin` hands that buffer to `renderSectionLayer` in place of the 16-bit one, `PackRenderer` binds it in its two terrain
draws. `Drawer.bindIndexBuffer` binds with the buffer's own index type, and an index buffer that is replaced is freed through `MemoryManager.addToFreeable`, after
the frames that may still use it. The first ten such layers are logged with their section's position and quad count, which is also the check that this is what the
base has.

Not helped: the translucent layer has sorted indices of its own, which `DrawBuffers.bindBuffers` binds as 16-bit whatever `VertexFormat.IndexType.least` chose for
them; one with more than 65536 indices (10922 quads) is said once in the log.

Not verified: all of it at run time.

## Invisible stone and dirt where the player joins (2026-11-11)

Built, not run: the user tests in the game.

The report, with two screenshots: vanilla stone and dirt next to where the player joins the server are see-through. Jade names the block and its outline is
drawn, so the client has it; the sky, and dark far terrain from Distant Horizons, show through. Placing or breaking a block beside it does not bring it back, and it
is the same with the shader pack off.

The user's `logs/debug.log` of 17:44 to 17:47: from 17:46:42 on, every spike line has `rQ 21+0` (21 sections ready for a rebuild), `prep 64x` and `build 0x`, with
nothing in VMR's build queue. A ready rebuild that is prepared and never built is `RenderSection.createCompileTask` returning null, which it does on one condition:
`ChunkStatusMap.INSTANCE.chunkRenderReady(secX, secZ)` is false. That needs the column and its eight neighbours to have both `DATA_READY` (set in
`ClientLevelChunkLifecycleMixin` from `ClientLevel.onChunkLoaded`) and `LIGHT_READY` (set in `ClientPacketListenerM` from `applyLightData`). The section stays dirty,
`SectionGraph.schedulePendingRebuilds` turns it down every frame, and a block change beside it only marks it dirty again. Why these columns lost their flags is not
in the log; that the blocks are in the client's level is in the screenshots.

`terrain/ChunkReadiness`, through `RenderSectionReadyMixin` on the `chunkRenderReady` call in `RenderSection.createCompileTask`: where VMR says no and
vanilla's rule says yes, the section is built. Vanilla's rule is `SectionRenderDispatcher.RenderSection.hasAllNeighbors`: the chunks on the four sides exist
(`doesChunkExistAt`); it does not wait for light, and a light update marks the sections dirty again (`ClientPacketListener.readSectionList`). So as not to build a
section twice while its light is on the way, an unlit column (`LevelLightEngine.lightOnInSection`) waits while the client still has light to apply
(`ClientLevel.isLightUpdateQueueEmpty`), five seconds at most, and asks for a graph update each frame meanwhile so it is tried again whether or not the player moves.
The first ten such builds are logged with VMR's flags and which of the nine columns were missing or unlit, and the `[chunks]` line counts them.

Two first attempts did not do it. The first marked the flags only where all nine columns were loaded and lit, and the log of 17:55 has
`chunk -27 -186 and its neighbours are loaded, but 3 of the nine have no light yet at section 6`: the stone and dirt above the player (section 6 is y 96 to 111, the
player stood at y 95), whose light never came. The second gave the light two seconds and still asked for all nine; the log of 18:00 says nothing either way, the
player having left 40 seconds after joining, with the world still being built.

That log has the other half. For the first 20 seconds after joining, frames took 0.4 to 6 s and VMR scheduled one section a frame (`prep 1x`) with 700 waiting
(`rQ 665+0` to `rQ 707+0`): 1 section uploaded in 6 frames, then 15 in 11. Copying a section's blocks out of a chunk not copied before takes about 2 ms (`prep 1x
2,38ms` at 17:46), more than VMR's time budget of 1, 0.75 or 0.5 ms, so one section is all a frame gets. `SectionGraphUrgencyMixin` now raises the budget to 16
sections and 8 ms while more than 64 wait (`ChunkFlow.rebuilds`, `rebuildNanos`).

A guard against stale entries in VMR's cache of chunks to build from (`RenderRegionBuilder.levelChunkCache`) was tried as well and taken out again: in the log of
18:11 it replaced none in two and a half minutes.

None of this was the invisible blocks: in that log 37 to 264 sections per 10 s were built that VMR held back, and the blocks stayed away. That is the section above.

Not verified: all of it at run time, and why the flags and the light were lost.

## Frame rate: what the log of 2026-11-10 says, and a sampler for what it cannot (2026-11-10)

Built, not run: the user tests in the game.

The report: 60 to 70 frames a second falling to 45 and below. `logs/2026-11-10_14-30-24.log` of the user's instance, in the Nether of a server, 2560x1440, render distance 5,
213 mods, Complementary Unbound + Euphoria Patches with `SHADOW_QUALITY=3`, `COLORED_LIGHTING=192`, Distant Horizons at 256 chunks.

What it shows:

- `[pack]` lines: 603 to 771 frames per 10 s, the longest frame 26 to 399 ms. The pack's mesh draws take 0.93 to 1.85 ms of a frame: not where the time goes.
- VulkanMod Reforged's `[ChunkDiag] spike` lines (a frame of 25 ms or more, from `Minecraft.runTick`'s head to before `Window.updateDisplay`, so ticks are in it): 120.
  In 112 of them over 20 ms is in none of its own figures (upload, schedule, graph, fence, wait on the device, present), and 84 have no chunk built, uploaded or queued.
- Of the 78 spikes after 14:34:10 none waited on the device for more than 0.5 ms (`gpuWait`, which is the frame slot's fence and the swap chain's image). A frame that
  runs long on the processor after a frame of ordinary length finds the device idle: the frames are limited by the render thread, not by the graphics card.
- `gc` is 0 in all but two of them. The JVM runs `-XX:+UseZGC -XX:+ZGenerational` (`instance.json`), whose figure there is a cycle's length, not a pause.
- `[textures]`: 27866 animation frames uploaded and 411100 left alone in 60 s, so `AnimatedSprites` holds back about 340 uploads a tick.
- Against `2026-11-09_20-05-59.log`, the last one with a pack before that change and the shader thread: small spikes with no chunk work fell from 46.9 to 12.3 per 10 s.
  Another place in another dimension, so a hint and not a measurement.
- Distant Horizons uploaded 885 to 1109 buffers per 10 s for the whole session. The four windows with about 650 MB had 603 to 691 frames, the two with about 400 MB had
  699 and 771. Six windows: a hint that its work costs frames, nothing more. Its 8 threads run at priority 5 (`DistantHorizons.toml`), the render thread at 7
  (VMR's `RenderSystemMixin`), so they cannot take the processor from it, only share cores with it.
- vkQueuePresentKHR took 72 to 164 ms five times, three of them in a menu before the world, where nothing of this mod's draws.

What it cannot show is what a frame does with the rest of its time, and whose code that is: nothing in a log does. So:

- `diag/RenderSampler`: a thread asks the render thread for its stack every 5 ms while a pack draws a level, and every 30 s says under `[where]`, for all frames and apart
  for those of 20 ms or more: the part of the frame (tick, queued packets, entities, block entities, particles, the hud, the shadow pass, the pack's passes, far terrain,
  the wait at the frame's start, submit), whose code was running, which mods were on the stack, and the ten methods seen most. A mod is told by its module's name, which
  NeoForge sets to its id, and a mixin's handler by the mod in its name. A frame's samples are counted when its length is known. On by default while the frame rate is being
  worked on, which should be turned around after; `frameSampler=false` in `config/opalcompat.properties`.
- `render/FrameStats`: the `[pack]` line now also has the average frame, the slowest second as a frame counter shows it, and how much of the time the render thread had a
  processor, which tells a thread that works from one that waits.
- `ShaderPacks.selectedPath` asked the disk whether the pack is there at the head of every frame (`PackRenderer.ensureLoaded`); it asks once a second now.

Looked at and left: the shadow pass cannot be dropped in the Nether with these settings, because `COLORED_LIGHTING` makes the shadow program fill the pack's light voxels
(`program/shadow.glsl`, `UpdateVoxelMap`), and the pack leaves the program on unless `SHADOW_QUALITY == -1` (`shaders.properties`). No call of this mod's waits on the device
in a frame: the only ones are at a reload, a resize and a pack image's upload.

Not verified: all of it at run time, and with it the reading above that the render thread is the limit, which the new `[pack]` figure will confirm or not.

## Sodium's optimisations, the pack screens, a review (2026-11-10)

Built, not run: the user tests in the game.

**Sodium.** The user asked for what Sodium has, from `sodium-neoforge-0.9.3-beta.4+mc26.3.jar`. No code of it is taken: its `LICENSE.md` and `neoforge.mods.toml` say
PolyForm Shield 1.0.0, which forbids a competing product, and it is written for Minecraft 26.3 (`versionRange = "[26.1.2.10-beta,)"`). What it does was compared by its
mixin groups (`sodium-common.mixins.json`) against VulkanMod Reforged's (`vulkanmod.mixins.json`):

| Sodium | Here |
|---|---|
| Chunk meshing and drawing: `core.world.chunk`, `core.world.biome`, `core.model.colors`, `features.textures.scan`, regions, sorting | VMR's own: `render.chunk.build`, `TintCache`, indirect draw |
| `features.render.entity` (cuboids), `features.render.entity.cull` | VMR: `ModelPartCubeM`, `ModelPartM`, `EntityRendererM` with `entityCulling` |
| `core.render.immediate.consumer`, `features.render.immediate.*`, `features.render.particle`, `core.model.quad` | VMR: `BufferBuilderM`, `VertexConsumerM`, `SpriteCoordinateExpanderM`, `VertexMultiConsumersM`, `SingleQuadParticleM`, `BakedQuadM`, `DirectionMixin` |
| `core.render.frustum` | VMR: `chunk.FrustumMixin` |
| `features.render.entity.shadows` | Not needed with a pack: `EntityRenderDispatcherMixin` drops the blob shadows a pack's own shadows replace |
| `features.textures.animations.tracking`: animate only visible textures | Was missing. `texture/AnimatedSprites`, below |
| `workarounds.*`, `features.render.viewport`, `core.GlFallbackAllocatorMixin` | OpenGL only |
| `features.gui.hooks.*`, `features.render.gui.font`, `features.render.world.clouds`, `features.render.model` | Not taken: screens of Sodium's own, and paths a pack either replaces or that run once at load |

**Animated textures.** The instance's mods carry about 630 animated block, fluid and item textures (`*.png.mcmeta` under `textures/`), and the game uploads a frame of each
twenty times a second. `AnimatedSprites`, own design: a sprite animates once it has been drawn with. It is marked where its coordinates are asked for
(`TextureAtlasSpriteUseMixin`), where a baked quad of it is read (`BakedQuadUseMixin`; VMR's `VertexConsumerM.putBulkData` calls `getVertices`) and where VMR meshes one into a
chunk, which reads the vertices past the getters (`BlockRendererUseMixin`). Baking the models does not count (`ModelBakeryUseMixin`). Only uploads made while an atlas runs its
animations are held back (`TextureAtlasCycleMixin`, `SpriteContentsUseMixin`), and the frame between two frames is not worked out either (`SpriteInterpolationUseMixin`). A mark
is never taken back, because a mesh cached by a mod may show a sprite long after it was built. Coarser than Sodium's per-frame visibility, and it cannot freeze what such a cache
shows. A switch on the Shaders page and `animateUsedTexturesOnly` in `config/opalcompat.properties`; the log says once a minute how many frames were uploaded and how many left alone.

**Pack screens.** `screen/PackSettingsScreen` searches every page of a pack at once, by a setting's name as the pack words it and by its name in the pack's files; a match is
listed with the page it is on. Tiles are flat (`screen/Flat`): name left, value right, a switch for a toggle, a fill for a slider, yellow for what is not applied yet, the accent
for what is not the pack's own value. `ShaderPackScreen` filters by name. With Opal in the game the Shaders page used to open Opal's screens (`OpalLink`); it opens these now,
and Opal's stay behind Opal's own key.

**Review of what had not run.** `FrameStats` never printed: it asked `drawingLevel` at the end of a frame, which `afterLevel` has cleared by then; it now counts frames
`afterLevel` saw a pack draw. Framebuffers over the swap chain's images were only dropped when the size changed, and a swap chain is rebuilt at the same size too (vsync, frame
queue): `PackRenderer` and `DistantPlain` now drop them from VMR's resize callback.

Not verified: all of it at run time. Whether leaving unused textures alone is felt, and whether anything that should move stands still: mods that draw from a cache built before
the first frame without going through any of the three marks would.

## DH's start, and far terrain with no pack (2026-11-09)

Built but not run: the user tests this in the game, at their request. The GLSL of `DistantPlain` was compiled with shaderc outside the game, nothing more.

| What | Why | Where |
|---|---|---|
| The Iris check at DH's start | `logIncompatibilityWarnings` takes any `IIrisAccessor` for Iris: with `BLAZE_3D` it crashes the game (`Distant Horizons conditional mod config Exception`), with `AUTO` it forces `OPEN_GL`. | `DhStartMixin` makes this mod's adapter read as absent in that one method. Another accessor passes, and so do the other checks and every other reader of the adapter (`setDisabledDhConfigBasedOnMods`, `LodRenderer`, `RenderUtil`, `ClientApi`). |
| Far terrain with no pack | `PackRenderer.drawDistant` returned false without a pack, so nothing drew it. | `render/DistantPlain`: DH's `terrain/gl` program on Vulkan, in one render pass over the main image and a depth image of its own, as DH's own depth is on OpenGL. Its fog (`fog/gl/fog.frag`) and far fade (`fade/gl/dh_fade.frag`) are post passes over the picture on OpenGL; here the same formulas run per fragment. `PlainLodValues` reads the settings as DH's shader classes do, `LodAtlas` is `GlBlockTextureAtlas` on VulkanMod's images. |
| Order on the solid layer | VMR's hook comes before DH's (`BLOCKERS.md`), and with no pack both draw into one image. | `WorldRendererMixin` calls `VkDhBridge.beforeWorldLayer` ahead of VMR's draw, with what DH's hook would have put in `RENDER_STATE`. `DhClientApiMixin` makes the later `renderLods` of DH's hook do nothing; a pack's map still calls it, so only with no pack. |
| A pipeline VMR does not know of | `Renderer.bindGraphicsPipeline` binds nothing while the handle it holds is the one asked for, and a raw bind leaves that handle in place. | `VkDhBridge.draw` zeroes it after either route. |

Left out with no pack: SSAO, TAA, the fade of the near chunks over the far terrain (`vanilla_fade.frag`), which all read finished images, and DH's clouds and beacons.
With the walk bob the near and far planes go into the clean projection and the bob on top (`DistantPlain.combined`), or the far terrain would not sway with the chunks in front of it.

`./gradlew patchDistantHorizons` writes a copy of DH to `build/libs/distanthorizons`, same file name, that forces `BLAZE_3D` in code rather than in the config, reports `VULKAN`, and has
the two remaining OpenGL calls of its NeoForge side out of the way (`LightMapWrapper_neoforge.uploadLightmap`, `NeoforgeClientProxy.afterLevelRenderEvent`; the shader of the far terrain reads the game's own light map). The user asked for it.
`BLAZE_3D` is a name here: 1.21.1 has no Blaze3D renderer, DH's `BlazeDh*` classes in this jar are empty, `DependencySetup` has no definition for it, and the drawing is `VkDhDefinition`'s. So that copy needs this mod on VMR. The original jar keeps working too.

## Phase 2 results (interrupted by a limit, 2026-11-03)

A run in a clean instance (`scripts/instance.py`, fresh jar, NeoForge 21.1.228, RTX 5060 Ti, validation layers on):

| Test | Result |
|---|---|
| Startup without a pack | works, validation only VMR's baseline |
| `test_final`, `test_composite`, `test_terrain` | work, the only new message is `Undefined-Value-ShaderOutputNotConsumed` |
| `test_compute` | works: setup, begin without a pass, prepare without a pass, composite, `final` + `final_a` |
| BSL, default settings, one scene, day | runs with no errors in the log, see `run/baseline/bsl_basic.png`; image quality still has to be judged |

Fixed by testing: `PackImage.upload` reached for `GraphicsQueue.INSTANCE`, which VMR never sets (`DeviceManager.getGraphicsQueue()`).
It also confirmed the fallback: the pack error ended in plain VMR and a text in `lastError`.

Compile verified: `checkPacks` and `-PdhVariant=true` clean on BSL, Complementary and Bliss. What ran afterwards is in Results on Vulkan.

## Decisions

- **Mod id `opalcompat`, package `dev.opalcompat`**; the taken code stays in `dev.vitrail.*` with a header
  about its origin, changes are marked `OpalCompat:`.
- **VMR as a release jar** (`libs/`, SHA-256 from release `mc-1.21.1-0618965`), no fork; a dependency on the
  exact version, because the mixins reach into VMR's internal classes.
- **Validation layers through the Vulkan loader** (`VK_ADD_LAYER_PATH`, `VK_INSTANCE_LAYERS`), because
  `Vulkan.ENABLE_VALIDATION_LAYERS` is a constant inlined into VMR's code.
- **Compilation:** shaderc from LWJGL 3.3.3 with options of our own, optimization `zero`, because `performance`
  removes `OpName` and stages are matched by name. The source goes off-heap because of its size (`MemoryStack`).
- **SPIR-V renumbering** (`compile.SpirvModule`): descriptors by name across stages into set 0,
  stage inputs onto the outputs of the previous stage by name. Vitrail's translator stays unchanged.
- **Runtime:** our own render passes, framebuffers and pipelines with MRT, because VMR has only 1 color
  attachment. We share VMR's device, `VulkanImage` and command buffer. The pipelines also declare VMR's dynamic states,
  otherwise binding them invalidates VMR's state.
- **Orientation:** targets have row 0 at the bottom like GL, no viewport flip; `final` goes to the swapchain
  with a flip, `frontFace = CLOCKWISE` because of Vulkan's framebuffer coordinates.
- **Depth:** the game's matrices are in OpenGL form and are published to the pack unchanged (`LevelCapture.glToReversed` is the conversion for
  `ViewMatrices`), the target has depth 0..1 through `ClipSpace.FORWARD`, and while a pack runs far is finite, as with Iris.
- **Terrain vertex format:** `DrawBuffers.VERTEX_SIZE`/`POS_OFFSET` are `static final` and `QuadSorter`
  compares the format by identity, so `CustomVertexFormat.COMPRESSED_TERRAIN` itself is extended at startup (stride 40 B),
  if there is a pack in the `shaderpacks` folder. `dev/vitrail/glsl/SodiumVertex.java`
  is a replacement of our own with the same API.
- **Frame order:** `beginLevel` → `beforeSky` (clear, begin, shadow: terrain, entities, copy of `shadowtex1`, translucents,
  `shadowcomp`, prepare) → sky → opaque terrain → entities and BERs → before the first translucent layer a copy of
  `depthtex2`, opaque hand, a copy of `depthtex1` and deferred → translucents → clouds, weather → at the end of `renderLevel`
  the translucent hand → `afterLevel` (composite, `final`, only then screen effects). VMR is interrupted through
  `temporaryRenderTarget()`.
- **Meshes outside terrain** (`MeshDraw`, `MeshPrograms`, `Families`): captured in `BufferUploader._drawWithShader` and `draw`
  and in `VBO.drawWithShader`. The program is decided by the shader's name, the phase and the vertex's owner. State (blend, depth, cull, polygon
  offset) is read from `VRenderSystem`, as the game set it. Bindings: 0 mesh, 1 one record of constants per draw for what the
  format lacks (instance rate), 2 a derived per-vertex stream (`EntityIds`, `MidTexCoord`, `Tangent`), only when the
  program really reads it (`SpirvModule` reports `read`) and the vertices are at hand.
- **What a pack does not handle, VMR draws into its own target and `final` overwrites it.** That is not an error, it is just missing from the image.
  Exception: a pack with no terrain program (e.g. only a color grade) gets VMR's image in its target (`seedFromGame`).
- **Vertex identity** (`DrawIds`, `BufferBuilderMixin`, `MeshDataMixin`): a batch mixes owners and is drawn later,
  so the ID is written with each vertex; mixed batches are split into runs in the original order (`MixedMesh`).
- **GUI** (`screen/ShaderPackScreen`, `screen/PackSettingsScreen`, `VOptionScreenMixin`): screens of our own on vanilla
  widgets, Vitrail's `screen/` is written against 26.x. The page model is the taken `pack/menu` (`PackMenu`, `MenuValues`)
  and `settings/PackSession`, the file is `SettingsFile`. The entry is a mixin into `VOptionScreen.addButtonsWithSearchBar`:
  `ModSettingsRegistry` can only do option pages, not a button and a pack's nested pages. Left click is the next value, right the
  previous, shift the default; Cancel leaves, Apply writes and reloads, Done both, as with Iris.
- **Pack failure:** an exception while loading or drawing leads to `report()`: the pack turns off, plain VMR draws, a toast, the full error
  text including shaderc diagnostics on the Shader Packs screen and a Copy Error button. After a change `PackRenderer.reload()`
  also rebuilds the mesh (`allChanged`), because `block.properties` is in it, as in `Iris.reload`.
- **Restart:** only when the game started with an empty `shaderpacks` folder. Everything else at run time: VMR reads the first 20 B of the extended
  vertex. The cost is double the terrain memory, even for a player with the pack turned off, if one is installed.
- **Device features** (`DeviceManagerMixin`): `independentBlend`, `geometryShader`,
  `fragmentStoresAndAtomics`, storage image formats and more, where the GPU has them.
- **Mipmaps:** `colortexNMipmapEnabled` are generated with a blit before the pass that wants them, on the side it
  reads (like Iris). Normals/specular without a resource pack get a neutral 1×1 texture.
- **Barriers** (`ImageLayouts`, `VulkanImageMixin`): transitions from/to `SHADER_READ_ONLY` and `GENERAL` go through
  `ALL_COMMANDS`, because VMR counts only on reads in the fragment stage and does not know `GENERAL`, and packs also read in the vertex stage. This also holds
  for VMR's images; the layout VMR tracks stays true. A global barrier only for a program with storage.
- **Compute** (`PackComputes`, `PackRenderer.dispatch`): the moments follow Iris. `setup` at each new target size
  (Iris: after construction and on `changed`, `dispatch(1, 1)`), `shadow` after clearing the map's depth over the map's resolution,
  `shadowcompN` before its pass over the screen size, `begin/prepare/deferred/composite/final` before their pass;
  a program with no pass (`TargetPlan.passing()`) at its moment, even if nothing draws after it in that phase. Like Iris:
  a barrier before each dispatch and after the whole group, `colorimg`/`colortex` on the halves of the given pass
  (`TargetSchedule.step`/`passing`), `shadowcolorimg`, `indirect.<stem>`. Zero groups means a pass that is off. A compute
  that does not compile or fails drops out on its own with a log, and the pack runs on (Iris would refuse the whole pack; a missing effect is
  less harm than a pack turned off). The shared barrier is `ALL_COMMANDS`: simple and correct, not the fastest.
- **Memory and descriptors** (`FrameArena`, `allocateSet`): grow by pages instead of failing, recycled after waiting
  on that slot's fence, not twice per frame (a mod may draw the level more than once).

## Mods

**Flywheel, Create.** VMR 0618965 contains `compat/sable/flywheel`, but `VulkanFlywheelEngine.render` draws nothing (it only
writes instances to memory) and the backend is behind `vulkanmod.compat.sable.flywheel.experimental`. Flywheel's default backends
want `GL 3.3`, VMR reports an emulated `3.2` (`GlFunctionRegistry.REPORTED_GL_VERSION`), so `GlCompat.SUPPORTS_*` is
false and Flywheel falls back to `flywheel:off` by itself; Create then draws through vanilla BERs and entities (`SuperByteBuffer` into
`MultiBufferSource`). That is the fallback and it needs no mixin. The render type of blocks (`rendertype_solid`, ...) under
an entity or BER is by Iris `MOVING_BLOCK`, so `gbuffers_block`, in the shadow `shadow_cutout`/`shadow_water`, a BER
in the shadow `shadow_block`. **A backend of our own** (Colorwheel) is not being done: Flywheel generates shaders from the mods' GLSL materials (over
1000 lines per program), Colorwheel wants further programs from the pack and stands on `glsl-transformer` (AGPL). The cost of the fallback is
CPU in large scenes, not a missing image. To check: `flywheel:off` in the log.

**Other mods.** Everything that goes through `RenderType` and `BufferUploader` is mapped by the shader's name (`Families`) without a
namespace, an unknown shader as an entity or BER depending on blending. Direct GL is looked for in the bytecode, `scripts/glscan.py <jar>`
(nested jars too). Mekanism 10.7.19: no GL, `GlStateManager` only in `MekanismRenderType`, `GuiGraph`, `GuiInsetElement`
(state, not drawing), its own shaders `rendertype_flame`, `rendertype_mekasuit`, `rendertype_sps` got programs as
in Iris (`ShaderAccess`). Create 6.0.10: GL only in Flywheel (backend, off) and in one Ponder class. **No Iris API shim:**
Create, Mekanism and DH call nothing from the Iris API without the mod id `iris`, Flywheel and DH look for it through `ModList`.
DH gets its own accessor (`IIrisAccessor`), other mods nothing. What a mod draws with direct GL outside this path ends up
in VMR's target, which `final` overwrites: missing from the image, no crash.

**Distant Horizons.** DH 3.3.3 has its rendering behind `AbstractDhRenderApiDefinition` (`IDhTerrainRenderer`, `IVertexBufferWrapper`
and more). `DhDependencySetupMixin` gives it `VkDhDefinition` instead of OpenGL: buffers are VMR's `VertexBuffer` (GPU, VMA), the terrain
is drawn by `PackRenderer.distantHalf` with the programs `dh_terrain`, `dh_water`, `dh_shadow`, or by `DistantPlain` with no pack, the other passes (fog, SSAO, fade, TAA,
applying to MC) are empty, because the pack takes care of those. `DhMainMixin` adds `IIrisAccessor`, thanks to which DH
defers water, builds the shadow list without the frustum camera and draws differently without a pack; the id `iris` appears nowhere. Depth:
own `dhDepthTex0` and a copy `dhDepthTex1` before water, forward like everything else, the projection row from DH's planes.
Vertex of 16 B (`DistantVertex`), section corner through a dynamic offset of one UBO, one set per half, one index buffer.
`DISTANT_HORIZONS` is defined only with the bridge active. No fork and no run-time dependency: `libs/` is `compileOnly` only,
the mixins are applied by `OpalCompatMixinPlugin` only for the exact version 3.3.3 and without `-Dopalcompat.dh=false`. DH's clouds and beacons
are not drawn (`dh_generic`).

## Codex's work (branch `codex-work`, revision `39fd05e`)

The verbatim import is commit `bed96bd`, the fixes are separate. The zip had no `.git`, but it stood directly on `b65080c`, so the difference of the
trees is the whole work. The jar `opalcompat-0.1.0-alpha.jar` is byte-identical in all classes with a build of these
sources, only the line endings in `opalcompat.mixins.json` differ; that says nothing about whether it works.

**Accepted:** the `:jar` fix in `build.gradle`, `HandPass` (two hand phases by Iris), `MixedMesh` and `DrawIds`
(vertex identity), `ShadowFeatures`, graphics `shadowcomp` with its own flips (agreement with Iris verified),
`ShadowMaps`, `TextureImages`, `PackStorage`, `PackImage`, `ImageLayouts`, `StorageBuffer`, `applyViewport`,
deferred cleanup after an error (`fail`/`frameEnded`).

**Removed:** `VertexBufferMixin`. Injectors at HEAD run in the order of application: lower `order`, then lower priority.
A comment in it counted on the opposite, so it ran only after VMR's `VertexBufferM`, which always cancels. All dead code: neither sky nor
clouds ever reached the pack. It also kept a copy of every static VBO on the heap. Replaced by `VboMixin` on `VBO`.

**Fixed** (all statically, none of it had run):

- unset `renderStage` and `of_PassColour` (sun, moon, sky color), the directives `sun`, `moon`, `stars`, `sky`,
  `clouds`, `weather` were ignored;
- the entity overlay was bound to a white 1×1 and `texelFetch` read outside the image (black entities were a risk);
- hand depth in 0..0.125 instead of the 0.4375..0.5625 packs ask about (`depth < 0.56`);
- `depthtex2` was copied only after the hand; the substitute `UV0`, `UV1`, `UV2` were zeros (red overlay, darkness), now as in Iris;
- the block outline, cracks, world border and programs outside the plan (`gbuffers_beaconbeam`, `gbuffers_lightning`) ended up
  in VMR, so they vanished; polygon offset was ignored;
- the vanilla blob under an entity also went into the shadow map (now turned off like in Iris when a pack draws shadows);
- screen effects (fire, water) went through the hand program instead of after `final`;
- a global `ALL_COMMANDS` barrier at each descriptor write, so a GPU stall per draw;
- 112 B of constants per vertex with byte-wise zeroing (now one record per draw);
- `vkResetDescriptorPool` and rewriting the UBO on a second `renderLevel` in one frame;
- reading depth attached as an attachment (wrong layout), a 2D descriptor for `sampler3D`/`sampler1D`;
- `render type setup` inside an interrupted pass: under Fabulous it would start a VMR pass in the middle of ours (also in the original
  terrain code); all passes on one `MemoryStack` (64 kB, `OutOfMemoryError` with packs of dozens of passes);
- targets with `clear=false` read undefined data in the first frame (Iris does a full clear);
- `VK_IMAGE_USAGE_STORAGE_BIT` even for a format that can't do it; a missing texture or unsupported raw took down the whole pack
  (now a warning and black); `reload()` closed resources a frame in progress was still using; protection against
  `skipRendering` (minimizing would otherwise turn the pack off for good).

## Mixins

All verified against the bytecode (`javap` over `neoforge-21.1.228-merged.jar` and the VMR jar), including handler descriptors,
staticness, and that a name without a descriptor points to a single method. No mixin has `require = 0`; `defaultRequire: 1`
applies, so a wrong target brings the game down at startup.

| Mixin | Target | Remap |
|---|---|---|
| `LevelRendererMixin` | `renderLevel` (HEAD, before `renderSky`, before `RenderType.translucent()` in 2 branches, RETURN), `renderSky`, `renderClouds`, `renderSnowAndRain` | yes |
| `GameRendererMixin` | `render`, `renderItemInHand` (wrapper, `renderHandsWithItems`, `renderScreenEffect`) | yes |
| `GameRendererDepthFarMixin` | the call to `getDepthFar` in `getProjectionMatrix` | yes |
| `BufferUploaderMixin` | `_drawWithShader`, `draw` (order 1000, before VMR's `order = 2000`) | yes |
| `BufferBuilderMixin`, `MeshDataMixin` | `beginVertex`, `build`, a new field | yes |
| `EntityRenderDispatcherMixin` | `render`, `renderFlame`, static `renderShadow` | yes |
| `BlockEntityRenderDispatcherMixin`, `ItemRendererMixin`, `ItemInHandRendererMixin`, `ParticleEngineMixin` | `render`, `render`, `renderArmWithItem`, `render` (5 arguments) | yes |
| `GameRendererAccessor`, `LevelRendererAccessor`, `TextureAtlasAccessor`, `BiomesMixin` | `getFov`, `bobHurt`, `bobView`, `renderHand`, `renderEntity`, static `Biomes.register` | yes |
| `VboMixin`, `VboAccessor` | `VBO.drawWithShader(..., ShaderInstance)`, `VBO` fields | no |
| `VulkanImageMixin` | static `VulkanImage.transitionImageLayout(MemoryStack, VkCommandBuffer, VulkanImage, int)` | no |
| `VTextureSelectorMixin` | static `VTextureSelector.uploadSubTexture` (HEAD) | no |
| `RendererMixin`, `RendererAccessor` | `Renderer.endFrame`, `appliedDepthBiasUnits/Factor` | no |
| `WorldRendererMixin`, `WorldRendererAccessor`, `DrawBuffersAccessor`, `DrawParametersAccessor` | VMR's terrain | no |
| `CustomVertexFormatMixin`, `TerrainBufferBuilderMixin`, `DeviceManagerMixin` | format, builder, `createLogicalDevice` | no |
| `VulkanIdleMixin` | `Vulkan.waitIdle`, `Vulkan.cleanUp`: under `QueueLock` | no |
| `RenderSectionReadyMixin`, `ChunkStatusMapAccessor` | `RenderSection.createCompileTask`: a section is built where vanilla would build it (`ChunkReadiness`); VMR's per-column flags, for its log line | no |
| `DrawBuffersUploadMixin`, `DrawerAccessor` | `DrawBuffers.stageUpload`: the quads of each uploaded layer; VMR's 32-bit quad indices, which `WorldRendererMixin` and `PackRenderer` bind for terrain once a layer has more than 16384 quads (`TerrainIndices`) | no |
| `TaskDispatcherFlowMixin`, `SectionGraphUrgencyMixin`, `BuildTaskUrgencyMixin`, `ChunkUploadCountMixin` | finished chunk sections uploaded past the ceiling while they wait, urgent ones first, and more rebuilds scheduled a frame while hundreds wait: `TaskDispatcher.getMaxUploadsPerFrame`, `schedule`, `pollTask`, `scheduleSectionUpdate`, `updateSections`, `clearBatchQueue`, `isIdle`; `SectionGraph.schedulePendingRebuilds`; `BuildTask.<init>`; `ChunkFrameDiagnostics.recordUploads` | no |
| `SpriteContentsUseMixin`, `SpriteInterpolationUseMixin`, `TextureAtlasSpriteUseMixin`, `BakedQuadUseMixin`, `TextureAtlasCycleMixin`, `ModelBakeryUseMixin`, `BlockRendererUseMixin` | animated textures only once used: `SpriteContents.upload`, `InterpolationData.uploadInterpolatedFrame`, the sprite's coordinate getters, `BakedQuad.getVertices` and `getSprite`, `TextureAtlas.cycleAnimationFrames`, `ModelBakery.bakeModels`, VMR's `BlockRenderer.putQuadData` | no |
| `DhDependencySetupMixin`, `DhMainMixin`, `DhRenderTasksMixin`, `DhRenderListMixin`, `DhClientWorldMixin`, `DhStartMixin`, `DhClientApiMixin` | Distant Horizons, only where `DhSupport.ACTIVE`: `setRenderingApiBindings`, `initializeModCompat`, `runRenderThreadTasks()`, `queueRunningOnRenderThread`, `buildRenderList`, `unloadLevel`, `logIncompatibilityWarnings`, `renderLods` | no |

`@WrapMethod` and `@WrapOperation` need MixinExtras 0.4+, NeoForge 21.1.228 carries 0.5.3 and VMR uses them too.

The package `compat` has its own config with `required: false` and `@Pseudo`, a change in another mod must not bring it down: `OpalOnVulkanMixin` and
`VectorsOnVulkanMixin` only with VMR, `OpalImagesMixin`, `OpalImageMixin` and `FlywheelShaderPackMixin` only without it, `EveryCompatModulesMixin`, `DhChunkyMixin` and the three `Create*` mixins
whenever their target is in the game.

## Not verified at run time

What ran is in Results on Vulkan. Consciously left out or not run:

- `DhStartMixin`, `DistantPlain`, `LodAtlas` and the patched copy of DH: built, not run. The user's decision: they test it in the game themselves.
- `LodTransfer`, `LodDeviceBuffer`, `QueueLock` and `VulkanIdleMixin`: built, not run, for the same reason.
- The shader thread of `MeshPrograms` and `PackPipeline.readyPipeline`: built, not run, for the same reason.
- `AnimatedSprites` and its mixins, the new `PackSettingsScreen` and `ShaderPackScreen`: built, not run, for the same reason.
- `RenderSampler` and the longer `[pack]` line of `FrameStats`: built, not run, for the same reason.
- `ChunkFlow` and its four mixins: built, not run, for the same reason.
- `ChunkReadiness`, `RenderSectionReadyMixin`, `ChunkStatusMapAccessor` and the larger rebuild budget: built, not run, for the same reason.
- `TerrainIndices`, `DrawBuffersUploadMixin`, `DrawerAccessor`: built, not run, for the same reason.
- Opal on OpenGL with Create, Mekanism and DH: ran only with Opal and Vectors, one scene. `FlywheelShaderPackMixin` did not run in the game
  and Opal has no `dh_*` programs. The user's decision: Vulkan is enough.
- A run without VMR after merging into one mod: it last ran as two jars. Now it is verified only statically (`scripts/release.py` makes sure
  that the entry classes and the OpenGL part do not name VMR's classes). The user's decision: no tests on OpenGL.
- FPS measurements and comparison with Iris: the user's decision. The one number in passing: pack and all mods with DH, 854x480, 80 FPS, 1% low 36.
- The VMR jar from the user's instance differs from the one on GitHub (5e4b09e8, 20,537,547 B against 61a1c215, 20,537,626 B), same version. The tests ran
  on the one from GitHub.
- The Stellarity set: ran without a shader pack, with G1 and the VMR jar from GitHub (it differs from the user's jar in a single GUI class,
  `DiagnosticsPageContent$DiagnosticsList`). A pack on this set, ZGC with FerriteCore and longer play are not verified. Nor is whether DH really gets Chunky's
  events after startup: only joining a world without a crash is verified.
- A large scene in Create, a formed multiblock in Mekanism (the dynamic tank stands, forming not verified), world border, cracks when mining,
  the fire screen effect, glow.

There are no `TODO-VERIFY` marks in the code. Hand and entity shadows are verified against `refs/Iris` (`pathways/HandRenderer`,
`shadows/ShadowRenderer`): Iris does the second drawing of entities the same way, so a mod that minds it has the same problem under Iris.
Fixed during that check: entity shadows sort by type (fewer batches), take tick delta like vanilla (`isEntityFrozen`, BER
`false`), `shadowPlayer` also takes the vehicle and passengers; the hand asks about the sleep of the camera entity, not the player.

## Known problems and limitations

- VMR 0.3.5's validation baseline, not caused by us: `VUID-vkQueueSubmit-pSignalSemaphores-00067`,
  `VUID-vkDestroyDevice-device-05137`. Further things from VMR and mods are in `BLOCKERS.md`.
- The warning `Undefined-Value-ShaderOutputNotConsumed`: Vitrail's `ofCoverage` output has no attachment.
  `Undefined-Value-OutputNotWritten` (1 to 2 with BSL and Bliss) is not investigated, it does not show in the image.
- At night with DH part of the sky has a purple haze with a sharper edge. It moves with the world, not with the screen, so it is not from overlapping targets;
  whether it belongs to the pack is not verified. BSL has a white horizon, the other packs do not.
- Only sections compiled by the camera's graph go into the shadow, VMR has no others.
- `centerDepth` reads depth directly, without smoothing. `normals`/`specular` are neutral for now, PBR atlases are not loaded.
- Raw textures: packed, BGR, integer and rectangle read black for now. VBOs with `transientGeometry` are drawn by VMR.
- A shadow program reading the same map it writes gets `shadowtex1`.
- `Vk.storageUsage`: a pack that binds as `colorimg` a target in a format without storage support fails when writing the descriptor.

## What is left

1. What is listed in "Not verified at run time".
2. Possibly: SSAO and the fade of the near chunks for far terrain with no pack, `dh_generic`, a Flywheel backend of our own.

## Reference: Iris-Vulkan-Port

`refs/Iris-Vulkan-Port`, commit `47babb4`. **LGPL-3.0**, but it depends on `glsl-transformer` (**AGPL-3.0**),
so nothing of the transformation part is taken. It targets Fabric and its own modified VulkanMod 0.5.9 with MRT.
- **Depth/stencil:** `depthtex0` is the depth of the main target shared with vanilla; we have our own D32,
  because VMR draws with a flip and our targets do not.
- **Framebuffers:** their VulkanMod can do MRT and draws terrain itself; we have our own passes and draw loop with no fork.
- **SPIR-V and uniforms:** they assign bindings in the text and have a manual UBO; we rewrite decorations by name.
- **Terrain attributes:** with them constants and a normal from `dFdx/dFdy`; with us real attributes. Consciously not taken.

## Taken code

- `src/main/java/dev/vitrail/**`: group a from Vitrail (commit `7a3cdcb`) + boundary `PackValues`,
  `ViewMatrices`, `CapturedProjection`, `ShadowAmortisation`, `ShadowCullPlan`, `BlockStateIds`,
  `PackNameIds`, `BiomeClassifier`. See `scripts/take_from_vitrail.py`. Java 21 (`_` → `unusedN`), API 1.21.1.
- Replacements of our own for Vitrail's files: `glsl/GameTransforms`, `glsl/SodiumVertex`, `render/FrameState`
  (port to 1.21.1), `FaceShading`, `HandDraw`, `ShadowTargets`, `PackImages`.
- Changes in Vitrail after taking it: `PackProgram.loadShadowComposites`, `ShaderProperties.viewportScales`, a note
  in `TargetPlan` (Codex, marked `OpalCompat:`).
- After the behaviour of Iris 1.21.1 (LGPL-3.0), with no copied code, see [NOTICE](NOTICE): `HandPass`, `ShadowFeatures`, `DrawIds`,
  `ItemInHandRendererMixin`, `TextureImages`, `Families`, the flips in `ShadowMaps`.
- License LGPL-3.0-only, `NOTICE-vitrail` is unchanged.
