package dev.opal.pack;

/** What Opal Compat calls of Opal, to compile without its jar. Not in the built jar. */
public final class ImageSpec {
	public String uniform() {
		throw new AssertionError();
	}

	public String sampler() {
		throw new AssertionError();
	}
}
