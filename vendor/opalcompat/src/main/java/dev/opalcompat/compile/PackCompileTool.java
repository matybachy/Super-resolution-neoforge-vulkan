package dev.opalcompat.compile;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.platform.VitrailPlatform;
import dev.opalcompat.GameVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Out-of-game check: {@code ./gradlew checkPacks [-Ppacks=a,b]}. Translates and compiles every program
 * of the packs in {@code run/shaderpacks} and exits non-zero if any unit fails.
 */
public final class PackCompileTool {

	public static void main(String[] args) throws Exception {
		Path gameDir = Path.of(args.length > 0 ? args[0] : "run").toAbsolutePath();
		Path dump = gameDir.resolve("opalcompat-debug").resolve("compile");
		Vitrail.initClient(new ToolPlatform(gameDir));
		EngineDefines.machine(EngineDefines.Environment.of(GameVersion.MC_VERSION));
		// -PdhVariant=true: every program as it reads with the far terrain there, DISTANT_HORIZONS defined.
		if (Boolean.getBoolean("opalcompat.dhVariant")) EngineDefines.machine(PackCompileCheck.withDistant(EngineDefines.machine()));

		List<Path> packs = new ArrayList<>();
		Path dir = gameDir.resolve("shaderpacks");
		if (args.length > 1 && !args[1].isBlank()) {
			for (String name : args[1].split(",")) packs.add(dir.resolve(name.trim()));
		} else {
			try (Stream<Path> entries = Files.list(dir)) {
				entries.sorted().forEach(packs::add);
			}
		}

		int failed = 0;
		try (SpirvCompiler compiler = new SpirvCompiler()) {
			for (Path pack : packs) {
				PackCompileCheck.Report report = PackCompileCheck.check(pack, compiler, dump);
				if (!report.clean()) failed++;
			}
		}
		System.out.println(failed == 0 ? "ALL PACKS COMPILED" : failed + " PACK(S) WITH FAILURES, see " + dump);
		System.exit(failed == 0 ? 0 : 1);
	}

	private record ToolPlatform(Path gameDirectory) implements VitrailPlatform {
		public String loaderName() { return "tool"; }
		public String loaderVersion() { return "0"; }
		public String modVersion() { return "0.1.0-tool"; }
		public String minecraftVersion() { return "1.21.1"; }
		public boolean isModLoaded(String modId) { return false; }
	}
}
