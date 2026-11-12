package dev.opalcompat.mixin;

import dev.opalcompat.render.LevelCapture;
import dev.opalcompat.render.PackRenderer;
import dev.opalcompat.render.Families;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.joml.Matrix4f;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The pack's full-screen chain runs once the level (and the hand) is drawn, before the GUI. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			shift = At.Shift.AFTER))
	private void opalcompat$afterLevel(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		PackRenderer.afterLevel();
	}

	/** The first of the two in {@code renderLevel}, which the bob and the nausea are then multiplied onto. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lnet/minecraft/client/renderer/GameRenderer;getProjectionMatrix(D)Lorg/joml/Matrix4f;"))
	private Matrix4f opalcompat$cleanProjection(GameRenderer game, double fov, Operation<Matrix4f> original) {
		Matrix4f projection = original.call(game, fov);
		LevelCapture.clean(projection);
		return projection;
	}

	@WrapMethod(method = "renderItemInHand")
	private void opalcompat$handPhase(Camera camera, float tick, Matrix4f projection, Operation<Void> original) {
		PackRenderer.pushPhase(Families.Phase.HAND);
		try {
			original.call(camera, tick, projection);
		} finally {
			PackRenderer.popPhase();
		}
	}

	/** The two pack passes have drawn the hands already. */
	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
	private void opalcompat$skipVanillaHands(ItemInHandRenderer renderer, float tick, PoseStack poses,
			MultiBufferSource.BufferSource buffers, LocalPlayer player, int light, Operation<Void> original) {
		if (!PackRenderer.handsHandled()) original.call(renderer, tick, poses, buffers, player, light);
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void opalcompat$screenEffectAfterFinal(Minecraft minecraft, PoseStack poses, Operation<Void> original) {
		if (!PackRenderer.postponeScreenEffect()) original.call(minecraft, poses);
	}
}
