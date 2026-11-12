package dev.opalcompat.compat.mixin;

import dev.opal.pack.ImageSpec;
import dev.opalcompat.compat.CustomImageUnits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** One custom image of Opal's, a private record, as {@link CustomImageUnits} reads it. */
@Pseudo
@Mixin(targets = "dev.opal.client.CustomImages$Image", remap = false)
public abstract class OpalImageMixin implements CustomImageUnits.Image {

	@Shadow
	public abstract ImageSpec spec();

	@Shadow
	public abstract int texture();

	@Shadow
	public abstract int target();

	@Shadow
	public abstract int format();

	@Override
	public String opalcompat$uniform() {
		return spec().uniform();
	}

	@Override
	public String opalcompat$sampler() {
		return spec().sampler();
	}

	@Override
	public int opalcompat$texture() {
		return texture();
	}

	@Override
	public int opalcompat$target() {
		return target();
	}

	@Override
	public int opalcompat$format() {
		return format();
	}
}
