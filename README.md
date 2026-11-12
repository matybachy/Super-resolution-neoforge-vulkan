# Super-resolution-neoforge-vulkan

DLSS-only Super Resolution integration for Minecraft 1.21.1 NeoForge on VulkanMod Reforged.

## Source components

This repository tracks the renderer and Vulkan shader-pack compatibility source as submodules:

- `vmr`: VulkanMod Reforged, Minecraft 1.21.1 / NeoForge
- `opalcompat`: Opal Compat, including the Vitrail Vulkan shader-pack path

Initialize them with:

```bash
git submodule update --init --recursive
```

LibLibLib, Vectors, and Opal Shaders remain runtime dependencies and are not redistributed here.

## Current status

Phase 0 reconnaissance is complete. DLSS implementation is pending the Phase 1 integration work. The integration must remain native-render fallback when NGX, required Vulkan extensions, NVIDIA hardware/driver support, or per-pixel motion vectors are unavailable.
