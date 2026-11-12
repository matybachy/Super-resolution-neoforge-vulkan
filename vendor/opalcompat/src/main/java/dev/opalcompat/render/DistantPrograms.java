package dev.opalcompat.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.PackValues;
import dev.vitrail.uniform.UniformBlock;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.compile.SpirvCompiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pack's programs for Distant Horizons' terrain: {@code dh_terrain}, {@code dh_water} and {@code dh_shadow}
 * for both halves from the light. Follows Vitrail's {@code render/DistantDraw} and Iris's {@code compat/dh}.
 * A pack with none of them leaves the far terrain undrawn, as there is nothing else here to draw it with.
 */
final class DistantPrograms implements AutoCloseable {

	enum Half {
		TERRAIN("distant", "dh_terrain", false, false),
		WATER("distant_water", "dh_water", true, false),
		SHADOW("distant_shadow", "dh_shadow", false, true),
		SHADOW_WATER("distant_shadow_water", "dh_shadow", true, true);

		final String element;
		final String program;
		final boolean water;
		final boolean light;

		Half(String element, String program, boolean water, boolean light) {
			this.element = element;
			this.program = program;
			this.water = water;
			this.light = light;
		}

		static Half of(boolean opaque, boolean light) {
			return light ? opaque ? SHADOW : SHADOW_WATER : opaque ? TERRAIN : WATER;
		}
	}

	/** @param pass the targets it writes; for the light's halves the shadow colours are asked for separately */
	record Program(PackProgram.Loaded loaded, PackPipeline pipeline, UniformBlock block, ChainPlan.Pass pass) {
	}

	private final Map<Half, Program> programs = new EnumMap<>(Half.class);
	private final List<PackPipeline> pipelines = new ArrayList<>();

	static DistantPrograms load(OpenedPack pack, PackProgram.Chain chain, PackValues values, SpirvCompiler compiler) {
		DistantPrograms distant = new DistantPrograms();
		List<PackProgram.GeometryElement> elements = new ArrayList<>();
		for (Half half : Half.values()) {
			elements.add(new PackProgram.GeometryElement(half.element, half.program, AlphaTest.OFF, VertexInputs.DISTANT, false));
		}
		PackProgram.Distant read;
		try {
			read = PackProgram.loadDistant(pack, chain.place(), elements);
		} catch (IOException | RuntimeException e) {
			OpalCompat.LOGGER.error("[pack] the far terrain programs could not be read; it stays undrawn", e);
			return distant;
		}
		for (Half half : Half.values()) {
			PackProgram.Loaded loaded = read.programs().get(half.element);
			if (loaded == null) continue;
			try {
				distant.add(half, loaded, chain, values, compiler);
			} catch (RuntimeException e) {
				OpalCompat.LOGGER.error("[pack] {} left out", half.program, e);
			}
		}
		return distant;
	}

	private void add(Half half, PackProgram.Loaded read, PackProgram.Chain chain, PackValues values, SpirvCompiler compiler) {
		String served = read.path().substring(read.path().lastIndexOf('/') + 1);
		// The water half of the picture alone comes after the deferred stage; the light's stand before all of it.
		boolean afterDeferred = half.water && !half.light;
		TargetSchedule schedule = chain.targets().schedule();
		Optional<TargetSchedule.Bound> step = half.light ? Optional.empty()
				: afterDeferred ? schedule.stepAfterDeferred(served) : schedule.step(served);
		PackProgram.Loaded loaded = read.rebind(chain.targets(), step);
		ChainPlan.Pass pass = null;
		if (!half.light) {
			pass = chain.chain().geometryOf(served, afterDeferred).orElse(null);
			if (pass == null || pass.attachments().isEmpty()) {
				OpalCompat.LOGGER.info("[pack] {} writes no target of this place; the far terrain stays undrawn", half.program);
				return;
			}
		}
		PackPipeline pipeline = new PackPipeline("distant:" + half.element + ":" + read.path(), loaded.program(), compiler, PackPipeline.Kind.DISTANT);
		this.pipelines.add(pipeline);
		UniformBlock block = PackRenderer.block(half.program, loaded, half.light ? values.shadowGeometryCatalog() : values.geometryCatalog());
		this.programs.put(half, new Program(loaded, pipeline, block, pass));
	}

	Program program(Half half) {
		return this.programs.get(half);
	}

	@Override
	public void close() {
		this.pipelines.forEach(PackPipeline::close);
		this.pipelines.clear();
		this.programs.clear();
	}
}
