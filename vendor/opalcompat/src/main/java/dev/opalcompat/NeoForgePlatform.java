package dev.opalcompat;

import dev.vitrail.platform.VitrailPlatform;
import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** What the code taken from Vitrail asks of the loader, answered by NeoForge. */
final class NeoForgePlatform implements VitrailPlatform {

	private final String modVersion;

	NeoForgePlatform(String modVersion) {
		this.modVersion = modVersion;
	}

	@Override
	public String loaderName() {
		return "NeoForge";
	}

	@Override
	public String loaderVersion() {
		return FMLLoader.versionInfo().neoForgeVersion();
	}

	@Override
	public String modVersion() {
		return modVersion;
	}

	@Override
	public String minecraftVersion() {
		return SharedConstants.getCurrentVersion().getName();
	}

	@Override
	public Path gameDirectory() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}
}
