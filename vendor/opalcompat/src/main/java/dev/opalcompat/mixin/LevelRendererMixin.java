package dev.opalcompat.mixin;

import dev.opalcompat.render.PackRenderer;
import dev.opalcompat.render.Families;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The level's matrices, and the start of the pack's frame. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void opalcompat$beginLevel(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
			GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
			CallbackInfo ci) {
		PackRenderer.beginLevel(frustumMatrix, projectionMatrix);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V"))
	private void opalcompat$beforeSky(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
			GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
			CallbackInfo ci) {
		// The game has just set the shader the sky's dome is drawn with, renderSky takes it from RenderSystem, and the pack's
		// shadow pass runs here. Its last render type would stay behind: the glint, with an enchanted item in the player's hand.
		ShaderInstance sky = RenderSystem.getShader();
		PackRenderer.beforeSky(camera);
		RenderSystem.setShader(() -> sky);
	}

	@WrapMethod(method = "renderSky")
	private void opalcompat$skyPhase(Matrix4f view, Matrix4f projection, float tick, Camera camera,
			boolean foggy, Runnable fog, Operation<Void> original) {
		PackRenderer.pushPhase(Families.Phase.SKY);
		try {
			original.call(view, projection, tick, camera, foggy, fog);
		} finally {
			PackRenderer.popPhase();
		}
	}

	@WrapMethod(method = "renderClouds")
	private void opalcompat$cloudPhase(PoseStack poses, Matrix4f view, Matrix4f projection, float tick,
			double x, double y, double z, Operation<Void> original) {
		PackRenderer.pushPhase(Families.Phase.CLOUDS);
		try {
			original.call(poses, view, projection, tick, x, y, z);
		} finally {
			PackRenderer.popPhase();
		}
	}

	@WrapMethod(method = "renderSnowAndRain")
	private void opalcompat$weatherPhase(LightTexture light, float tick, double x, double y, double z,
			Operation<Void> original) {
		PackRenderer.pushPhase(Families.Phase.WEATHER);
		try {
			original.call(light, tick, x, y, z);
		} finally {
			PackRenderer.popPhase();
		}
	}

	/** Both branches of the translucent stage ask for the render type; it takes the first only. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/RenderType;translucent()Lnet/minecraft/client/renderer/RenderType;"))
	private void opalcompat$solidHand(DeltaTracker tick, boolean outline, Camera camera, GameRenderer game,
			LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
		PackRenderer.renderSolidHand(camera, game, tick.getGameTimeDeltaPartialTick(true));
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void opalcompat$translucentHand(DeltaTracker tick, boolean outline, Camera camera, GameRenderer game,
			LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
		PackRenderer.renderTranslucentHand(camera, game, tick.getGameTimeDeltaPartialTick(true));
	}
}
