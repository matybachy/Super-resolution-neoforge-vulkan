package dev.opalcompat.compat;

import dev.vector.core.ConfigManager;
import dev.vector.core.VectorConfig;
import dev.vector.fabric.VectorClient;
import dev.vector.fabric.gui.VectorScreen;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.screen.ActionOption;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.RangeOption;
import net.vulkanmod.config.option.SwitchOption;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Vectors' settings as pages of VulkanMod Reforged's screen. Only what does something on Vulkan: terrain batching, the chunk
 * queue and its workers belong to the OpenGL terrain renderer. The words are Vectors' own, the ranges those of its screen, and
 * the rest of it (graphs, benchmarks, themes) is a button away.
 */
final class VectorsPages {

	private static final String[] PROFILES = {"performance", "balanced", "precision", "quality", "custom"};

	private static VectorConfig draft;
	private static boolean edited;

	private VectorsPages() {
	}

	static ModSettingsEntry entry() {
		return new ModSettingsEntry(Component.literal("Vectors"),
				() -> ResourceLocation.fromNamespaceAndPath("vectors", "icon.png"),
				VectorsPages::pages,
				VectorsPages::apply);
	}

	private static List<OptionPage> pages() {
		draft = ConfigManager.copy(VectorClient.INSTANCE.config());
		edited = false;
		return List.of(
				new OptionPage(text("opalcompat.compat.vectors.page.workload"), new OptionBlock[] {
						block("setting.entities", "entityHelp",
								toggle("entities"),
								range("passiveDistance", 16, 256, 8),
								range("hostileDistance", 16, 256, 8),
								range("itemDistance", 16, 256, 8),
								range("decorativeDistance", 16, 256, 8)),
						block("setting.blockEntities", "blockHelp",
								toggle("hiddenChests"),
								toggle("blockEntities"),
								range("chestDistance", 16, 256, 8),
								range("signDistance", 16, 256, 8),
								range("bannerDistance", 16, 256, 8)),
						block("setting.particles", "particleHelp",
								toggle("particles"),
								range("particleBudget", 10, 2000, 10),
								range("particleDistance", 16, 256, 8),
								toggle("reduceExpensive")),
						block("setting.weather", "weatherHelp",
								toggle("weather"),
								range("weatherDensity", 0, 100, 5),
								range("weatherParticleDensity", 0, 100, 5),
								range("weatherDistance", 2, 16, 1)),
				}),
				new OptionPage(text("opalcompat.compat.vectors.page.tune"), new OptionBlock[] {
						new OptionBlock("", new Option<?>[] {
								profile(),
								new ActionOption(Component.translatable("opalcompat.compat.vectors.open"), () -> Component.translatable("opalcompat.compat.vectors.open.button"),
										() -> Minecraft.getInstance().setScreen(new VectorScreen(Minecraft.getInstance().screen))),
						}),
						block("page.smart", "smartDescription",
								toggle("adaptive"),
								range("targetFps", 0, 360, 10),
								range("minQuality", 25, 100, 5),
								range("maxQuality", 25, 100, 5),
								toggle("autoRenderDistance"),
								range("minRenderDistance", 2, 32, 1)),
						block("setting.overlay", "",
								toggle("overlay"),
								toggle("overlayFps"),
								toggle("overlayFrameTime"),
								toggle("overlayAverage"),
								toggle("overlayLow"),
								toggle("overlayMemory"),
								toggle("overlayHideScreens")),
				}));
	}

	private static void apply() {
		if (!edited) return;
		draft.validate();
		VectorClient.INSTANCE.commit(draft);
		edited = false;
	}

	private static OptionBlock block(String title, String help, Option<?>... options) {
		if (!help.isEmpty()) {
			Component tooltip = Component.translatable(help);
			for (Option<?> option : options) option.setTooltip(tooltip);
		}
		return new OptionBlock(text(title), options);
	}

	private static Option<?> profile() {
		CyclingOption<String> option = new CyclingOption<>(Component.translatable("setting.profile"), PROFILES,
				name -> {
					draft.applyProfile(name);
					edited = true;
				},
				() -> draft.profile);
		option.setTranslator(name -> Component.translatable("value." + name));
		return option;
	}

	private static Option<?> toggle(String field) {
		return new SwitchOption(Component.translatable("setting." + field),
				value -> set(field, value),
				() -> (Boolean) get(field));
	}

	private static Option<?> range(String field, int min, int max, int step) {
		return new RangeOption(Component.translatable("setting." + field), min, max, step,
				value -> set(field, value),
				() -> (Integer) get(field));
	}

	private static Object get(String field) {
		try {
			Field f = VectorConfig.class.getField(field);
			return f.get(draft);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Vectors has no setting " + field, e);
		}
	}

	private static void set(String field, Object value) {
		try {
			VectorConfig.class.getField(field).set(draft, value);
			edited = true;
		} catch (ReflectiveOperationException e) {
			OpalCompat.LOGGER.warn("Vectors has no setting {}", field, e);
		}
	}

	private static String text(String key) {
		return Component.translatable(key).getString();
	}
}
