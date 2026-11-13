package dev.matybachy.srvmr;

import dev.matybachy.srvmr.gui.DlssPage;
import dev.matybachy.srvmr.gui.SidebarScroll;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NVIDIA DLSS Super Resolution for VulkanMod Reforged.
 * <p>
 * VulkanMod Reforged draws the world into a target of its Render Scale and, before the GUI, stretches that target onto
 * the swapchain ({@code DefaultMainPass.resolveScaledFramebufferToSwapchain}). This mod puts DLSS in that one place:
 * the world is drawn with a jittered projection, motion vectors are worked out from the depth and the camera, and DLSS
 * writes a full-size picture that VulkanMod Reforged then puts on the screen in place of its stretched one. Everything
 * else stays VulkanMod Reforged's: the GUI is drawn over it at full size as before.
 * <p>
 * Whatever goes wrong, DLSS stands aside for the rest of the session and VulkanMod Reforged stretches the picture as it
 * always did. The {@code [dlss]} lines in the log say why.
 */
@Mod(value = Srvmr.MOD_ID, dist = Dist.CLIENT)
public final class Srvmr {

	public static final String MOD_ID = "srvmr";
	public static final Logger LOGGER = LoggerFactory.getLogger("SuperResolutionVMR");

	public Srvmr(IEventBus modBus, ModContainer container) {
		SrvmrConfig config = SrvmrConfig.get();
		LOGGER.info("[dlss] {} {}: {}; DLSS runs while VulkanMod Reforged's Render Scale is below 100 % (Video Settings, DLSS page)",
				container.getModInfo().getDisplayName(), container.getModInfo().getVersion(), config.enabled ? "on" : "off");
		DlssPage.register();
		SidebarScroll.register();
	}
}
