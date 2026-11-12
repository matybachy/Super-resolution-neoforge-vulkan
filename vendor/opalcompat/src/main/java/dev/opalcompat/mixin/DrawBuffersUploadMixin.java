package dev.opalcompat.mixin;

import dev.opalcompat.terrain.TerrainIndices;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** How many quads each uploaded layer of a section has, for the indices terrain is then drawn with: {@link TerrainIndices}. */
@Mixin(value = DrawBuffers.class, remap = false)
public abstract class DrawBuffersUploadMixin {

	@Inject(method = "stageUpload", at = @At("HEAD"))
	private void opalcompat$indices(RenderSection section, UploadBuffer buffer, TerrainRenderType layer, boolean fullReplacement,
			CallbackInfoReturnable<?> cir) {
		if (!buffer.indexOnly) TerrainIndices.uploading(section, layer, buffer.indexCount, buffer.autoIndices);
	}
}
