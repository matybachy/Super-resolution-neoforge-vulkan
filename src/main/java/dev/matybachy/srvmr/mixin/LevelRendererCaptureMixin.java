package dev.matybachy.srvmr.mixin;

import dev.matybachy.srvmr.render.DlssFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The camera, the entities and the two matrices the world of this frame is drawn with, for the motion vectors. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCaptureMixin {

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void srvmr$capture(DeltaTracker tracker, boolean blockOutline, Camera camera, GameRenderer renderer,
			LightTexture light, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		DlssFrame.capture(modelView, projection, camera, tracker);
	}
}
