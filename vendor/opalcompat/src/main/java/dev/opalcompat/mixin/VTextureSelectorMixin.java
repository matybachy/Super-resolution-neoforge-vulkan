package dev.opalcompat.mixin;

import dev.opalcompat.OpalCompat;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An upload with no image behind the bound texture: OpenGL ignores it, VulkanMod Reforged throws. Steam 'n' Rails does this while
 * the game loads, it uploads its phantom track sprites from the tick of the block atlas as soon as they exist, and until the load
 * is over that atlas has an id but no image. VMR's GL emulation already skips such an upload ({@code GlTexture.texSubImage2D});
 * this is the same for the path {@code NativeImage} takes.
 */
@Mixin(value = VTextureSelector.class, remap = false)
public abstract class VTextureSelectorMixin {

	@Shadow
	@Final
	private static VulkanImage[] boundTextures;

	@Shadow
	private static int activeTexture;

	@Unique
	private static boolean opalcompat$reported;

	@Inject(method = "uploadSubTexture", at = @At("HEAD"), cancellable = true)
	private static void opalcompat$noImage(CallbackInfo ci) {
		if (boundTextures[activeTexture] != null) return;
		ci.cancel();
		if (!opalcompat$reported) {
			opalcompat$reported = true;
			OpalCompat.LOGGER.info("Skipped an upload to a texture with no image yet, as OpenGL does", new Throwable("Uploaded from"));
		}
	}
}
