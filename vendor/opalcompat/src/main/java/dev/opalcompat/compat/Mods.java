package dev.opalcompat.compat;

import net.neoforged.fml.loading.LoadingModList;

/** Which of the mods this one works with are in the game. Asked from mixin plugins too, before mods are constructed. */
public final class Mods {

	public static final boolean VULKAN = present("vulkanmod");
	public static final boolean OPAL = present("opal");
	public static final boolean VECTORS = present("vectors");

	private Mods() {
	}

	private static boolean present(String id) {
		LoadingModList mods = LoadingModList.get();
		return mods != null && mods.getModFileById(id) != null;
	}
}
