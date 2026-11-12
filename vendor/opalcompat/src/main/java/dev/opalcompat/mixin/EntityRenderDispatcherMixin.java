package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.opalcompat.render.PackRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@WrapMethod(method = "render")
	private <E extends Entity> void opalcompat$entityIds(E entity, double x, double y, double z, float yaw,
			float tick, PoseStack poses, MultiBufferSource buffers, int light, Operation<Void> original) {
		PackRenderer.pushEntity(entity);
		try {
			original.call(entity, x, y, z, yaw, tick, poses, buffers, light);
		} finally {
			PackRenderer.popDrawIds();
		}
	}

	/** A pack's name for the fire on a burning mob ({@code entity_flame}), as Iris gives it. */
	@WrapMethod(method = "renderFlame")
	private void opalcompat$flameIds(PoseStack poses, MultiBufferSource buffers, Entity entity, Quaternionf rotation,
			Operation<Void> original) {
		PackRenderer.pushFlame();
		try {
			original.call(poses, buffers, entity, rotation);
		} finally {
			PackRenderer.popDrawIds();
		}
	}

	/** Without this the blob would also go into the shadow map, as one more caster flat on the ground. */
	@Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
	private static void opalcompat$noBlobUnderShadowMap(CallbackInfo ci) {
		if (PackRenderer.drawsShadowMap()) ci.cancel();
	}
}
