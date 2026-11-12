# BLOCKERS.md

What can't be fixed in this repository, because it takes a change to another mod or to how that mod is built.

## `create-dyn-light` (Create Dynamic Light 2.0.1) does not load

The game stops with `Mod createdynlight requires sodiumdynamiclights 1.0.0 or above`. The mod declares that dependency as
`mandatory=false`, but `neoforge.mods.toml` for NeoForge 1.21 reads `type`, and without it the dependency is required. It can't
be supplied: according to Modrinth `sodiumdynamiclights` requires Sodium, Sodium will not run with VMR, and Opal turns itself off
next to it. The addon also builds on the LambDynLights API, so it would do nothing without that anyway. **Remove it from the
instance.** Posing as `sodiumdynamiclights` is out of the question, for the same reason as the id `iris`.

## `createbetterfps` does not load

The game stops with `Mod createbetterfps requires sodium 0.6.9 or above`, and Sodium will not run with VMR. **Remove it from the instance.**

## A large mod set without FerriteCore does not fit in 15 GB

The set this was measured on has 3.3 million `BlockState`s (Every Compat adds 55,836 blocks). Without FerriteCore 8.9 GB is live
right at the start of the resource load and 15.4 GB three minutes later (`jcmd GC.class_histogram`: 33 million `ImmutableMapEntry`,
15 million multipart model conditions). The result is `OutOfMemoryError` in `Rendering overlay` (G1), or with ZGC a game hanging on
a full heap. Opal Compat can't change that, and it is not what takes that memory. **Put `ferritecore-7.0.3-neoforge.jar` in `mods`.**
With it 4.5 GB and 8.6 GB are live at the same two points.

It is still tight: shortly after joining a new world 14.2 GB of 15.36 GB is live, while EMI bakes its recipes (8.3 million
`ItemEmiStack`). **Drop `-XX:+UseZGC -XX:+ZGenerational`:** ZGC has no compressed pointers, so the same data takes more room. ZGC
with FerriteCore was not tried, G1 was. With RAM to spare, raise the heap too.

## Bugs of other mods that Opal Compat works around

The right fix belongs with them. Once they make it, the mixin is unnecessary but does no harm.

- **VMR:** `VTextureSelector.uploadSubTexture` throws an NPE on an upload to a texture with no image, `GlTexture.texSubImage2D` skips it. `VTextureSelectorMixin`.
- **Every Compat 2.11.56:** `forAllModules` walks the modules without the lock `addModule` holds. `EveryCompatModulesMixin`.
- **VMR:** a section is built only once its column and all eight around it have both `ChunkStatusMap` flags, data and light; a column whose flags are lost, whose light never comes or whose diagonal neighbour is missing keeps its sections unbuilt for as long as the chunk stays loaded, so its blocks are there and not drawn, with a shader pack or without one. Vanilla asks only for the four chunks beside it. `ChunkReadiness`, `RenderSectionReadyMixin`.
- **VMR:** terrain is drawn through one buffer of 16-bit quad indices for 65536 vertices, and nothing grows or replaces it for a section layer with more than 16384 quads, so the blocks meshed after the first 16384 quads of a dense section are not drawn (with `uniqueOpaqueLayer` every opaque block of a section is in one layer). `TerrainIndices`, `DrawBuffersUploadMixin`, `DrawerAccessor`, `WorldRendererMixin`. The translucent layer's own indices are bound as 16-bit too, which is left as it is.
- **VMR:** after joining a world, one section is scheduled a frame while hundreds wait (the time budget is 0.5 to 1 ms and a first copy of a section's blocks takes about 2 ms). `SectionGraphUrgencyMixin`.
- **Ponder 1.0.82 and Create 6.0.10:** `StitchedSprite.ALL`, `SpriteShifter.ENTRY_CACHE` and `CTSpriteShifter.ENTRY_CACHE` are plain `HashMap`s filled from mod constructors, which NeoForge runs on several threads. `CreateStitchedSpriteMixin`, `CreateSpriteShifterMixin`, `CreateCtSpriteShifterMixin`.
- **Distant Horizons 3.3.3 with Chunky 1.4.23:** `ChunkyAccessor` calls `ChunkyProvider.get()` before Chunky exists. `DhChunkyMixin`.
- **VMR with Distant Horizons:** both draw from the head of `LevelRenderer.renderSectionLayer` and VMR comes first, so DH's deferred water lands after the world's, and with no pack its whole far terrain would land on top of the near chunks. `DhSupport.waterBeforeWorld`, `VkDhBridge.beforeWorldLayer`.
- **Distant Horizons 3.3.3 at its start:** `logIncompatibilityWarnings` takes any `IIrisAccessor` for Iris, crashes the game on the `BLAZE_3D` engine and forces `OPEN_GL` on `AUTO`. `DhStartMixin`, for this mod's adapter alone.
- **Distant Horizons 3.3.3:** `RenderThreadTaskHandler` allows its tasks half a frame at the frame limit, 1.5 ms at an unlimited 260, while its loader threads wait for them holding the one pool of threads DH has. Every section's upload waits for the next frame, so far terrain arrives at the speed of the frame rate. `DhRenderTasksMixin`, `LodUploads`, `LodTransfer`.
- **Distant Horizons 3.3.3 on a server:** the level key arrives just after the join and two threads unload the same level; the second throws `Cannot invoke "DhClientLevel.close()"` in `DhClientWorld.unloadLevel`. `DhClientWorldMixin`.

## Fabulous graphics on VMR

VMR does not support it: `GraphicsModeCompatibility.supportedModes()` offers only Fast and Fancy and the log says
`fabulous graphics mode not supported, forcing fancy`. When it is forced directly (the vanilla screen through Shift+P,
the test command `graphics fabulous`), `VUID-vkCmdDrawIndexed-imageLayout-00344`, `VUID-VkImageMemoryBarrier-oldLayout-01197`,
`VUID-vkCmdDraw-None-09600` and `VUID-vkCmdDrawIndexed-None-08608` appear. Plain VMR without a pack gives the same messages.

## `VUID-vkResetCommandBuffer-commandBuffer-00045`

A rare message (2 of about 20 runs), one of them shortly after startup. Only VMR calls `vkResetCommandBuffer`
(`Renderer.java:265`, `CommandPool.java:150`, `Vulkan.java:444`), Opal Compat does not. No visible effect.

## VMR's `Matrix4fM` is not applied on NeoForge

The mixin into `org.joml.Matrix4f` is meant to force depth 0..1, but JOML is not loaded through the transformer: the mixin export
(`-Dmixin.debug.export=true`) has nothing under `org/joml`. The matrices are in OpenGL form [-1,1] and VMR clips half of their depth
range (near comes out as 0.1 instead of 0.05). Opal Compat now counts on that. If VMR fixes this, `LevelCapture.glToReversed`
has to change.
