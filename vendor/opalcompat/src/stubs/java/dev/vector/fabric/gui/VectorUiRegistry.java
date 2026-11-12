package dev.vector.fabric.gui;

/** What Opal Compat calls of Vectors, to compile without its jar. Not in the built jar. */
public final class VectorUiRegistry {
	public static void registerPage(VectorPage page) {
		throw new AssertionError();
	}
}
