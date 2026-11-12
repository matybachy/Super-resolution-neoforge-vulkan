package dev.opalcompat.render;

import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.render.BiomeClassifier;
import dev.vitrail.uniform.BiomeCategory;
import dev.opalcompat.GameVersion;
import dev.opalcompat.dh.DhSupport;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.biome.Biomes;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the machine is, as packs branch on it: {@code MC_VERSION}, the GPU vendor, the mip level and the
 * biome symbols. Follows Vitrail's {@code render/PackDefines}. Has to be installed before a pack is
 * opened, because the settings copy this table when they are read.
 */
final class Machine {

	private Machine() {
	}

	static void install() {
		String vendor = DeviceManager.device == null ? "" : DeviceManager.device.vendorIdString;
		String renderer = DeviceManager.device == null ? "" : DeviceManager.device.deviceName;
		int mipmap = Minecraft.getInstance().options.mipmapLevels().get();
		// Named so the Biomes class has initialised, and every biome has gone past BiomeClassifier.record.
		Objects.requireNonNull(Biomes.THE_VOID);
		Map<String, Integer> biomes = new LinkedHashMap<>();
		List<String> names = BiomeClassifier.names();
		for (int id = 0; id < names.size(); id++) {
			biomes.put(names.get(id), id);
		}
		List<String> categories = Arrays.stream(BiomeCategory.values())
				.map(category -> category.name().toUpperCase(Locale.ROOT)).toList();
		EngineDefines.machine(new EngineDefines.Environment(GameVersion.MC_VERSION, os(), vendor, renderer, mipmap,
				DhSupport.ACTIVE, biomes, categories, null, independentBlend()));
	}

	private static boolean independentBlend() {
		return DeviceManager.device != null && DeviceManager.device.availableFeatures.features().independentBlend();
	}

	private static EngineDefines.Os os() {
		return switch (Util.getPlatform()) {
			case WINDOWS -> EngineDefines.Os.WINDOWS;
			case OSX -> EngineDefines.Os.MAC;
			case LINUX -> EngineDefines.Os.LINUX;
			default -> EngineDefines.Os.OTHER;
		};
	}
}
