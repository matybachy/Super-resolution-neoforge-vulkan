package dev.opalcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("renderHand") boolean opalcompat$renderHand();
	@Invoker("getFov") double opalcompat$getFov(Camera camera, float tick, boolean useSetting);
	@Invoker("bobHurt") void opalcompat$bobHurt(PoseStack poses, float tick);
	@Invoker("bobView") void opalcompat$bobView(PoseStack poses, float tick);
}
