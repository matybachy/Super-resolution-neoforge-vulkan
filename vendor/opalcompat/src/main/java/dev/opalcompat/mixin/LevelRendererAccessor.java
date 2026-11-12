package dev.opalcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	@Invoker("renderEntity")
	void opalcompat$renderEntity(Entity entity, double x, double y, double z, float tick,
			PoseStack poses, MultiBufferSource buffers);
}
