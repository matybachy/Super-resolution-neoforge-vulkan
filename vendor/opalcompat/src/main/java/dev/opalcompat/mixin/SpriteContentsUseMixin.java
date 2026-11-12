package dev.opalcompat.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.opalcompat.texture.AnimatedSprites;
import dev.opalcompat.texture.UsedSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A frame of an animation is not uploaded for a sprite nothing has drawn with: {@link AnimatedSprites}. */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsUseMixin implements UsedSprite {

	@Unique
	private volatile boolean opalcompat$used;

	@Override
	public boolean opalcompat$used() {
		return this.opalcompat$used;
	}

	@Override
	public void opalcompat$use() {
		this.opalcompat$used = true;
	}

	@Inject(method = "upload", at = @At("HEAD"), cancellable = true)
	private void opalcompat$onlyUsed(int x, int y, int frameX, int frameY, NativeImage[] atlasData, CallbackInfo ci) {
		if (AnimatedSprites.leftAlone(this)) ci.cancel();
	}
}
