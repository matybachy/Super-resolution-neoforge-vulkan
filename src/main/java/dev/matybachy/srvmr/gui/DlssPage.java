package dev.matybachy.srvmr.gui;

import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.SrvmrConfig;
import dev.matybachy.srvmr.render.DlssResolve;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.RenderScale;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.RangeOption;
import net.vulkanmod.config.option.SwitchOption;

import java.util.List;

/**
 * DLSS on its own page of VulkanMod Reforged's video settings, through the registry VulkanMod Reforged keeps for mods'
 * pages ({@code ModSettingsRegistry}). The page edits a copy of the configuration; Apply takes it, writes
 * {@code config/srvmr.properties} and has DLSS start afresh with it. Render Scale on this page is VulkanMod Reforged's
 * own setting, shown with the DLSS mode it gives; VulkanMod Reforged resizes its targets when it changes.
 */
public final class DlssPage {

	private static final SrvmrConfig.Quality[] QUALITIES = {SrvmrConfig.Quality.AUTO, SrvmrConfig.Quality.QUALITY,
			SrvmrConfig.Quality.BALANCED, SrvmrConfig.Quality.PERFORMANCE, SrvmrConfig.Quality.ULTRA_PERFORMANCE,
			SrvmrConfig.Quality.DLAA};
	/** The slider's lowest step, which stands for DLSS Ultra Performance's third of the screen. */
	private static final int ULTRA_STEP = 30;
	private static final int ULTRA_SCALE = 33;
	private static final SrvmrConfig.DepthMode[] DEPTHS = SrvmrConfig.DepthMode.values();

	private static SrvmrConfig.Draft draft;
	private static boolean edited;
	private static boolean scaleEdited;
	private static boolean qualityEdited;

	private DlssPage() {
	}

	public static void register() {
		try {
			ModSettingsRegistry.INSTANCE.addModEntry(new ModSettingsEntry(Component.translatable("srvmr.page.group"),
					() -> ResourceLocation.fromNamespaceAndPath(Srvmr.MOD_ID, "icon.png"),
					DlssPage::pages,
					DlssPage::apply));
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot add the DLSS page to VulkanMod Reforged's video settings", e);
		}
	}

	private static List<OptionPage> pages() {
		draft = SrvmrConfig.get().draft();
		edited = false;
		scaleEdited = false;
		qualityEdited = false;
		return List.of(new OptionPage(text("srvmr.page"), new OptionBlock[] {
				new OptionBlock("", new Option<?>[] {
						new SwitchOption(Component.translatable("srvmr.option.enabled"),
								value -> {
									draft.enabled = value;
									edited = true;
								},
								() -> draft.enabled)
								.setTooltip(Component.translatable("srvmr.option.enabled.tooltip")),
						renderScale(),
						quality(),
				}),
				new OptionBlock(text("srvmr.block.framegen"), new Option<?>[] {
						frameGeneration(),
						framePacing(),
				}),
				new OptionBlock(text("srvmr.block.troubleshooting"), new Option<?>[] {
						toggle("srvmr.option.jitter", value -> draft.jitter = value, () -> draft.jitter),
						toggle("srvmr.option.invertJitterX", value -> draft.invertJitterX = value, () -> draft.invertJitterX),
						toggle("srvmr.option.invertJitterY", value -> draft.invertJitterY = value, () -> draft.invertJitterY),
						toggle("srvmr.option.invertMotionY", value -> draft.invertMotionY = value, () -> draft.invertMotionY),
						depth("srvmr.option.packDepth", value -> draft.packDepth = value, () -> draft.packDepth),
						toggle("srvmr.option.packDepthUpsideDown", value -> draft.packDepthUpsideDown = value,
								() -> draft.packDepthUpsideDown),
						depth("srvmr.option.vanillaDepth", value -> draft.vanillaDepth = value, () -> draft.vanillaDepth),
				}),
		}));
	}

	/**
	 * VulkanMod Reforged's Render Scale, in its steps of 5, with what DLSS makes of it. Below VulkanMod Reforged's 50 % it
	 * goes only with DLSS on ({@code RenderScaleMixin}); the lowest step is DLSS Ultra Performance's 33 %.
	 */
	private static Option<?> renderScale() {
		return new RangeOption(Component.translatable("srvmr.option.renderScale"), ULTRA_STEP, RenderScale.MAX,
				RenderScale.STEP,
				value -> Component.translatable(scaleKey(value), value <= ULTRA_STEP ? ULTRA_SCALE : value),
				value -> {
					int scale = value <= ULTRA_STEP ? ULTRA_SCALE : value;
					// Below 50 % only while DLSS is on in what is being applied; VulkanMod Reforged keeps 50 % otherwise.
					Initializer.CONFIG.renderScale = draft.enabled ? Math.max(ULTRA_SCALE, Math.min(RenderScale.MAX, scale))
							: Math.max(RenderScale.MIN, Math.min(RenderScale.MAX, scale));
					scaleEdited = true;
				},
				() -> {
					int scale = RenderScale.clamp(Initializer.CONFIG.renderScale);
					return scale < ULTRA_STEP + RenderScale.STEP ? ULTRA_STEP : scale;
				})
				.setTooltip(Component.translatable("srvmr.option.renderScale.tooltip"));
	}

	private static String scaleKey(int scale) {
		if (scale >= RenderScale.MAX) return "srvmr.scale.off";
		if (scale >= 65) return "srvmr.scale.quality";
		if (scale >= 55) return "srvmr.scale.balanced";
		if (scale >= 45) return "srvmr.scale.performance";
		return "srvmr.scale.ultra";
	}

	private static Option<?> quality() {
		CyclingOption<SrvmrConfig.Quality> option = new CyclingOption<>(Component.translatable("srvmr.option.quality"),
				QUALITIES,
				value -> {
					draft.quality = value;
					edited = true;
					qualityEdited = true;
				},
				() -> draft.quality);
		option.setTranslator(value -> Component.translatable("srvmr.quality." + value.name().toLowerCase(java.util.Locale.ROOT)));
		option.setTooltip(Component.translatable("srvmr.option.quality.tooltip"));
		return option;
	}

	/** Off, 2x to 6x: frames between the real ones, only while DLSS runs. */
	private static Option<?> frameGeneration() {
		CyclingOption<SrvmrConfig.FrameGeneration> option = new CyclingOption<>(
				Component.translatable("srvmr.option.frameGeneration"), SrvmrConfig.FrameGeneration.values(),
				value -> {
					draft.frameGeneration = value;
					edited = true;
				},
				() -> draft.frameGeneration);
		option.setTranslator(value -> Component.translatable("srvmr.framegen." + value.name().toLowerCase(java.util.Locale.ROOT)));
		option.setTooltip(Component.translatable("srvmr.option.frameGeneration.tooltip"));
		return option;
	}

	/** How long a generated frame lasts against a real one, in tens of per cent: 0 is the most frames, 90 the most even. */
	private static Option<?> framePacing() {
		return new RangeOption(Component.translatable("srvmr.option.framePacing"), 0, 90, 10,
				value -> Component.translatable("srvmr.pacing.value", value),
				value -> {
					draft.framePacing = value / 100.0F;
					edited = true;
				},
				() -> Math.round(draft.framePacing * 10.0F) * 10)
				.setTooltip(Component.translatable("srvmr.option.framePacing.tooltip"));
	}

	private static Option<?> toggle(String key, java.util.function.Consumer<Boolean> set,
			java.util.function.Supplier<Boolean> get) {
		return new SwitchOption(Component.translatable(key),
				value -> {
					set.accept(value);
					edited = true;
				},
				get)
				.setTooltip(Component.translatable(key + ".tooltip"));
	}

	private static Option<?> depth(String key, java.util.function.Consumer<SrvmrConfig.DepthMode> set,
			java.util.function.Supplier<SrvmrConfig.DepthMode> get) {
		CyclingOption<SrvmrConfig.DepthMode> option = new CyclingOption<>(Component.translatable(key), DEPTHS,
				value -> {
					set.accept(value);
					edited = true;
				},
				get);
		option.setTranslator(value -> Component.translatable("srvmr.depth." + value.name().toLowerCase(java.util.Locale.ROOT)));
		option.setTooltip(Component.translatable(key + ".tooltip"));
		return option;
	}

	/** Apply: the edited copy becomes the configuration; VulkanMod Reforged's own setting is saved where it changed. */
	private static void apply() {
		try {
			// A mode chosen without touching Render Scale brings the scale it is made for, so that it is the mode DLSS
			// runs: a mode the scale does not fit gives way to the one it does (DlssResolve.quality).
			if (qualityEdited && !scaleEdited && draft != null && draft.enabled) {
				int scale = scaleFor(draft.quality);
				if (scale > 0 && scale != Initializer.CONFIG.renderScale) {
					Initializer.CONFIG.renderScale = scale;
					scaleEdited = true;
				}
			}
			qualityEdited = false;
			if (edited && draft != null) {
				SrvmrConfig.apply(draft);
				DlssResolve.settingsChanged();
				edited = false;
				Srvmr.LOGGER.info("[dlss] settings applied: DLSS {}, mode {}", draft.enabled ? "on" : "off",
						draft.quality.name().toLowerCase(java.util.Locale.ROOT));
			}
			if (scaleEdited) {
				Initializer.CONFIG.write();
				scaleEdited = false;
			}
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot apply the DLSS settings", e);
		}
	}

	/**
	 * VulkanMod Reforged's step of 5 % nearest each mode's ratio (Quality 67 %, Balanced 58 %, Performance 50 %), and the
	 * exact third for Ultra Performance; 0 for Auto and DLAA, which take the scale as it is.
	 */
	private static int scaleFor(SrvmrConfig.Quality quality) {
		return switch (quality) {
			case QUALITY -> 65;
			case BALANCED -> 60;
			case PERFORMANCE -> 50;
			case ULTRA_PERFORMANCE -> ULTRA_SCALE;
			default -> 0;
		};
	}

	private static String text(String key) {
		return Component.translatable(key).getString();
	}
}
