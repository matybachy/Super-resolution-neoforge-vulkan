package dev.matybachy.srvmr.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.matybachy.srvmr.render.FrameGen;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A generated frame ({@link FrameGen}) leaves the world and the hand out: the game has set its camera up for the frame
 * by then, and the picture is DLSS's last one moved to that camera. Not required: if another mod changes the same calls,
 * frame generation turns itself off and the game draws as always.
 */
@Mixin(GameRenderer.class)
public abstract class FrameGenMixin {

	@WrapOperation(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void srvmr$leaveWorldOut(LevelRenderer level, DeltaTracker tracker, boolean blockOutline, Camera camera,
			GameRenderer renderer, LightTexture light, Matrix4f modelView, Matrix4f projection, Operation<Void> original) {
		if (FrameGen.skipLevel(camera, modelView, projection)) return;
		original.call(level, tracker, blockOutline, camera, renderer, light, modelView, projection);
	}

	@WrapOperation(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/Camera;FLorg/joml/Matrix4f;)V"))
	private void srvmr$leaveHandOut(GameRenderer renderer, Camera camera, float partialTick, Matrix4f projection,
			Operation<Void> original) {
		if (FrameGen.levelSkipped()) return;
		original.call(renderer, camera, partialTick, projection);
	}
}
