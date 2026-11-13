package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.matybachy.srvmr.render.DlssFrame;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where a frame of the world begins, the projection everything of it is drawn with, and the moment the game clears the
 * depth to draw the hand. DLSS needs the picture moved by a fraction of a pixel each frame, the same fraction told to it,
 * and the depth of the world, which that clear throws away. The hand is drawn with the jittered projection too.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererJitterMixin {

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void srvmr$beginWorld(DeltaTracker tracker, CallbackInfo ci) {
		DlssFrame.beginWorld();
	}

	@ModifyReturnValue(method = "getProjectionMatrix", at = @At("RETURN"))
	private Matrix4f srvmr$jitter(Matrix4f projection) {
		return DlssFrame.jitter(projection);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
	private void srvmr$beforeHandClear(DeltaTracker tracker, CallbackInfo ci) {
		DlssFrame.beforeHandClear();
	}
}
