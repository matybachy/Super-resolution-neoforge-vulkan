package dev.opalcompat.mixin;

import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The polygon offset the game has asked for, which a pack pipeline applies as depth bias, and VulkanMod's bound pipeline. */
@Mixin(value = Renderer.class, remap = false)
public interface RendererAccessor {

	@Accessor("appliedDepthBiasUnits")
	float opalcompat$depthBiasUnits();

	@Accessor("appliedDepthBiasFactor")
	float opalcompat$depthBiasFactor();

	/** Which pipeline VulkanMod takes to be bound; it binds nothing while the one it asks for is this one. */
	@Accessor("boundPipelineHandle")
	void opalcompat$boundPipelineHandle(long handle);
}
