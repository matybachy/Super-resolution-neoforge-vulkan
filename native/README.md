# Private DLSS native bridge

This directory is documentation only. The NVIDIA NGX SDK is proprietary and
must be obtained separately under NVIDIA's license.

Implement the JNI methods declared by
`dev.matybachy.superresolution.DlssNativeBridge` in a private native library
named `superresolution_dlss`. The implementation must:

1. query NGX Vulkan instance and device extension requirements before the
   Vulkan instance/device are created;
2. initialize and shut down NGX without assuming the upstream project's
   application/project identifier is authorized for this project;
3. evaluate only with valid color, depth, camera-motion-vector, and output
   image views in layouts accepted by the selected SDK;
4. return a non-zero status on every failure so Java keeps native-resolution
   rendering active.

Do not commit the SDK, headers, libraries, DLLs, or generated binaries.
