package dev.opalcompat;

import dev.opalcompat.dev.AutoTest;
import dev.opalcompat.pack.ShaderPacks;
import dev.opalcompat.screen.ShaderPages;
import dev.vitrail.Vitrail;
import dev.vitrail.pack.option.EngineDefines;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.vulkanmod.vulkan.device.DeviceManager;

/** The shader packs on VulkanMod Reforged. Everything that names its classes starts here, and this runs only with it in the game. */
final class VulkanSide {

	private VulkanSide() {
	}

	static void start(IEventBus modBus, ModContainer container) {
		String vulkanMod = ModList.get().getModContainerById("vulkanmod")
				.map(c -> c.getModInfo().getVersion().toString())
				.orElseThrow(() -> new IllegalStateException("VulkanMod Reforged is not loaded"));
		OpalCompat.LOGGER.info("{} {} running on VulkanMod Reforged {}", OpalCompat.MOD_ID,
				container.getModInfo().getVersion(), vulkanMod);

		Vitrail.initClient(new NeoForgePlatform(container.getModInfo().getVersion().toString()));
		// Packs branch on MC_VERSION; Vitrail's default is 26.2.
		EngineDefines.machine(EngineDefines.Environment.of(GameVersion.MC_VERSION));

		ShaderPages.register();

		modBus.addListener(FMLClientSetupEvent.class, event -> {
			logDevice();
			if (Boolean.getBoolean("opalcompat.reportAll")) {
				ShaderPacks.reportAll();
			} else {
				ShaderPacks.selectedPath().ifPresent(ShaderPacks::loadAndReport);
			}
		});

		if (AutoTest.ENABLED) {
			NeoForge.EVENT_BUS.register(AutoTest.INSTANCE);
		}
	}

	private static void logDevice() {
		if (DeviceManager.device != null) {
			OpalCompat.LOGGER.info("Vulkan device: {}", DeviceManager.device.deviceName);
		} else {
			OpalCompat.LOGGER.warn("VulkanMod Reforged has not created a Vulkan device yet");
		}
	}
}
