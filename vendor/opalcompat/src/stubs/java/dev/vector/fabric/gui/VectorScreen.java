package dev.vector.fabric.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** What Opal Compat calls of Vectors, to compile without its jar. Not in the built jar. */
public final class VectorScreen extends Screen {
	public VectorScreen(Screen parent) {
		super(Component.empty());
	}
}
