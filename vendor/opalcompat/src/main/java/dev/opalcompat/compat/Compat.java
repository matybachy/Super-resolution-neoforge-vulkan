package dev.opalcompat.compat;

import dev.opalcompat.OpalCompat;
import net.neoforged.neoforge.common.NeoForge;

/**
 * What the mods in {@link Mods} need so that they run in one game. With VulkanMod Reforged, Opal's OpenGL renderer stands down and
 * its choice of pack goes to the Vulkan side, and the settings of all of them are on VulkanMod's screen. Without it, Opal is the
 * renderer and gets what {@code CompatMixinPlugin} lists for OpenGL.
 */
public final class Compat {

	private Compat() {
	}

	public static void start() {
		OpalCompat.LOGGER.info("VulkanMod Reforged {}, Opal {}, Vectors {}", Mods.VULKAN, Mods.OPAL, Mods.VECTORS);
		if (Mods.VULKAN) {
			if (Mods.OPAL) OpalLink.start();
			VulkanGui.register();
		}
		if (GlAutoTest.ENABLED) {
			NeoForge.EVENT_BUS.register(GlAutoTest.INSTANCE);
		}
	}
}
