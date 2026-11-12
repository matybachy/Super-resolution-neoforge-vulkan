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
The upstream Super Resolution source is not bundled; this repository contains
only the DLSS-specific port and retains its GPL-3.0-or-later license in
`LICENSE-superresolution`.

## DLSS integration status

The repository now contains an isolated DLSS integration core in
`src/main/java/dev/matybachy/superresolution`. It provides a DLSS-only
upscaler lifecycle, resize and history reset handling, explicit
native-resolution fallback, and the temporal inputs required by NGX.

`CameraMotionVectorPass` tracks previous/current view-projection matrices for
the camera-only velocity path at the Opal Compat renderer seam. Entity and
animated-object motion is not represented yet and may ghost during temporal
accumulation.

When the DLSS JVM property is enabled, VMR registers the NGX Vulkan device
extensions before device creation, Opal applies a Halton camera jitter to the
projection, and the same jitter is passed to NGX.

The native bridge is implemented in `native/src/superresolution_dlss.cpp`.
NVIDIA NGX headers, libraries, project identifiers, and DLLs are not
redistributed. The renderer integration calls this core after the world pass,
writes camera-only motion vectors from the scaled depth buffer, evaluates DLSS
into a dedicated display-resolution target, and presents that target before
native-resolution GUI rendering.

`VulkanExtensionRegistrar` targets the added VMR
`VulkanExtensionHooks` seam. NGX-discovered instance/device requirements must
be registered before VMR initializes its Vulkan class; otherwise the core
continues with the documented native-resolution fallback.

## Native SDK setup

Build a private native library named `superresolution_dlss` against the NVIDIA
NGX SDK and place it on the Java library path. With the SDK checkout at
`C:\dev\DLSS`, configure the native build with:

```powershell
cmake -S native -B native/build -DDLSS_SDK_DIR=C:/dev/DLSS
cmake --build native/build --config Release
```

The CMake file reads headers from `C:\dev\DLSS\include` and links the Windows
x64 import library from `C:\dev\DLSS\lib\Windows_x86_64\x64`. It does not copy
those files into this repository. At runtime, keep the matching NVIDIA NGX
DLLs from `C:\dev\DLSS\lib\Windows_x86_64\rel` on the process `PATH` (or
alongside the private bridge DLL), and put the built `superresolution_dlss.dll`
on the Java library path. The bridge must initialize NGX
with the application's Vulkan instance/device, query feature requirements
before device creation, and translate the SDK resource/layout requirements
into the VulkanMod command buffer.

Set `SUPERRESOLUTION_NGX_PROJECT_ID` to a project identifier authorized for
this application. Do not reuse the upstream project's identifier. Do not copy
NVIDIA SDK binaries or the upstream project's identifier into this repository.
DLSS remains disabled, with a warning and native-resolution rendering, until
the bridge and all required images are valid.

For Windows setup, `native/build-windows.ps1` builds the bridge and
`native/run-with-dlss.ps1` can launch a Java profile with the bridge and NGX
runtime directories on `PATH`; it requires the authorized project ID as a
runtime parameter.

Enable the runtime bootstrap with the JVM property
`-Dsuperresolution.dlss=true`; quality can be selected with
`-Dsuperresolution.dlss.quality=QUALITY|BALANCED|PERFORMANCE|ULTRA_PERFORMANCE`.
When using the patched VMR settings screen, the same enable, quality, and
sharpness values are exposed under the Super Resolution page and written back
to these properties. The settings page reinitializes the live resolver when
applied.
The same page exposes manual pre-exposure and exposure-scale values because
the inspected Opal API does not publish an automatic scene-exposure value.
