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

## DLSS integration status

The repository now contains an isolated DLSS integration core in
`src/main/java/dev/matybachy/superresolution`. It provides a DLSS-only
upscaler lifecycle, resize and history reset handling, explicit
native-resolution fallback, and the temporal inputs required by NGX.

`CameraMotionVectorPass` tracks previous/current view-projection matrices for
the camera-only velocity path at the Opal Compat renderer seam. Entity and
animated-object motion is not represented yet and may ghost during temporal
accumulation.

The native bridge is intentionally only a JNI declaration. NVIDIA NGX headers,
libraries, project identifiers, and DLLs are not redistributed. The renderer
integration must call this core after the world pass and before
native-resolution GUI rendering, providing actual Vulkan image views and a
recording command buffer.

`VulkanExtensionRegistrar` targets the added VMR
`VulkanExtensionHooks` seam. NGX-discovered instance/device requirements must
be registered before VMR initializes its Vulkan class; otherwise the core
continues with the documented native-resolution fallback.

## Native SDK setup

Build a private native library named `superresolution_dlss` against the NVIDIA
NGX SDK and place it on the Java library path. The bridge must initialize NGX
with the application's Vulkan instance/device, query feature requirements
before device creation, and translate the SDK resource/layout requirements
into the VulkanMod command buffer.

Do not copy NVIDIA SDK binaries or the upstream project's identifier into this
repository. DLSS remains disabled, with a warning and native-resolution
rendering, until the bridge and all required images are valid.
