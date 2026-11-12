package dev.vector.core;

/** What Opal Compat calls of Vectors, to compile without its jar. Not in the built jar. */
public final class ConfigManager {
	public static VectorConfig copy(VectorConfig config) {
		throw new AssertionError();
	}
}
