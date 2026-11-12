package dev.opalcompat;

import dev.opalcompat.compat.Compat;
import dev.opalcompat.compat.Mods;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shader packs on VulkanMod Reforged, and what it takes for Opal, Vectors and the mods around them to run in one game. Without
 * VulkanMod there is no Vulkan side and Opal is the renderer: only {@link Compat} runs, so nothing here may name VulkanMod's classes.
 */
@Mod(value = OpalCompat.MOD_ID, dist = Dist.CLIENT)
public final class OpalCompat {

	public static final String MOD_ID = "opalcompat";
	public static final Logger LOGGER = LoggerFactory.getLogger("OpalCompat");

	public OpalCompat(IEventBus modBus, ModContainer container) {
		if (Mods.VULKAN) VulkanSide.start(modBus, container);
		Compat.start();
	}
}
