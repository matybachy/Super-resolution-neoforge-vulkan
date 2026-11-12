package dev.opalcompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The list of a quad tree's enabled sections is the truth of what is drawn. {@code LodQuadTree} swaps it under a lock
 * and only then disables the sections it replaced, from its own thread; {@code buildRenderList} copies the list and
 * then asks each section whether it is still enabled. Between those two, a list copied just before the swap loses the
 * sections the swap replaced, while the ones replacing them are not in it: a hole in the far terrain for one frame.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.RenderBufferHandler", remap = false)
public abstract class DhRenderListMixin {

	@Redirect(method = "buildRenderList", at = @At(value = "INVOKE",
			target = "Lcom/seibel/distanthorizons/core/render/QuadTree/LodRenderSection;getRenderingEnabled()Z"))
	private boolean opalcompat$listed(com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection section) {
		return true;
	}
}
