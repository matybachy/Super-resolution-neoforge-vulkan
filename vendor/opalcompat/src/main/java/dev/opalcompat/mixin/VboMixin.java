package dev.opalcompat.mixin;

import dev.opalcompat.render.PackRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.render.VBO;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sky's and the clouds' buffers, and any other a mod draws in the level. Hooked here and not on the
 * game's {@code VertexBuffer}: VulkanMod's {@code VertexBufferM} cancels {@code upload},
 * {@code drawWithShader} and {@code close} at HEAD, and a later injector at HEAD never runs.
 */
@Mixin(value = VBO.class, remap = false)
public abstract class VboMixin {

	@Inject(method = "drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V",
			at = @At("HEAD"), cancellable = true)
	private void opalcompat$draw(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, CallbackInfo ci) {
		if (PackRenderer.drawStored((VBO) (Object) this, modelView, projection, shader)) ci.cancel();
	}
}
