package dev.opalcompat.mixin;

import dev.opalcompat.texture.AnimatedSprites;
import dev.opalcompat.texture.UsedSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nor is the frame between two frames worked out for it, which is the costlier half: {@link AnimatedSprites}. */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$InterpolationData", priority = 1100)
public abstract class SpriteInterpolationUseMixin {

	@Shadow
	@Final
	SpriteContents this$0;

	@Inject(method = "uploadInterpolatedFrame", at = @At("HEAD"), cancellable = true)
	private void opalcompat$onlyUsed(CallbackInfo ci) {
		if (AnimatedSprites.leftAlone((UsedSprite) (Object) this.this$0)) ci.cancel();
	}
}
