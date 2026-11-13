# Native bridge

`src/srvmr_ngx.cpp` is a small JNI library between the mod (`dev.matybachy.srvmr.ngx.NgxBridge`) and NVIDIA NGX. It owns no Vulkan objects: the instance, the device,
the command buffer and the images are VulkanMod Reforged's, and only their handles are passed on.

It is linked statically against `nvsdk_ngx_s.lib` from the DLSS SDK and the static Visual C++ runtime, so the DLL depends on nothing but Windows itself.

```
native\build-windows.cmd C:\path\to\DLSS C:\path\to\Vulkan-Headers "C:\Program Files\Zulu\zulu-21"
```

writes `native\build\srvmr_ngx.dll`. The script finds Visual Studio's C++ tools through `vswhere`, then runs CMake with the NMake generator.

What it exports: `deviceExtensions` (the device extensions NGX lists for DLSS on this GPU, before the device exists), `init` (NGX on VulkanMod Reforged's device,
with this project's own NGX project id and engine type `CUSTOM`), `availability`, `renderSizes` (DLSS's optimal settings), `create`, `evaluate`, `release`,
`shutdown`, `lastResult`.

The built DLL contains NVIDIA's code and is never committed (`.gitignore`).
