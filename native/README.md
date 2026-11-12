# Private DLSS native bridge

The native bridge in this directory requires a private NVIDIA NGX SDK checkout.
The SDK is proprietary and must be obtained separately under NVIDIA's license.

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

On Windows, run `build-windows.ps1 -DlssSdkDir C:\dev\DLSS` from this
directory. The script configures and builds the bridge, then reports the DLL
directories that must be placed on `PATH` at runtime.

To launch a Java-based Minecraft profile with the bridge, put its complete
argument list (one argument per line) in a file and run
`run-with-dlss.ps1 -ProjectId <authorized-id> -JavaArgumentsFile <path>`.
The wrapper sets `SUPERRESOLUTION_NGX_PROJECT_ID`, prepends the bridge and NGX
runtime directories to `PATH`, and never stores the project ID in the
repository.
