package dev.opalcompat.pack;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.load.LoadedPack;
import dev.vitrail.pack.load.PackLoader;
import dev.vitrail.pack.load.PackReport;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.PackOption;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.settings.SettingsFile;
import dev.opalcompat.OpalCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Which pack is selected, with which settings, and reading it with the loader taken from Vitrail.
 * <p>
 * {@code config/opalcompat.properties}: {@code shaderPack=<name>}. Settings live in
 * {@code shaderpacks/<name>.txt}, the file Iris reads, one per pack. The system properties
 * {@code opalcompat.pack}, {@code opalcompat.profile} and {@code opalcompat.options} ({@code A=b,C=d}) go over
 * them (the autotest sets them) until a pack is chosen in the game. An empty pack name means no pack.
 */
public final class ShaderPacks {

	private static final String CONFIG = "opalcompat.properties";
	private static final String KEY = "shaderPack";
	private static final String LAST_KEY = "lastShaderPack";

	/** Asked for every frame; the file is read once. */
	private static String selected;
	private static String missingNamed;
	private static final long LOOK_EVERY_NANOS = 1_000_000_000L;
	private static String lookedFor;
	private static long lookedAt;
	private static Optional<Path> found = Optional.empty();

	private ShaderPacks() {
	}

	public static Path directory() {
		return PackLoader.directory(Vitrail.platform().gameDirectory());
	}

	private static Properties config() {
		Properties properties = new Properties();
		Path file = configFile();
		if (Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				properties.load(in);
			} catch (IOException e) {
				OpalCompat.LOGGER.warn("Cannot read {}", file, e);
			}
		}
		return properties;
	}

	private static void store(Properties properties) throws IOException {
		Files.createDirectories(configFile().getParent());
		try (OutputStream out = Files.newOutputStream(configFile())) {
			properties.store(out, "OpalCompat");
		}
	}

	public static String selectedName() {
		if (selected == null) {
			String forced = System.getProperty("opalcompat.pack");
			selected = forced != null ? forced.trim() : config().getProperty(KEY, "").trim();
		}
		return selected;
	}

	public static void select(String name) throws IOException {
		Properties properties = config();
		properties.setProperty(KEY, name);
		if (!name.isEmpty()) properties.setProperty(LAST_KEY, name);
		store(properties);
		selected = name;
	}

	/** A switch of this mod's own in the same file. */
	public static boolean flag(String key, boolean fallback) {
		String value = config().getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	public static void setFlag(String key, boolean value) throws IOException {
		Properties properties = config();
		properties.setProperty(key, Boolean.toString(value));
		store(properties);
	}

	/** The pack that was on last, for when shaders go back on. */
	public static String lastName() {
		return config().getProperty(LAST_KEY, "").trim();
	}

	public static String profile() {
		return System.getProperty("opalcompat.profile", "").trim();
	}

	/** The settings changed from the selected pack's defaults. */
	public static Map<String, OptionValue> options() {
		Map<String, OptionValue> options = new LinkedHashMap<>();
		String name = selectedName();
		if (!name.isEmpty()) {
			Path file = SettingsFile.of(Vitrail.platform().gameDirectory(), name);
			try {
				SettingsFile.read(file).values().forEach((option, value) -> options.put(option, OptionValue.parse(value)));
			} catch (IOException e) {
				OpalCompat.LOGGER.warn("Cannot read {}, the pack draws with its own defaults", file, e);
			}
		}
		String forced = System.getProperty("opalcompat.options", "");
		for (String pair : forced.split(",")) {
			int at = pair.indexOf('=');
			if (at > 0) {
				options.put(pair.substring(0, at).trim(), OptionValue.parse(pair.substring(at + 1)));
			}
		}
		return options;
	}

	/** The selected pack on disk, if one is selected and present. */
	public static Optional<Path> selectedPath() {
		String name = selectedName();
		if (name.isEmpty()) return Optional.empty();
		// Asked at the head of every frame: the disk is, once a second.
		long now = System.nanoTime();
		if (!name.equals(lookedFor) || now - lookedAt > LOOK_EVERY_NANOS) {
			lookedFor = name;
			lookedAt = now;
			Path path = directory().resolve(name);
			if (Files.exists(path)) {
				found = Optional.of(path);
			} else {
				if (!name.equals(missingNamed)) OpalCompat.LOGGER.warn("Selected shader pack {} is not in {}", name, directory());
				missingNamed = name;
				found = Optional.empty();
			}
		}
		return found;
	}

	/** Reads one pack and writes Vitrail's report plus the full program and option lists. */
	public static Optional<LoadedPack> loadAndReport(Path path) {
		try {
			LoadedPack pack = PackLoader.load(path);
			PackReport.log(pack);
			logPrograms(pack);
			logOptions(pack);
			return Optional.of(pack);
		} catch (IOException | RuntimeException e) {
			PackReport.couldNotRead(path.getFileName().toString(), e);
			return Optional.empty();
		}
	}

	/** Every pack in {@code shaderpacks/}, for {@code -Dopalcompat.reportAll=true}. */
	public static void reportAll() {
		try {
			List<Path> candidates = PackLoader.candidates(Vitrail.platform().gameDirectory());
			OpalCompat.LOGGER.info("[pack] {} candidates in {}", candidates.size(), directory());
			candidates.forEach(ShaderPacks::loadAndReport);
		} catch (IOException e) {
			OpalCompat.LOGGER.warn("Cannot list {}", directory(), e);
		}
	}

	private static void logPrograms(LoadedPack pack) {
		Map<String, Map<String, List<String>>> byPlace = new TreeMap<>();
		for (ProgramSet.ProgramKey key : pack.programs().keys()) {
			String place = key.dimension().isEmpty() ? "(root)" : key.dimension();
			String name = key.name().isCompute() ? key.name().baseName() + "_" + key.name().computeSuffix()
					: key.name().baseName();
			byPlace.computeIfAbsent(place, p -> new TreeMap<>())
					.computeIfAbsent(name, n -> new ArrayList<>())
					.add(key.stage().name().toLowerCase(Locale.ROOT));
		}
		byPlace.forEach((place, programs) -> OpalCompat.LOGGER.info("[pack] programs {} ({}): {}", place,
				programs.size(), programs.entrySet().stream()
						.map(e -> e.getKey() + e.getValue().stream().sorted().collect(Collectors.joining(",", "[", "]")))
						.collect(Collectors.joining(" "))));
	}

	private static void logOptions(LoadedPack pack) {
		List<PackOption> options = pack.options().all().stream()
				.sorted(Comparator.comparing(PackOption::name)).toList();
		StringBuilder line = new StringBuilder();
		int written = 0;
		for (PackOption option : options) {
			String value = switch (option.kind()) {
				case TOGGLE -> option.defaultOff() ? "off" : "on";
				default -> option.defaultText();
			};
			line.append(option.name()).append('=').append(value);
			if (option.hasValueList()) line.append('{').append(option.values().size()).append('}');
			line.append(' ');
			if (++written % 12 == 0) {
				OpalCompat.LOGGER.info("[pack] options {}", line.toString().trim());
				line.setLength(0);
			}
		}
		if (!line.isEmpty()) OpalCompat.LOGGER.info("[pack] options {}", line.toString().trim());
		OpalCompat.LOGGER.info("[pack] {}: {} programs, {} options", pack.packName(), pack.programs().count(),
				options.size());
	}

	private static Path configFile() {
		return Vitrail.platform().gameDirectory().resolve("config").resolve(CONFIG);
	}
}
