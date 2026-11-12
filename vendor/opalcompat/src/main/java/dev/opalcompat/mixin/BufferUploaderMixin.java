package dev.opalcompat.mixin;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import dev.opalcompat.render.PackRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Immediate meshes. Both run ahead of VulkanMod, whose own hook on {@code draw} carries {@code order =
 * 2000}: injectors at HEAD run in the order they are applied, lower {@code order} first, and among equal
 * orders the lower priority first.
 */
@Mixin(BufferUploader.class)
public abstract class BufferUploaderMixin {

	@Inject(method = "_drawWithShader", at = @At("HEAD"), cancellable = true)
	private static void opalcompat$drawWithShader(MeshData mesh, CallbackInfo ci) {
		if (PackRenderer.drawImmediate(mesh, false)) {
			mesh.close();
			ci.cancel();
		}
	}

	@Inject(method = "draw", at = @At("HEAD"), cancellable = true)
	private static void opalcompat$draw(MeshData mesh, CallbackInfo ci) {
		if (PackRenderer.drawImmediate(mesh, true)) {
			mesh.close();
			ci.cancel();
		}
	}
}
