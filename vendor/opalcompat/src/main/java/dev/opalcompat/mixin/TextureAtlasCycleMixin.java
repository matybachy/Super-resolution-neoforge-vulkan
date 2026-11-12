package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.opalcompat.texture.AnimatedSprites;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;

/** Only the uploads an atlas makes for its animations are held back, never a sprite's first frame: {@link AnimatedSprites}. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasCycleMixin {

	@WrapMethod(method = "cycleAnimationFrames")
	private void opalcompat$cycling(Operation<Void> original) {
		AnimatedSprites.cycling(true);
		try {
			original.call();
		} finally {
			AnimatedSprites.cycling(false);
		}
	}
}
