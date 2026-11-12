package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.opalcompat.texture.AnimatedSprites;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** A baked quad carries its coordinates with it, so drawing one asks its sprite nothing: {@link AnimatedSprites}. */
@Mixin(BakedQuad.class)
public abstract class BakedQuadUseMixin {

	@Shadow
	@Final
	protected TextureAtlasSprite sprite;

	@ModifyReturnValue(method = "getVertices", at = @At("RETURN"))
	private int[] opalcompat$used(int[] vertices) {
		AnimatedSprites.use(this.sprite);
		return vertices;
	}

	@ModifyReturnValue(method = "getSprite", at = @At("RETURN"))
	private TextureAtlasSprite opalcompat$usedSprite(TextureAtlasSprite sprite) {
		AnimatedSprites.use(sprite);
		return sprite;
	}
}
