package dev.matybachy.srvmr.ngx;

import dev.matybachy.srvmr.Srvmr;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * The bridge to NGX and NVIDIA's DLSS runtime, carried in the jar and written to {@code srvmr/natives/<checksum>} in the
 * game folder, where NGX is told to look for {@code nvngx_dlss.dll}. The folder is named after the files, so a newer jar
 * writes a folder of its own instead of replacing a library another game still running has loaded, and an unchanged
 * jar finds its files already there.
 */
public final class Natives {

	private static final String BRIDGE = "srvmr_ngx.dll";
	private static final String RUNTIME = "nvngx_dlss.dll";

	private static int state;
	private static Path directory;

	private Natives() {
	}

	public static Path dataDirectory() {
		return FMLPaths.GAMEDIR.get().resolve("srvmr");
	}

	/** Where the libraries are; meaningful once {@link #load} succeeded. */
	public static Path directory() {
		return directory != null ? directory : dataDirectory().resolve("natives");
	}

	/** Whether the bridge is loaded. Tried once; the log says why not. */
	public static synchronized boolean load() {
		if (state != 0) return state > 0;
		state = -1;
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
			Srvmr.LOGGER.info("[dlss] DLSS needs Windows on x64; this is {} on {}", os, arch);
			return false;
		}
		try {
			byte[] bridge = resource(BRIDGE);
			byte[] runtime = resource(RUNTIME);
			if (bridge == null || runtime == null) {
				Srvmr.LOGGER.warn("[dlss] this jar was built without {}; DLSS is off", bridge == null ? BRIDGE : RUNTIME);
				return false;
			}
			CRC32 checksum = new CRC32();
			checksum.update(bridge);
			checksum.update(runtime);
			Path dir = dataDirectory().resolve("natives").resolve(String.format(Locale.ROOT, "%08x", checksum.getValue()));
			Files.createDirectories(dir);
			write(dir.resolve(BRIDGE), bridge);
			write(dir.resolve(RUNTIME), runtime);
			System.load(dir.resolve(BRIDGE).toAbsolutePath().toString());
			directory = dir;
			state = 1;
			Srvmr.LOGGER.info("[dlss] native bridge loaded from {}", dir);
		} catch (IOException | UnsatisfiedLinkError | SecurityException e) {
			Srvmr.LOGGER.warn("[dlss] cannot load the native bridge; DLSS is off", e);
		}
		return state > 0;
	}

	private static byte[] resource(String name) throws IOException {
		try (InputStream in = Natives.class.getResourceAsStream("/natives/windows-x64/" + name)) {
			return in == null ? null : in.readAllBytes();
		}
	}

	private static void write(Path target, byte[] bytes) throws IOException {
		if (Files.isRegularFile(target) && Files.size(target) == bytes.length) return;
		Path part = target.resolveSibling(target.getFileName() + ".part");
		Files.write(part, bytes);
		Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
	}
}
