package dev.opalcompat.mixin;

import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** VulkanMod Reforged's 32-bit quad indices, which its immediate draws use past 65536 vertices and terrain needs too: {@code TerrainIndices}. */
@Mixin(value = Drawer.class, remap = false)
public interface DrawerAccessor {

	@Accessor("quadsIntIndexBuffer")
	AutoIndexBuffer opalcompat$quadsIntIndexBuffer();
}
