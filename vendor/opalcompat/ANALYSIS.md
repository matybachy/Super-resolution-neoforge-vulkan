# Phase 0: analysis (restored, shortened)

Revisions: Vitrail `7a3cdcb` (LGPL-3.0, MC 26.x), VulkanMod Reforged `0618965` = 0.3.5-ALPHA+1.21.1 (LGPL-3.0),
Iris branch `1.21.1` (LGPL-3.0). The clones are in `refs/`.

## Vitrail `common/src/main/java` (403 files, 102,831 lines)

| Group | Files | Lines |
|---|---:|---:|
| a) independent of the game version (`glsl`, `pack.*`, `uniform.*`) | 227 | 47,736 |
| b) tied to 26.x / Sodium (`render`, `mixin`, `sodium`) | 123 | 44,202 |
| c) boundary (`screen`, ids, registries) | 53 | 11,296 |

Group b serves only as a specification of behaviour.

## VulkanMod Reforged: map

- **Compilation:** `SPIRVUtils`, `lwjgl-shaderc` 3.3.3, no reflection; bindings from JSON or its own parser.
- **Pipeline:** one descriptor set, push constants for the vertex stage only, 1 color blend attachment, no
  geometry stage and no compute.
- **Framebuffers:** `colorAttachments <= 1`; `MainPass` is an interface; `Renderer.temporaryRenderTarget()`
  interrupts and restores a pass with its state.
- **Terrain:** 20 B `COMPRESSED_TERRAIN` without a normal or block ID, hooks `setShaderGetter`,
  `TerrainBufferBuilder.setBlockAttributes`, section offset in `gl_InstanceIndex`, `ChunkOffset` push constant.
- **Depth:** forward 0..1 (`Matrix4fM`). **Device:** minimum features.
- **VMR mixins:** `renderLevel` only injects, `@Overwrite` mostly in the GL facade.

## Risks

MRT (own passes), device features (mixin), terrain format (extended at startup), shadow pass (VMR has only
the camera's section graph; own selection from `SectionGrid`), shaderc version, VMR's alpha state (exact version),
GL emulation (our targets are not GL textures), license (nothing from Sodium, nothing from `glsl-transformer`).
