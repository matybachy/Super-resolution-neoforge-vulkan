package dev.matybachy.srvmr;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * {@code config/srvmr.properties}, also edited on the DLSS page of VulkanMod Reforged's video settings. Read at the first
 * use, which can be before the mod itself is constructed: VulkanMod Reforged creates its Vulkan device early and the
 * device has to be asked for DLSS's extensions then. A change from the page replaces the whole configuration at once and
 * is written back to the file; the render thread reads it frame by frame.
 * <p>
 * Some of the settings are there because they cannot be known without running the game: which way the jitter offset is
 * counted, and how the depth of the game's own picture is stored. If moving the camera smears the picture, those are
 * what to change, one at a time.
 */
public final class SrvmrConfig {

	/** How a depth sample maps to the OpenGL clip depth of the game's matrices. */
	public enum DepthMode {
		/** {@code 0.5 * (z + 1)}: the depth Opal Compat's pack targets carry. */
		FORWARD,
		/** {@code z} itself: the game's own drawing on VulkanMod Reforged, which keeps OpenGL matrices on NeoForge. */
		DIRECT,
		/** {@code 0.5 * (1 - z)}. */
		REVERSED
	}

	/** DLSS's modes, with NGX's {@code NVSDK_NGX_PerfQuality_Value}. AUTO takes the one Render Scale fits. */
	public enum Quality {
		AUTO(-1), ULTRA_PERFORMANCE(3), PERFORMANCE(0), BALANCED(1), QUALITY(2), DLAA(5);

		public final int ngx;

		Quality(int ngx) {
			this.ngx = ngx;
		}
	}

	/** Frames shown between two real ones, made by moving the last picture to where the camera is now. */
	public enum FrameGeneration {
		OFF(0), X2(1), X3(2), X4(3), X5(4), X6(5);

		public final int extra;

		FrameGeneration(int extra) {
			this.extra = extra;
		}
	}

	private static final String HEADER = String.join("\n",
			"Super Resolution VMR: NVIDIA DLSS on VulkanMod Reforged. Also on the DLSS page of VulkanMod Reforged's video settings.",
			"DLSS runs while VulkanMod Reforged's Render Scale is below 100 % (steps of 5 %; below 50 % only with DLSS on).",
			"  65-70 % suits DLSS Quality, 55-60 % Balanced, 45-50 % Performance, 33-40 % Ultra Performance.",
			"enabled: false leaves everything to VulkanMod Reforged.",
			"quality: auto, quality, balanced, performance, ultra_performance or dlaa.",
			"invertJitterX / invertJitterY / invertMotionY: if the picture shimmers or smears when the camera moves.",
			"packDepth / vanillaDepth: forward, direct or reversed; how depth is stored with and without a shader pack.",
			"packDepthUpsideDown: whether a shader pack's depth has its rows the other way round from the picture.",
			"handDepth: with a shader pack, depth below this is the hand, which moves with the camera.",
			"frameGeneration: off, x2, x3, x4, x5 or x6 (experimental, only while DLSS runs): frames between the real ones, the last picture",
			"  moved to where the camera is now and moving entities moved on by their own motion. The most frames generated:",
			"  fewer are, or none, where real frames keep up with the screen or a generated one would cost nearly as much.");

	private static volatile SrvmrConfig instance;

	public final boolean enabled;
	public final Quality quality;
	public final boolean jitter;
	public final boolean invertJitterX;
	public final boolean invertJitterY;
	public final boolean invertMotionY;
	public final DepthMode packDepth;
	public final DepthMode vanillaDepth;
	public final boolean packDepthUpsideDown;
	public final float handDepth;
	public final FrameGeneration frameGeneration;

	/** A copy to edit, for the settings page. */
	public static final class Draft {
		public boolean enabled;
		public Quality quality;
		public boolean jitter;
		public boolean invertJitterX;
		public boolean invertJitterY;
		public boolean invertMotionY;
		public DepthMode packDepth;
		public DepthMode vanillaDepth;
		public boolean packDepthUpsideDown;
		public float handDepth;
		public FrameGeneration frameGeneration;
	}

	private SrvmrConfig(Draft d) {
		this.enabled = d.enabled;
		this.quality = d.quality;
		this.jitter = d.jitter;
		this.invertJitterX = d.invertJitterX;
		this.invertJitterY = d.invertJitterY;
		this.invertMotionY = d.invertMotionY;
		this.packDepth = d.packDepth;
		this.vanillaDepth = d.vanillaDepth;
		this.packDepthUpsideDown = d.packDepthUpsideDown;
		this.handDepth = d.handDepth;
		this.frameGeneration = d.frameGeneration;
	}

	public static SrvmrConfig get() {
		SrvmrConfig config = instance;
		if (config == null) {
			synchronized (SrvmrConfig.class) {
				if (instance == null) instance = load();
				config = instance;
			}
		}
		return config;
	}

	public Draft draft() {
		Draft d = new Draft();
		d.enabled = this.enabled;
		d.quality = this.quality;
		d.jitter = this.jitter;
		d.invertJitterX = this.invertJitterX;
		d.invertJitterY = this.invertJitterY;
		d.invertMotionY = this.invertMotionY;
		d.packDepth = this.packDepth;
		d.vanillaDepth = this.vanillaDepth;
		d.packDepthUpsideDown = this.packDepthUpsideDown;
		d.handDepth = this.handDepth;
		d.frameGeneration = this.frameGeneration;
		return d;
	}

	/** Takes an edited draft as the configuration from now on, and writes it to the file. */
	public static synchronized void apply(Draft draft) {
		SrvmrConfig config = new SrvmrConfig(draft);
		instance = config;
		write(config);
	}

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("srvmr.properties");
	}

	private static SrvmrConfig load() {
		Path file = file();
		Properties p = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(reader);
			} catch (IOException e) {
				Srvmr.LOGGER.warn("[dlss] cannot read {}; using the defaults", file, e);
			}
		}
		Draft d = new Draft();
		d.enabled = bool(p, "enabled", true);
		d.quality = choice(p, "quality", Quality.AUTO, Quality.class);
		d.jitter = bool(p, "jitter", true);
		d.invertJitterX = bool(p, "invertJitterX", false);
		d.invertJitterY = bool(p, "invertJitterY", false);
		d.invertMotionY = bool(p, "invertMotionY", false);
		d.packDepth = choice(p, "packDepth", DepthMode.FORWARD, DepthMode.class);
		d.vanillaDepth = choice(p, "vanillaDepth", DepthMode.DIRECT, DepthMode.class);
		d.packDepthUpsideDown = bool(p, "packDepthUpsideDown", true);
		d.handDepth = number(p, "handDepth", 0.5625F);
		d.frameGeneration = choice(p, "frameGeneration", FrameGeneration.OFF, FrameGeneration.class);
		SrvmrConfig config = new SrvmrConfig(d);
		// Every key written back, so the file always shows what there is to set.
		if (!properties(config).equals(p)) write(config);
		return config;
	}

	private static Properties properties(SrvmrConfig config) {
		Properties out = new Properties();
		out.setProperty("enabled", Boolean.toString(config.enabled));
		out.setProperty("quality", config.quality.name().toLowerCase(Locale.ROOT));
		out.setProperty("jitter", Boolean.toString(config.jitter));
		out.setProperty("invertJitterX", Boolean.toString(config.invertJitterX));
		out.setProperty("invertJitterY", Boolean.toString(config.invertJitterY));
		out.setProperty("invertMotionY", Boolean.toString(config.invertMotionY));
		out.setProperty("packDepth", config.packDepth.name().toLowerCase(Locale.ROOT));
		out.setProperty("vanillaDepth", config.vanillaDepth.name().toLowerCase(Locale.ROOT));
		out.setProperty("packDepthUpsideDown", Boolean.toString(config.packDepthUpsideDown));
		out.setProperty("handDepth", Float.toString(config.handDepth));
		out.setProperty("frameGeneration", config.frameGeneration.name().toLowerCase(Locale.ROOT));
		return out;
	}

	private static void write(SrvmrConfig config) {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				properties(config).store(writer, HEADER);
			}
		} catch (IOException e) {
			Srvmr.LOGGER.warn("[dlss] cannot write {}", file, e);
		}
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		String value = p.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	private static float number(Properties p, String key, float fallback) {
		try {
			String value = p.getProperty(key);
			float parsed = value == null ? fallback : Float.parseFloat(value.trim());
			return Float.isFinite(parsed) ? parsed : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static <E extends Enum<E>> E choice(Properties p, String key, E fallback, Class<E> type) {
		String value = p.getProperty(key);
		if (value == null) return fallback;
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
