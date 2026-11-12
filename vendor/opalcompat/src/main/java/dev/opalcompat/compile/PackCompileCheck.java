package dev.opalcompat.compile;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.ProgramTranslator;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.load.PackLoader;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.target.ChainPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Translates every program of a pack with Vitrail's translator and compiles every unit to SPIR-V.
 * Failures are logged with the program, the stage and the line, and the translated text of a failing
 * unit is written next to the report so the line can be read in context.
 * <p>
 * Used out of game by {@link PackCompileTool}.
 */
public final class PackCompileCheck {

	private static final Logger LOGGER = LoggerFactory.getLogger("OpalCompat/compile");

	public record Failure(String program, String stage, String detail) {
	}

	public record Report(String pack, int programs, int units, int compiled, List<Failure> failures,
			long millis) {
		public boolean clean() {
			return failures.isEmpty() && compiled == units;
		}
	}

	private PackCompileCheck() {
	}

	public static Report check(Path packPath, SpirvCompiler compiler, Path dumpDir) throws IOException {
		long start = System.nanoTime();
		Map<String, Set<ProgramStage>> programs = new TreeMap<>();
		String packName;
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			packName = source.packName();
			ProgramSet set = ProgramSet.enumerate(source, DimensionSet.discover(source));
			for (ProgramSet.ProgramKey key : set.keys()) {
				String file = key.file();
				String path = file.substring(0, file.lastIndexOf('.'));
				programs.computeIfAbsent(path, p -> EnumSet.noneOf(ProgramStage.class)).add(key.stage());
			}
		}

		// Programs the pack's shaders.properties switches off under its default settings are not drawn,
		// and are not expected to compile either (BSL's shadowcomp needs MULTICOLORED_BLOCKLIGHT).
		Set<String> switchedOff = PackLoader.load(packPath).disabledPrograms();

		List<Failure> failures = new ArrayList<>();
		int units = 0;
		int compiled = 0;
		try (OpenedPack opened = OpenedPack.open(packPath, Map.of(), "")) {
			for (Map.Entry<String, Set<ProgramStage>> entry : programs.entrySet()) {
				String path = entry.getKey();
				if (switchedOff.contains(path)) {
					LOGGER.info("[compile] {} {}: switched off by shaders.properties", packName, path);
					continue;
				}
				// composite.csh and composite.fsh are one path and two programs.
				List<Set<ProgramStage>> programsAtPath = new ArrayList<>();
				EnumSet<ProgramStage> graphics = EnumSet.copyOf(entry.getValue());
				if (graphics.remove(ProgramStage.COMPUTE)) programsAtPath.add(EnumSet.of(ProgramStage.COMPUTE));
				if (!graphics.isEmpty()) programsAtPath.add(graphics);
				for (Set<ProgramStage> stages : programsAtPath) {
					Optional<ProgramTranslator.TranslatedProgram> program;
					// A dh_ program is only ever loaded with the far terrain there, and written for that.
					EngineDefines.Environment plain = EngineDefines.machine();
					if (path.substring(path.lastIndexOf('/') + 1).startsWith("dh_")) EngineDefines.machine(withDistant(plain));
					try {
						program = translate(packPath, opened, path, stages);
					} catch (Exception e) {
						failures.add(new Failure(path, "translate", e.toString()));
						LOGGER.error("[compile] {} {}: translation failed", packName, path, e);
						continue;
					} finally {
						EngineDefines.machine(plain);
					}
					if (program.isEmpty()) {
						LOGGER.info("[compile] {} {}: not served (stages {})", packName, path, stages);
						continue;
					}
					for (TranslatedUnit unit : program.get().stages().values()) {
						units++;
						String name = path + "." + unit.stage().extension();
						SpirvCompiler.Result result = compiler.compile(name, unit.text(), unit.stage());
						if (Boolean.getBoolean("opalcompat.dumpAll")) dump(dumpDir, packName, name, unit.text());
						if (result.ok()) {
							compiled++;
							result.diagnostics().forEach(d -> LOGGER.debug("[compile] {} {}: {}", packName, name, d));
							continue;
						}
						StringBuilder detail = new StringBuilder();
						result.diagnostics().forEach(d -> detail.append("\n    ").append(d));
						failures.add(new Failure(path, unit.stage().extension(), detail.toString()));
						LOGGER.error("[compile] {} {}: {} error(s){}", packName, name, result.errors(), detail);
						dump(dumpDir, packName, name, unit.text());
					}
				}
			}
			int[] counted = meshFamilies(opened, packName, compiler, dumpDir, failures);
			units += counted[0];
			compiled += counted[1];
		}
		Report report = new Report(packName, programs.size(), units, compiled, failures,
				(System.nanoTime() - start) / 1_000_000L);
		LOGGER.info("[compile] {}: {} programs, {}/{} units compiled, {} failures, {} ms", packName,
				report.programs(), compiled, units, failures.size(), report.millis());
		return report;
	}

	static EngineDefines.Environment withDistant(EngineDefines.Environment plain) {
		return new EngineDefines.Environment(plain.mcVersion(), plain.os(), plain.vendorName(), plain.rendererName(),
				plain.mipmapLevel(), true, plain.biomes(), plain.biomeCategories(), plain.textureFormat(), plain.bufferBlending());
	}

	/**
	 * The programs the game's own draws ask for, as the renderer loads them: by name through the fallback
	 * tree and with the vertex inputs of their family ({@code render/Families}, which cannot be read here
	 * because it needs the game's vertex formats). Nothing else compiles these before the first frame that
	 * draws one, so this is what stands between a bad translation and a family missing from the picture.
	 * Also holds each to what a pipeline can feed: an input neither a vanilla format nor the renderer's
	 * constants answer stops {@code PackPipeline} from being built.
	 *
	 * @return units found and units compiled
	 */
	private static int[] meshFamilies(OpenedPack opened, String packName, SpirvCompiler compiler, Path dumpDir,
			List<Failure> failures) {
		int units = 0;
		int compiled = 0;
		String place;
		try {
			place = PackProgram.loadChain(opened, "world0", ChainFilter.ALL, ChainPlan.Families.DEFAULT).place();
		} catch (IOException | RuntimeException e) {
			failures.add(new Failure("(chain)", "plan", e.toString()));
			LOGGER.error("[compile] {}: the chain could not be planned", packName, e);
			return new int[] {0, 0};
		}
		// Graphics shadow composites as the renderer loads them: one that fails in its constructor costs the
		// whole pack, and it is what tells whether a switched off one (BSL's) stays off.
		try {
			for (Map.Entry<String, PackProgram.Loaded> entry : PackProgram.loadShadowComposites(opened, place).entrySet()) {
				for (TranslatedUnit unit : entry.getValue().program().stages().values()) {
					units++;
					String name = entry.getKey() + "." + unit.stage().extension();
					SpirvCompiler.Result result = compiler.compile(name, unit.text(), unit.stage());
					if (result.ok()) {
						compiled++;
					} else {
						StringBuilder detail = new StringBuilder();
						result.diagnostics().forEach(d -> detail.append("\n    ").append(d));
						failures.add(new Failure(entry.getKey(), unit.stage().extension(), detail.toString()));
						LOGGER.error("[compile] {} {}: {} error(s){}", packName, name, result.errors(), detail);
						dump(dumpDir, packName, name, unit.text());
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			failures.add(new Failure("shadowcomp", "translate", e.toString()));
			LOGGER.error("[compile] {}: shadow composites failed", packName, e);
		}
		for (PackProgram.GeometryElement element : meshElements()) {
			String label = element.program() + "[" + element.inputs() + " " + String.join(",", element.bound()) + "]";
			PackProgram.Loaded loaded;
			try {
				loaded = PackProgram.loadGeometry(opened, place, List.of(element)).get(element.element());
			} catch (IOException | RuntimeException e) {
				failures.add(new Failure(label, "translate", e.toString()));
				LOGGER.error("[compile] {} {}: translation failed", packName, label, e);
				continue;
			}
			if (loaded == null) continue;
			for (TranslatedUnit unit : loaded.program().stages().values()) {
				units++;
				String name = label + "." + unit.stage().extension();
				SpirvCompiler.Result result = compiler.compile(name, unit.text(), unit.stage());
				if (!result.ok()) {
					StringBuilder detail = new StringBuilder();
					result.diagnostics().forEach(d -> detail.append("\n    ").append(d));
					failures.add(new Failure(label, unit.stage().extension(), detail.toString()));
					LOGGER.error("[compile] {} {}: {} error(s){}", packName, name, result.errors(), detail);
					dump(dumpDir, packName, name, unit.text());
					continue;
				}
				compiled++;
				if (unit.stage() != ProgramStage.VERTEX) continue;
				for (String input : new SpirvModule(result.spirv()).inputs().keySet()) {
					if (!FEEDABLE_INPUTS.contains(input)) {
						failures.add(new Failure(label, "vertex input", input + " is answered by nothing"));
						LOGGER.error("[compile] {} {}: vertex input {} is answered by nothing", packName, name, input);
					}
				}
			}
		}
		return new int[] {units, compiled};
	}

	/** What the elements of a vanilla format or {@code PackPipeline.Constants} can answer. */
	private static final Set<String> FEEDABLE_INPUTS = Set.of("Position", "Color", "UV0", "UV1", "UV2", "Normal",
			"EntityIds", "MidTexCoord", "Tangent", "LineWidth");

	private static List<PackProgram.GeometryElement> meshElements() {
		List<PackProgram.GeometryElement> elements = new ArrayList<>();
		List<String> glyph = List.of("Position", "Color", "UV0", "UV2");
		for (String program : List.of("gbuffers_hand", "gbuffers_block_translucent", "gbuffers_entities_translucent")) {
			elements.add(new PackProgram.GeometryElement("mesh", program, AlphaTest.NON_ZERO, VertexInputs.GLYPH, glyph, false));
		}
		elements.add(same("gbuffers_line", AlphaTest.OFF, VertexInputs.LINES));
		elements.add(same("gbuffers_armor_glint", AlphaTest.NON_ZERO, VertexInputs.GLINT));
		elements.add(same("gbuffers_damagedblock", AlphaTest.ONE_TENTH, VertexInputs.CRUMBLING));
		for (String program : List.of("gbuffers_weather", "gbuffers_particles", "gbuffers_particles_translucent")) {
			elements.add(same(program, AlphaTest.ONE_TENTH, VertexInputs.PARTICLE));
		}
		elements.add(new PackProgram.GeometryElement("mesh", "gbuffers_skybasic", AlphaTest.NON_ZERO, VertexInputs.SKY,
				List.of("Position"), false));
		elements.add(new PackProgram.GeometryElement("mesh", "gbuffers_skybasic", AlphaTest.NON_ZERO, VertexInputs.SKY,
				List.of("Position", "Color"), false));
		elements.add(new PackProgram.GeometryElement("mesh", "gbuffers_skytextured", AlphaTest.OFF, VertexInputs.SKY,
				List.of("Position", "UV0"), false));
		elements.add(new PackProgram.GeometryElement("mesh", "gbuffers_skytextured", AlphaTest.OFF, VertexInputs.SKY,
				List.of("Position", "UV0", "Color"), false));
		for (String program : List.of("gbuffers_basic", "gbuffers_textured", "gbuffers_clouds", "gbuffers_lightning",
				"gbuffers_beaconbeam", "gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_spidereyes",
				"gbuffers_block", "gbuffers_block_translucent", "gbuffers_hand", "gbuffers_hand_water", "shadow",
				"shadow_entities", "shadow_block", "shadow_cutout", "shadow_water", "shadow_lightning")) {
			elements.add(same(program, AlphaTest.ONE_TENTH, VertexInputs.ENTITY));
		}
		return elements;
	}

	private static PackProgram.GeometryElement same(String program, AlphaTest alpha, VertexInputs inputs) {
		return new PackProgram.GeometryElement("mesh", program, alpha, inputs, inputs.elements(), false);
	}

	private static Optional<ProgramTranslator.TranslatedProgram> translate(Path packPath, OpenedPack opened,
			String path, Set<ProgramStage> stages) throws IOException {
		if (stages.contains(ProgramStage.COMPUTE)) {
			return PackProgram.loadCompute(opened, path).map(c -> c.loaded().program());
		}
		String name = path.substring(path.lastIndexOf('/') + 1);
		return PackProgram.load(packPath, path, inputsFor(name), Map.of(), "")
				.map(PackProgram.Loaded::program);
	}

	/** The vertex format each family is drawn with, as far as compiling it is concerned. */
	static VertexInputs inputsFor(String name) {
		if (name.matches("(composite|deferred|prepare|begin|shadowcomp|setup)\\d*|final")) return VertexInputs.FULLSCREEN;
		if (name.startsWith("dh_")) return VertexInputs.DISTANT;
		if (name.startsWith("gbuffers_terrain") || name.equals("gbuffers_water") || name.startsWith("shadow")) {
			return VertexInputs.TERRAIN;
		}
		if (name.startsWith("gbuffers_sky")) return VertexInputs.SKY;
		// 1.21.1 draws clouds as a plain mesh; Vitrail's CLOUDS prologue is the 26.x cell buffer.
		if (name.equals("gbuffers_clouds")) return VertexInputs.WORLD;
		if (name.equals("gbuffers_armor_glint")) return VertexInputs.GLINT;
		if (name.equals("gbuffers_damagedblock")) return VertexInputs.CRUMBLING;
		if (name.equals("gbuffers_line")) return VertexInputs.LINES;
		if (name.startsWith("gbuffers_particles") || name.equals("gbuffers_weather")
				|| name.startsWith("gbuffers_textured")) return VertexInputs.PARTICLE;
		if (name.startsWith("gbuffers_entities") || name.startsWith("gbuffers_hand")
				|| name.startsWith("gbuffers_block") || name.equals("gbuffers_beaconbeam")
				|| name.equals("gbuffers_spidereyes") || name.equals("gbuffers_lightning")) return VertexInputs.ENTITY;
		return VertexInputs.WORLD;
	}

	private static void dump(Path dumpDir, String pack, String name, String text) {
		if (dumpDir == null) return;
		try {
			Path file = dumpDir.resolve(pack.replaceAll("[^A-Za-z0-9._-]", "_")).resolve(name.replace('/', '_') + ".glsl");
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			LOGGER.warn("Cannot dump {}", name, e);
		}
	}
}
