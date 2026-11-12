package dev.opalcompat.mixin;

import dev.opalcompat.render.PackRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * VulkanMod Reforged puts the far plane at infinity, which a pack cannot live with. JOML builds that matrix with an epsilon, so
 * a depth of one, the sky, lies past infinity: {@code gbufferProjectionInverse} gives it a negative w and the position comes out
 * behind the camera. Complementary Unbound then looked for its clouds under the camera and found none. While a pack draws, the
 * plane is where the game itself puts it, as under Iris; every draw of the frame gets this one matrix, so all of it stays in one
 * depth.
 * <p>
 * At the call and not in {@code getDepthFar}, whose injections run in the order they were applied: VulkanMod's returns from the
 * method before anything behind it is reached, at any priority of this mixin.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererDepthFarMixin {

	@Shadow
	private float renderDistance;

	@Redirect(method = "getProjectionMatrix", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;getDepthFar()F"))
	private float opalcompat$finiteFar(GameRenderer self) {
		return PackRenderer.packDraws() ? this.renderDistance * 4.0F : self.getDepthFar();
	}
}
