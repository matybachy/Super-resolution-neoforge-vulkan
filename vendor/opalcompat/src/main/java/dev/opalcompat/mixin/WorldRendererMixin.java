package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.opalcompat.dh.DhSupport;
import dev.opalcompat.dh.VkDhBridge;
import dev.opalcompat.render.PackRenderer;
import dev.opalcompat.terrain.TerrainIndices;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The pack's terrain programs draw the chunk layers into its own targets instead of VulkanMod's shader. Where VulkanMod draws
 * them itself, it does so with indices that reach every quad of a section: {@link TerrainIndices}.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {

	@Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
	private void opalcompat$terrain(RenderType renderType, double camX, double camY, double camZ, Matrix4f modelView,
			Matrix4f projection, CallbackInfo ci) {
		if (DhSupport.ACTIVE) VkDhBridge.beforeWorldLayer(renderType, modelView, projection);
		if (PackRenderer.drawTerrain(renderType, camX, camY, camZ)) {
			ci.cancel();
		}
	}

	@WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/vulkan/memory/AutoIndexBuffer;getIndexBuffer()Lnet/vulkanmod/vulkan/memory/IndexBuffer;"))
	private IndexBuffer opalcompat$indicesForEveryQuad(AutoIndexBuffer shortQuads, Operation<IndexBuffer> original) {
		return TerrainIndices.buffer();
	}
}
