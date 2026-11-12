package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.opalcompat.render.PackRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	@WrapMethod(method = "render")
	private <E extends BlockEntity> void opalcompat$blockEntityIds(E entity, float tick, PoseStack poses,
			MultiBufferSource buffers, Operation<Void> original) {
		PackRenderer.pushBlockEntity(entity);
		try {
			original.call(entity, tick, poses, buffers);
		} finally {
			PackRenderer.popDrawIds();
		}
	}
}
