package dev.opalcompat.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.model.ProgramNames;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.PackValues;
import dev.vitrail.uniform.UniformBlock;
import dev.vitrail.uniform.UniformCatalog;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.compile.SpirvCompiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack's compute programs by the moment each runs at, as Iris 1.21.1 places them
 * ({@code pipeline/IrisRenderingPipeline}, {@code CompositeRenderer}, {@code FinalPassRenderer},
 * {@code shadows/ShadowCompositeRenderer}). LGPL-3.0, see NOTICE.
 * <ul>
 * <li>{@code setup}: once for every size of the targets, one work group per axis unless it says more;</li>
 * <li>{@code shadow}: at the head of the frame, over the size of the map;</li>
 * <li>{@code shadowcompN}: before that pass, over the size of the screen;</li>
 * <li>{@code beginN}, {@code prepareN}, {@code deferredN}, {@code compositeN}, {@code final}: before that
 * pass, or at its moment if the place draws no pass for it.</li>
 * </ul>
 * One that fails to compile is left out with a word in the log, not the pack with it: what it would add to
 * the picture is missing, and the picture is still there.
 */
final class PackComputes implements AutoCloseable {

	/** @param indirect {buffer index, byte offset} from {@code indirect.<stem>}, which then gives the counts; or null */
	record Dispatch(String name, PackProgram.Compute compute, PackPipeline pipeline, UniformBlock block, long[] indirect) {
	}

	/** Computes hanging off a program this place draws no pass for. */
	record Alone(String program, TargetSchedule.Bound step, List<Dispatch> dispatches) {
	}

	private static final Pattern SHADOW_COLOUR = Pattern.compile("shadowcolor(?:img)?(\\d?)");

	private final List<Dispatch> setup = new ArrayList<>();
	private final List<Dispatch> shadow = new ArrayList<>();
	private final Map<String, List<Dispatch>> shadowComposites = new HashMap<>();
	private final Map<String, List<Dispatch>> chained = new HashMap<>();
	private final List<Alone> alone = new ArrayList<>();
	private final List<PackPipeline> pipelines = new ArrayList<>();
	private final Set<Dispatch> failed = new HashSet<>();

	static PackComputes load(OpenedPack opened, PackProgram.Chain chain, PackValues values, SpirvCompiler compiler) {
		PackComputes computes = new PackComputes();
		ChainPlan plan = chain.chain();
		Set<String> running = new HashSet<>();
		plan.passes().forEach(pass -> running.add(pass.program()));
		plan.last().ifPresent(pass -> running.add(pass.program()));
		Set<String> passing = Set.copyOf(chain.targets().passing());
		Map<String, long[]> pointers = opened.properties().indirectPointers(opened.settings().globalDefines(opened.options()));
		Map<String, List<Dispatch>> alone = new HashMap<>();

		for (String name : chain.targets().computes()) {
			Optional<ProgramNames.ProgramName> parsed = ProgramNames.parse(name);
			if (parsed.isEmpty()) continue;
			String family = parsed.get().family();
			String base = parsed.get().baseName();
			boolean light = family.equals("shadow") || ProgramNames.shadowComposite(family);
			// Iris reads setup's programs by number alone (ProgramSet, readProgramArray).
			if (family.equals("setup") && parsed.get().isCompute()) continue;
			boolean standalone = passing.contains(base);
			if (!light && !family.equals("setup") && !standalone && !running.contains(base)) {
				OpalCompat.LOGGER.warn("[pack] compute {} is not dispatched: nothing runs {}", name, base);
				continue;
			}
			Dispatch dispatch = computes.read(opened, chain.place(), name, light ? values.shadowGeometryCatalog() : values.catalog(),
					compiler, pointers.get(name));
			if (dispatch == null) continue;
			switch (family) {
				case "setup" -> computes.setup.add(dispatch);
				case "shadow" -> computes.shadow.add(dispatch);
				case "shadowcomp" -> computes.shadowComposites.computeIfAbsent(base, unused -> new ArrayList<>()).add(dispatch);
				default -> (standalone ? alone : computes.chained).computeIfAbsent(base, unused -> new ArrayList<>()).add(dispatch);
			}
		}

		Comparator<Dispatch> letters = Comparator.comparing(dispatch -> ProgramNames.computeLetter(dispatch.name()));
		computes.setup.sort(Comparator.comparing(Dispatch::name, ProgramNames.frameOrder()));
		computes.shadow.sort(letters);
		computes.shadowComposites.values().forEach(list -> list.sort(letters));
		computes.chained.values().forEach(list -> list.sort(letters));
		alone.values().forEach(list -> list.sort(letters));
		alone.keySet().stream().sorted(ProgramNames.frameOrder()).forEach(program -> {
			TargetSchedule.Bound step = chain.targets().schedule().passing(program).orElse(null);
			// A plan disagreeing with itself rather than anything a pack can do: with no halves it would bind nothing.
			if (step == null) {
				OpalCompat.LOGGER.warn("[pack] compute of {} is not dispatched: the schedule gives it no halves", program);
				return;
			}
			computes.alone.add(new Alone(program, step, alone.get(program)));
		});
		return computes;
	}

	private Dispatch read(OpenedPack opened, String place, String name, UniformCatalog catalog, SpirvCompiler compiler, long[] indirect) {
		String path = place.isEmpty() ? name : place + "/" + name;
		try {
			PackProgram.Compute compute = PackProgram.loadCompute(opened, path).orElse(null);
			if (compute == null) return null;
			if (!compute.unresolved().isEmpty()) {
				OpalCompat.LOGGER.info("[pack] compute {} reads no number for {}, that directive counts as absent", path, compute.unresolved());
			}
			// A local size that is not a number would have to be invented, and a guess of one is a group per pixel.
			if (!compute.sized() && indirect == null) {
				OpalCompat.LOGGER.warn("[pack] compute {} is not dispatched: its work group count cannot be read", path);
				return null;
			}
			PackPipeline pipeline = new PackPipeline(path, compute.loaded().program(), compiler, PackPipeline.Kind.COMPUTE);
			this.pipelines.add(pipeline);
			return new Dispatch(name, compute, pipeline, PackRenderer.block(path, compute.loaded(), catalog), indirect);
		} catch (IOException | RuntimeException e) {
			OpalCompat.LOGGER.error("[pack] compute {} left out", path, e);
			return null;
		}
	}

	List<Dispatch> setup() {
		return this.setup;
	}

	List<Dispatch> shadow() {
		return this.shadow;
	}

	List<Dispatch> shadowComposite(String pass) {
		return this.shadowComposites.getOrDefault(pass, List.of());
	}

	Set<String> shadowCompositePasses() {
		return this.shadowComposites.keySet();
	}

	List<Dispatch> before(String pass) {
		return this.chained.getOrDefault(pass, List.of());
	}

	List<Alone> alone() {
		return this.alone;
	}

	boolean failed(Dispatch dispatch) {
		return this.failed.contains(dispatch);
	}

	/** What it recorded before the failure is harmless, layouts and barriers; it is not tried again. */
	void fail(Dispatch dispatch, RuntimeException e) {
		this.failed.add(dispatch);
		OpalCompat.LOGGER.error("[pack] compute {} failed; it stays out of the frame", dispatch.name(), e);
	}

	/** The shadow colours a compute names, which a fragment stage of the place may never mention. */
	Set<Integer> shadowColours() {
		Set<Integer> named = new HashSet<>();
		for (PackPipeline pipeline : this.pipelines) {
			for (PackPipeline.Slot slot : pipeline.slots) {
				Matcher matcher = SHADOW_COLOUR.matcher(slot.name());
				if (matcher.matches()) named.add(matcher.group(1).isEmpty() ? 0 : Integer.parseInt(matcher.group(1)));
			}
		}
		return named;
	}

	/** {@code shadowcolorimgN}, or -1. */
	static int shadowColourImage(String name) {
		if (!name.startsWith("shadowcolorimg")) return -1;
		Matcher matcher = SHADOW_COLOUR.matcher(name);
		return matcher.matches() && !matcher.group(1).isEmpty() ? Integer.parseInt(matcher.group(1)) : -1;
	}

	@Override
	public void close() {
		this.pipelines.forEach(PackPipeline::close);
		this.pipelines.clear();
	}
}
