package dev.opalcompat.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The atlas size, for {@code atlasSize}. 1.21.1 keeps it private. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {

	@Accessor("width")
	int opalcompat$width();

	@Accessor("height")
	int opalcompat$height();
}
