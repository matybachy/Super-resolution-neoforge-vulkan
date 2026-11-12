package dev.opalcompat.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons binds its listener to Chunky on the first chunk it hears of. Chunky only exists from ServerStartingEvent on, and
 * the integrated server loads its first chunks before that, so with both mods every singleplayer world crashed with "Chunky is not
 * loaded". Until Chunky is there the bind fails and stays to do: DH comes back here with every chunk.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor", remap = false)
public abstract class DhChunkyMixin {

	@Shadow
	private boolean listenerBound;

	@Shadow
	protected abstract void bindOnGenerationProgressEvent();

	@Inject(method = "tryRunFirstTimeSetup", at = @At("HEAD"), cancellable = true)
	private void opalcompat$whenChunkyIsThere(CallbackInfo ci) {
		ci.cancel();
		if (this.listenerBound) return;
		try {
			this.bindOnGenerationProgressEvent();
			this.listenerBound = true;
		} catch (IllegalStateException e) {
			// Not started yet.
		}
	}
}
