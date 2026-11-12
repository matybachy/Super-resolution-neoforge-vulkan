package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.opalcompat.terrain.ChunkReadiness;
import net.vulkanmod.render.chunk.ChunkStatusMap;
import net.vulkanmod.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A section is built where vanilla would build it, not only where VulkanMod Reforged's map says so: {@link ChunkReadiness}. */
@Mixin(value = RenderSection.class, remap = false)
public abstract class RenderSectionReadyMixin {

	@WrapOperation(method = "createCompileTask", at = @At(value = "INVOKE",
			target = "Lnet/vulkanmod/render/chunk/ChunkStatusMap;chunkRenderReady(II)Z"))
	private boolean opalcompat$vanillaReady(ChunkStatusMap status, int chunkX, int chunkZ, Operation<Boolean> original) {
		return ChunkReadiness.ready(chunkX, chunkZ, original.call(status, chunkX, chunkZ));
	}
}
