package dev.opalcompat.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.ProgramTranslator;
import dev.vitrail.pack.model.TargetSize;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.PackValues;
import dev.vitrail.uniform.UniformBlock;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.compile.SpirvCompiler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The pack's programs for mesh draws, translated and compiled the first time a family asks for one. A
 * program that cannot be served is logged once and its family handed back to VulkanMod; it does not
 * take the rest of the pack with it.
 * <p>
 * OpalCompat: all of that, and the pipelines of these programs, happens on a thread of their own. A program of a large
 * pack takes a few hundred milliseconds to translate and compile, and the driver up to a second more for a pipeline it
 * has not seen; on the render thread each was a frame that long, the first time a mob, a chest or a particle came into
 * view. Until a program is there, what asks for it is left out of the picture. That thread is the only one reading the
 * pack once it is loaded: {@code ShaderPackSource} keeps memos that belong to one thread.
 */
final class MeshPrograms implements AutoCloseable {
	private record Key(Families.Piece piece, boolean afterDeferred) {
	}

	private record Compiled(PackPipeline pipeline, UniformBlock uniforms) {
	}

	/**
	 * @param pass the targets it writes; for a shadow program the shadow colours are asked for separately
	 */
	record Program(PackProgram.Loaded loaded, PackPipeline pipeline, UniformBlock uniforms, ChainPlan.Pass pass,
			String served, boolean shadow) {
	}

	private final OpenedPack pack;
	private final PackProgram.Chain chain;
	private final PackValues values;
	private final SpirvCompiler compiler;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "OpalCompat shaders");
		thread.setDaemon(true);
		return thread;
	});
	/** Render thread only. */
	private final Map<Key, Optional<Program>> programs = new HashMap<>();
	private final Map<Key, Future<Optional<Program>>> preparing = new HashMap<>();
	/** {@link #worker} only, until {@link #close}. */
	private final Map<Families.Piece, Optional<PackProgram.Loaded>> loaded = new HashMap<>();
	private final Map<ProgramTranslator.TranslatedProgram, Compiled> compiled = new IdentityHashMap<>();

	MeshPrograms(OpenedPack pack, PackProgram.Chain chain, PackValues values, SpirvCompiler compiler) {
		this.pack = pack;
		this.chain = chain;
		this.values = values;
		this.compiler = compiler;
	}

	/**
	 * On the render thread.
	 *
	 * @return the program, empty where the pack does not serve this family, or null while it is being prepared
	 */
	Optional<Program> resolve(Families.Piece piece, boolean afterDeferred) {
		Key key = new Key(piece, afterDeferred);
		Optional<Program> ready = this.programs.get(key);
		if (ready != null) return ready;
		Future<Optional<Program>> future = this.preparing.get(key);
		if (future == null) {
			try {
				this.preparing.put(key, this.worker.submit(() -> load(key)));
			} catch (RejectedExecutionException e) {
				// Being closed.
			}
			return null;
		}
		if (!future.isDone()) return null;
		this.preparing.remove(key);
		Optional<Program> result;
		try {
			result = future.get();
		} catch (ExecutionException | CancellationException e) {
			OpalCompat.LOGGER.error("[pack] {} could not be loaded; VulkanMod draws it", piece.program(), e);
			result = Optional.empty();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		this.programs.put(key, result);
		return result;
	}

	/** On {@link #worker}. */
	private Optional<Program> load(Key key) {
		Families.Piece piece = key.piece();
		String name = piece.program() + "|" + piece.inputs();
		try {
			Optional<PackProgram.Loaded> translated = this.loaded.computeIfAbsent(piece, this::translate);
			if (translated.isEmpty()) {
				OpalCompat.LOGGER.info("[pack] {}: no program of the pack serves it; VulkanMod draws it", name);
				return Optional.empty();
			}
			boolean shadow = piece.program().startsWith("shadow");
			String path = translated.get().path();
			String served = path.substring(path.lastIndexOf('/') + 1);
			TargetSchedule schedule = this.chain.targets().schedule();
			Optional<TargetSchedule.Bound> step = shadow ? Optional.empty()
					: key.afterDeferred() ? schedule.stepAfterDeferred(served) : schedule.step(served);
			PackProgram.Loaded bound = translated.get().rebind(this.chain.targets(), step);
			Compiled compiled = this.compiled.get(bound.program());
			if (compiled == null) {
				PackPipeline pipeline = new PackPipeline("mesh:" + path, bound.program(), this.compiler, PackPipeline.Kind.MESH,
						this.worker);
				UniformBlock uniforms = PackRenderer.block("mesh:" + path, bound,
						shadow ? this.values.shadowGeometryCatalog() : this.values.geometryCatalog());
				compiled = new Compiled(pipeline, uniforms);
				this.compiled.put(bound.program(), compiled);
			}
			ChainPlan.Pass pass = shadow ? null : attachments(served, key.afterDeferred(), step);
			if (!shadow && pass == null) {
				OpalCompat.LOGGER.info("[pack] {}: {} writes no target of this place; VulkanMod draws it", name, served);
				return Optional.empty();
			}
			return Optional.of(new Program(bound, compiled.pipeline(), compiled.uniforms(), pass, served, shadow));
		} catch (RuntimeException e) {
			OpalCompat.LOGGER.error("[pack] {} could not be loaded; VulkanMod draws it", name, e);
			return Optional.empty();
		}
	}

	private Optional<PackProgram.Loaded> translate(Families.Piece piece) {
		PackProgram.GeometryElement element = new PackProgram.GeometryElement("mesh", piece.program(),
				piece.alpha(), piece.inputs(), piece.bound(), false);
		try {
			return Optional.ofNullable(PackProgram.loadGeometry(this.pack, this.chain.place(), List.of(element)).get("mesh"));
		} catch (IOException e) {
			OpalCompat.LOGGER.error("[pack] {} could not be read", piece.program(), e);
			return Optional.empty();
		}
	}

	/**
	 * The plan answers for the names its own families ask about, and {@code gbuffers_beaconbeam} or
	 * {@code gbuffers_lightning} are not among them, nor is every name on both sides of the deferred
	 * stage. So the same walk once more for those ({@code ChainPlan.geometryOf}, which is private), from
	 * what the target plan knows of every geometry program. Without it those draws would land in
	 * VulkanMod's own target, which the final pass overwrites. Null if nothing of it can be written.
	 */
	private ChainPlan.Pass attachments(String served, boolean afterDeferred, Optional<TargetSchedule.Bound> step) {
		ChainPlan plan = this.chain.chain();
		Optional<ChainPlan.Pass> asked = plan.geometryOf(served, afterDeferred);
		if (asked.isPresent() && !asked.get().attachments().isEmpty()) return asked.get();
		TargetPlan targets = this.chain.targets();
		List<ChainPlan.Attachment> attachments = new ArrayList<>();
		TargetSize size = null;
		for (int index : targets.writes(served)) {
			// Past a target this place has not got, the outputs would no longer line up with their ranks.
			if (!targets.allocated().contains(index)) break;
			TargetSize here = targets.directives().size(index);
			if (size != null && !size.equals(here)) break;
			size = here;
			attachments.add(new ChainPlan.Attachment(index,
					step.map(bound -> bound.write(index)).orElse(TargetSchedule.Side.MAIN)));
		}
		if (attachments.isEmpty()) {
			if (!targets.allocated().contains(0)) return null;
			attachments.add(new ChainPlan.Attachment(0, step.map(bound -> bound.write(0)).orElse(TargetSchedule.Side.MAIN)));
			size = targets.directives().size(0);
		}
		return new ChainPlan.Pass(served, attachments, size, false);
	}

	/** Before the pack is: the worker finishes what it is doing and nothing after that. */
	@Override
	public void close() {
		this.worker.shutdownNow();
		try {
			if (!this.worker.awaitTermination(30, TimeUnit.SECONDS)) {
				OpalCompat.LOGGER.warn("[pack] the shader thread is still busy; its programs are left behind");
				return;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		this.compiled.values().forEach(compiled -> compiled.pipeline().close());
		this.compiled.clear();
		this.programs.clear();
		this.preparing.clear();
		this.loaded.clear();
	}
}
