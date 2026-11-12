package dev.vector.fabric;

import dev.vector.core.VectorConfig;

/** What Opal Compat calls of Vectors, to compile without its jar. Not in the built jar. */
public final class VectorClient {
	public static VectorClient INSTANCE;

	public VectorConfig config() {
		throw new AssertionError();
	}

	public void commit(VectorConfig config) {
		throw new AssertionError();
	}
}
