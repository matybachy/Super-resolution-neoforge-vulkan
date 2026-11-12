package dev.opalcompat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.opalcompat.texture.AnimatedSprites;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;

/** Baking asks every sprite of every model where it is, and draws with none of them: {@link AnimatedSprites}. */
@Mixin(ModelBakery.class)
public abstract class ModelBakeryUseMixin {

	@WrapMethod(method = "bakeModels")
	private void opalcompat$baking(ModelBakery.TextureGetter textures, Operation<Void> original) {
		AnimatedSprites.baking(1);
		try {
			original.call(textures);
		} finally {
			AnimatedSprites.baking(-1);
		}
	}
}
