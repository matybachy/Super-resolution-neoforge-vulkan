package dev.opal.client;

import dev.opal.pack.ShaderPack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** What Opal Compat calls of Opal, to compile without its jar. Not in the built jar. */
public final class ShaderOptionsScreen extends Screen {
	public ShaderOptionsScreen(Screen parent, ShaderPack pack) {
		super(Component.empty());
	}
}
