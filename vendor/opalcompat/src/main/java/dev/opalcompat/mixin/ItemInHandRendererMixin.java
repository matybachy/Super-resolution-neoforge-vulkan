package dev.opalcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.opalcompat.render.HandPass;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Which hand goes in which of the two passes ({@link HandPass}). Iris's rule, by the item held. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void opalcompat$selectHand(AbstractClientPlayer player, float tick, float pitch, InteractionHand hand,
			float swing, ItemStack stack, float equip, PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
		if (HandPass.skip(stack)) ci.cancel();
	}
}
