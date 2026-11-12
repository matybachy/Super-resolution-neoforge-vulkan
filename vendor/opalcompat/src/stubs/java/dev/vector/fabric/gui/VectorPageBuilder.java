package dev.vector.fabric.gui;

/** What Opal Compat calls of Vectors, to compile without its jar. Not in the built jar. */
public interface VectorPageBuilder {
	VectorPageBuilder addAction(String title, String hint, String button, boolean primary, Runnable action);
}
