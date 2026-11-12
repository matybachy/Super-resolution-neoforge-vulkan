package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.opalcompat.texture.AnimatedSprites;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Whoever asks a sprite where it is in its atlas is about to draw with it: {@link AnimatedSprites}. */
@Mixin(TextureAtlasSprite.class)
public abstract class TextureAtlasSpriteUseMixin {

	@Shadow
	@Final
	private SpriteContents contents;

	@ModifyReturnValue(method = {"getU0", "getU1", "getV0", "getV1", "getU", "getV"}, at = @At("RETURN"))
	private float opalcompat$used(float coordinate) {
		AnimatedSprites.use(this.contents);
		return coordinate;
	}

	@ModifyReturnValue(method = "wrap", at = @At("RETURN"))
	private VertexConsumer opalcompat$usedWrapped(VertexConsumer consumer) {
		AnimatedSprites.use(this.contents);
		return consumer;
	}
}
