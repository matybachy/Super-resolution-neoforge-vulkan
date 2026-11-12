package dev.opalcompat.mixin;

import dev.opalcompat.terrain.ChunkFlow;
import net.vulkanmod.render.optimization.ChunkFrameDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Reforged's own count of the sections a frame uploaded, for the {@code [chunks]} line: {@link ChunkFlow}. */
@Mixin(value = ChunkFrameDiagnostics.class, remap = false)
public abstract class ChunkUploadCountMixin {

	@Inject(method = "recordUploads", at = @At("HEAD"))
	private static void opalcompat$count(int sections, long staged, int waitingAfter, CallbackInfo ci) {
		ChunkFlow.uploaded(sections, staged, waitingAfter);
	}
}
