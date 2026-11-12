package dev.opalcompat.screen;

import dev.vitrail.pack.load.PackLoader;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.pack.ShaderPacks;
import dev.opalcompat.render.PackRenderer;
import dev.opalcompat.texture.AnimatedSprites;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.SwitchOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Shaders as a page of VulkanMod Reforged's video settings, through the registry it keeps for other mods: on or off, which
 * pack, and the way to the pack's settings. Like the rest of that screen it takes effect on Apply.
 */
public final class ShaderPages {

	private static final String NONE = "";

	private static SwitchOption enabled;
	private static CyclingOption<String> pack;
	private static SwitchOption usedTextures;
	private static Function<Screen, Screen> listScreen = ShaderPackScreen::new;
	private static BiFunction<Screen, String, Screen> settingsScreen;

	private ShaderPages() {
	}

	public static void register() {
		ModSettingsRegistry.INSTANCE.addModEntry(new ModSettingsEntry(Component.translatable("opalcompat.page.group"),
				() -> ResourceLocation.fromNamespaceAndPath(OpalCompat.MOD_ID, "icon.png"),
				ShaderPages::pages,
				ShaderPages::apply));
	}

	/**
	 * For a mod with screens of its own over the same selection and {@code shaderpacks/<pack>.txt}: what the page's buttons open.
	 * The settings function gets the screen to come back to and the pack, and answers null where it cannot read that pack.
	 */
	public static void screens(Function<Screen, Screen> list, BiFunction<Screen, String, Screen> settings) {
		listScreen = list;
		settingsScreen = settings;
	}

	private static List<OptionPage> pages() {
		String[] packs = packs();
		enabled = new SwitchOption(Component.translatable("opalcompat.page.shaders"), value -> {},
				() -> !ShaderPacks.selectedName().isEmpty());
		enabled.setTooltip(Component.translatable("opalcompat.page.shaders.tip"));
		pack = new CyclingOption<>(Component.translatable("opalcompat.page.pack"), packs, value -> {},
				() -> shown(packs));
		pack.setTranslator(name -> name.isEmpty() ? Component.translatable("opalcompat.page.nopacks") : Component.literal(name));
		pack.setTooltip(name -> Component.literal(name));

		Option<?> settings = new ActionOption(Component.translatable("opalcompat.page.settings"), () -> Component.translatable("opalcompat.page.open"),
				() -> openSettings(pack.getNewValue())).setActivationFn(() -> packs.length > 0 && !packs[0].isEmpty());
		Option<?> list = new ActionOption(Component.translatable("opalcompat.page.list"), () -> Component.translatable("opalcompat.page.open"),
				() -> Minecraft.getInstance().setScreen(listScreen.apply(Minecraft.getInstance().screen)));
		Option<?> folder = new ActionOption(Component.translatable("opalcompat.screen.folder"), () -> Component.translatable("opalcompat.page.open"),
				ShaderPages::openFolder);
		usedTextures = new SwitchOption(Component.translatable("opalcompat.page.textures"), value -> {}, AnimatedSprites::enabled);
		usedTextures.setTooltip(Component.translatable("opalcompat.page.textures.tip"));
		Option<?> status = new ActionOption(Component.translatable("opalcompat.page.status"), ShaderPages::status, null);
		status.setTooltip(value -> statusInFull());

		return List.of(new OptionPage(Component.translatable("opalcompat.page.packs").getString(), new OptionBlock[] {
				new OptionBlock("", new Option<?>[] {enabled, pack}),
				new OptionBlock("", new Option<?>[] {settings, list, folder}),
				new OptionBlock("", new Option<?>[] {usedTextures}),
				new OptionBlock("", new Option<?>[] {status}),
		}));
	}

	private static void apply() {
		if (usedTextures != null && usedTextures.getAppliedValue() != AnimatedSprites.enabled()) AnimatedSprites.enable(usedTextures.getAppliedValue());
		String chosen = enabled.getAppliedValue() ? pack.getAppliedValue() : NONE;
		if (chosen.equals(ShaderPacks.selectedName())) return;
		try {
			ShaderPacks.select(chosen);
			PackRenderer.reload();
		} catch (IOException e) {
			OpalCompat.LOGGER.error("Cannot save the chosen shader pack", e);
		}
	}

	/** The pack row shows the one in use, or with shaders off the one they would come back with. */
	private static String shown(String[] packs) {
		String selected = ShaderPacks.selectedName();
		if (!selected.isEmpty()) return selected;
		String last = ShaderPacks.lastName();
		for (String name : packs) {
			if (name.equals(last)) return last;
		}
		return packs[0];
	}

	private static String[] packs() {
		List<String> names = new ArrayList<>();
		try {
			for (Path path : PackLoader.candidates(Minecraft.getInstance().gameDirectory.toPath())) {
				names.add(path.getFileName().toString());
			}
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Cannot list the shader packs", e);
		}
		String selected = ShaderPacks.selectedName();
		if (!selected.isEmpty() && !names.contains(selected)) names.add(selected);
		return names.isEmpty() ? new String[] {NONE} : names.toArray(new String[0]);
	}

	private static void openSettings(String name) {
		Minecraft minecraft = Minecraft.getInstance();
		Screen settings = settingsScreen != null ? settingsScreen.apply(minecraft.screen, name) : null;
		if (settings != null) {
			minecraft.setScreen(settings);
		} else {
			new ShaderPackScreen(minecraft.screen).openSettings(minecraft, name);
		}
	}

	private static void openFolder() {
		Path directory = ShaderPacks.directory();
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Cannot create {}", directory, e);
		}
		Util.getPlatform().openFile(directory.toFile());
	}

	private static Component status() {
		String error = PackRenderer.lastError().orElse(null);
		if (error != null) return Component.translatable("opalcompat.page.failed").withStyle(ChatFormatting.RED);
		String selected = ShaderPacks.selectedName();
		if (PackRenderer.needsRestart(selected)) return Component.translatable("opalcompat.page.restart").withStyle(ChatFormatting.YELLOW);
		if (selected.isEmpty()) return Component.translatable("opalcompat.page.off");
		return PackRenderer.runningPack().isPresent()
				? Component.translatable("opalcompat.page.running").withStyle(ChatFormatting.GREEN)
				: Component.translatable("opalcompat.page.pending");
	}

	private static Component statusInFull() {
		String error = PackRenderer.lastError().orElse(null);
		if (error != null) return Component.literal(error);
		String selected = ShaderPacks.selectedName();
		if (PackRenderer.needsRestart(selected)) return Component.translatable("opalcompat.screen.restart");
		if (selected.isEmpty()) return Component.translatable("opalcompat.screen.off");
		return PackRenderer.runningPack().<Component>map(name -> Component.translatable("opalcompat.screen.running", name))
				.orElse(Component.translatable("opalcompat.screen.pending", selected));
	}
}
