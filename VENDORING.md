# Vendored sources

Run the **Vendor Vulkan stack sources** workflow from the Actions tab to download and commit:

- VulkanMod Reforged from `TrulyRin/VulkanMod-Reforged`, branch `main`
- Opal Compat from `matybachy/opal-shaders-compat`, branch `claude-review`

The vendored trees retain their upstream license and notice files. LibLibLib, Vectors, and Opal Shaders are runtime dependencies and are not redistributed by this workflow.
The excluded upstream Super Resolution algorithms are intentionally not
vendored; only the retained GPL license text is kept at the repository root.
