package dev.opalcompat.diag;

import dev.opalcompat.OpalCompat;
import dev.opalcompat.pack.ShaderPacks;
import net.neoforged.fml.ModList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Where the render thread spends a frame, said in the log every thirty seconds while a pack draws a level.
 * <p>
 * VulkanMod Reforged's spike lines account for chunks, uploads and waits on the device, and nothing in a log says what
 * a frame does otherwise: in the log of 2026-11-10, 112 of 120 spikes had none of those in them, and none had waited on
 * the device. So a thread of its own asks the render thread for its stack every five milliseconds and counts, for all
 * frames and apart for the slow ones, which part of the frame it was in, whose code was running, which mods were on
 * the stack and which method it was. A frame's samples are counted once its length is known, which is how the slow
 * frames get a count of their own.
 * <p>
 * A stack is taken at a safepoint, so tight loops are seen at their ends; that shifts a method by a line, not by a mod.
 * Asking costs the render thread tens of microseconds a time. Off with {@code frameSampler=false} in
 * {@code config/opalcompat.properties}.
 */
public final class RenderSampler {

	private static final String KEY = "frameSampler";
	private static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
	private static final long REPORT_NANOS = TimeUnit.SECONDS.toNanos(30);
	private static final long SLOW_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
	private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
	private static final int FEWEST_SAMPLES = 400;
	private static final int RING = 1024;

	private static final String OTHER = "other";
	private static final String SHADOW = "shadow map";
	private static final String FAR = "far terrain (Distant Horizons)";
	private static final String FAR_PACKAGE = "com.seibel.distanthorizons.";
	private static final String GAME = "minecraft";
	private static final String LOADER = "neoforge";

	/** By class, then by method; {@code *} stands for every method of the class. The deepest one on a stack names the part. */
	private static final Map<String, Map<String, String>> PARTS = new HashMap<>();

	static {
		part("net.minecraft.client.Minecraft", "tick", "tick");
		part("net.minecraft.util.thread.BlockableEventLoop", "runAllTasks", "queued tasks (packets)");
		part("net.minecraft.client.renderer.texture.TextureManager", "tick", "texture animations");
		part("net.minecraft.client.sounds.SoundManager", "*", "sound");
		part("net.minecraft.client.renderer.GameRenderer", "render", "frame, the rest");
		part("net.minecraft.client.renderer.GameRenderer", "renderLevel", "level, the rest");
		part("net.minecraft.client.renderer.LevelRenderer", "renderLevel", "level, the rest");
		part("net.minecraft.client.renderer.LevelRenderer", "renderSky", "sky and weather");
		part("net.minecraft.client.renderer.LevelRenderer", "renderClouds", "sky and weather");
		part("net.minecraft.client.renderer.LevelRenderer", "renderSnowAndRain", "sky and weather");
		part("net.minecraft.client.renderer.entity.EntityRenderDispatcher", "render", "entities");
		part("net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher", "render", "block entities");
		part("net.minecraft.client.particle.ParticleEngine", "render", "particles");
		part("net.minecraft.client.renderer.ItemInHandRenderer", "renderHandsWithItems", "hand");
		part("net.minecraft.client.gui.Gui", "render", "hud");
		part("net.minecraft.client.gui.screens.Screen", "renderWithTooltip", "screen");
		part("com.mojang.blaze3d.platform.Window", "updateDisplay", "window and events");
		part("net.vulkanmod.vulkan.Renderer", "preInitFrame", "frame start (wait on the device, chunk uploads)");
		part("net.vulkanmod.vulkan.Renderer", "beginFrame", "frame start (wait on the device, chunk uploads)");
		part("net.vulkanmod.vulkan.Renderer", "endFrame", "submit and present");
		part("net.vulkanmod.render.chunk.WorldRenderer", "*", "chunks (VulkanMod)");
		part("dev.opalcompat.render.PackRenderer", "openAndShadow", SHADOW);
		part("dev.opalcompat.render.PackRenderer", "terrainLayer", "terrain draw (pack)");
		part("dev.opalcompat.render.PackRenderer", "distantHalf", "far terrain draw (pack)");
		part("dev.opalcompat.render.DistantPlain", "*", "far terrain draw (pack)");
		for (String method : List.of("runPasses", "deferred", "finishFrame", "drawFullscreen", "runShadowComposites")) {
			part("dev.opalcompat.render.PackRenderer", method, "pack passes");
		}
		for (String method : List.of("drawImmediate", "drawStored", "mixedMesh", "storedMesh", "submitMesh")) {
			part("dev.opalcompat.render.PackRenderer", method, "mesh draws (pack)");
		}
	}

	private static final boolean ENABLED = ShaderPacks.flag(KEY, true);
	private static final AtomicLongArray LENGTHS = new AtomicLongArray(RING);
	private static final AtomicInteger FRAMES = new AtomicInteger();
	private static final AtomicInteger SLOW_FRAMES = new AtomicInteger();
	private static final AtomicLong FRAME_NANOS = new AtomicLong();

	/** Frames finished. Written by the render thread alone, after the frame's length. */
	private static volatile long finished;
	private static volatile long lastFrameAt;
	private static volatile Thread render;
	private static Set<String> mods = Set.of();

	private RenderSampler() {
	}

	private static void part(String type, String method, String name) {
		PARTS.computeIfAbsent(type, t -> new HashMap<>()).put(method, name);
	}

	/**
	 * Render thread, at the end of a frame in which a pack drew a level.
	 *
	 * @param nanos since the end of the frame before, or negative where that one drew no level
	 */
	public static void frame(long nanos) {
		if (!ENABLED) return;
		long index = finished;
		LENGTHS.set((int) (index & (RING - 1)), nanos);
		finished = index + 1;
		lastFrameAt = System.nanoTime();
		if (nanos >= 0L) {
			FRAMES.incrementAndGet();
			FRAME_NANOS.addAndGet(nanos);
			if (nanos >= SLOW_NANOS) SLOW_FRAMES.incrementAndGet();
		}
		if (render == null) start();
	}

	private static void start() {
		Set<String> ids = new HashSet<>();
		ModList.get().getMods().forEach(mod -> ids.add(mod.getModId()));
		ids.remove(GAME);
		ids.remove(LOADER);
		mods = ids;
		render = Thread.currentThread();
		Thread thread = new Thread(RenderSampler::run, "OpalCompat sampler");
		thread.setDaemon(true);
		thread.start();
	}

	private record Sample(long frame, String part, boolean shadow, boolean runnable, String owner, String place, List<String> mods) {
	}

	private static final class Tally {
		private int samples;
		private int shadow;
		private int runnable;
		private final Map<String, int[]> parts = new HashMap<>();
		private final Map<String, int[]> owners = new HashMap<>();
		private final Map<String, int[]> onStack = new HashMap<>();
		private final Map<String, int[]> places = new HashMap<>();

		void add(Sample sample) {
			this.samples++;
			if (sample.shadow()) this.shadow++;
			if (sample.runnable()) this.runnable++;
			count(this.parts, sample.part());
			count(this.owners, sample.owner());
			count(this.places, sample.place());
			for (String mod : sample.mods()) count(this.onStack, mod);
		}

		private static void count(Map<String, int[]> counts, String key) {
			counts.computeIfAbsent(key, k -> new int[1])[0]++;
		}

		void clear() {
			this.samples = this.shadow = this.runnable = 0;
			this.parts.clear();
			this.owners.clear();
			this.onStack.clear();
			this.places.clear();
		}
	}

	private static void run() {
		ArrayDeque<Sample> pending = new ArrayDeque<>();
		Tally all = new Tally();
		Tally slow = new Tally();
		long reportedAt = System.nanoTime();
		try {
			while (true) {
				LockSupport.parkNanos(INTERVAL_NANOS);
				long now = System.nanoTime();
				// A menu, a pause, a world being left: nothing a frame's length could be put to.
				if (now - lastFrameAt > QUIET_NANOS) {
					pending.clear();
					LockSupport.parkNanos(QUIET_NANOS / 2);
					continue;
				}
				Thread thread = render;
				long frame = finished;
				boolean runnable = thread.getState() == Thread.State.RUNNABLE;
				StackTraceElement[] stack = thread.getStackTrace();
				if (stack.length > 0) pending.add(read(frame, runnable, stack));
				settle(pending, all, slow);
				if (now - reportedAt >= REPORT_NANOS && all.samples >= FEWEST_SAMPLES) {
					report(all, slow, now - reportedAt);
					all.clear();
					slow.clear();
					reportedAt = now;
				}
			}
		} catch (Throwable e) {
			OpalCompat.LOGGER.warn("The frame sampler stops", e);
		}
	}

	/** Counts the samples of frames that have ended, each under its frame's length. */
	private static void settle(ArrayDeque<Sample> pending, Tally all, Tally slow) {
		long done = finished;
		while (!pending.isEmpty() && pending.peekFirst().frame() < done) {
			Sample sample = pending.pollFirst();
			if (done - sample.frame() >= RING) continue;
			long nanos = LENGTHS.get((int) (sample.frame() & (RING - 1)));
			if (nanos < 0L) continue;
			all.add(sample);
			if (nanos >= SLOW_NANOS) slow.add(sample);
		}
	}

	private static Sample read(long frame, boolean runnable, StackTraceElement[] stack) {
		String part = OTHER;
		boolean shadow = false;
		List<String> onStack = new ArrayList<>(4);
		for (int i = stack.length - 1; i >= 0; i--) {
			StackTraceElement element = stack[i];
			String type = element.getClassName();
			String named = null;
			Map<String, String> methods = PARTS.get(type);
			if (methods != null) {
				named = methods.get(element.getMethodName());
				if (named == null) named = methods.get("*");
			} else if (type.startsWith(FAR_PACKAGE)) {
				named = FAR;
			}
			if (named != null) {
				part = named;
				if (SHADOW.equals(named)) shadow = true;
			}
			String mod = modOf(element, false);
			if (mod != null && !onStack.contains(mod)) onStack.add(mod);
		}
		String owner = "?";
		String place = "?";
		for (StackTraceElement element : stack) {
			String mod = modOf(element, true);
			String module = element.getModuleName();
			if (mod == null && !GAME.equals(module) && !LOADER.equals(module)) continue;
			owner = mod != null ? mod : module;
			String type = element.getClassName();
			place = owner + ": " + type.substring(type.lastIndexOf('.') + 1) + "." + element.getMethodName();
			break;
		}
		return new Sample(frame, part, shadow, runnable, owner, place, onStack);
	}

	/**
	 * The mod a stack frame belongs to, or null for the game, the loader and libraries. NeoForge names a mod's module
	 * by its id. A mixin's handler runs inside the class it was merged into, under a name that carries its mod
	 * between the second and the third dollar sign.
	 *
	 * @param wrapping whether a handler that calls on into what it wraps counts: it does for the code that is running,
	 *                 and does not for what is merely on the stack, where it would stand over everything it wraps
	 */
	private static String modOf(StackTraceElement element, boolean wrapping) {
		String module = element.getModuleName();
		if (module != null && mods.contains(module)) return module;
		String method = element.getMethodName();
		int first = method.indexOf('$');
		if (first <= 0) return null;
		int second = method.indexOf('$', first + 1);
		int third = second < 0 ? -1 : method.indexOf('$', second + 1);
		if (third < 0) return null;
		if (!wrapping && (method.startsWith("wrap") || method.startsWith("redirect"))) return null;
		String id = method.substring(second + 1, third);
		return mods.contains(id) ? id : null;
	}

	private static void report(Tally all, Tally slow, long nanos) {
		int frames = FRAMES.getAndSet(0);
		int slowFrames = SLOW_FRAMES.getAndSet(0);
		long frameNanos = FRAME_NANOS.getAndSet(0L);
		OpalCompat.LOGGER.info("[where] {} s: {} frames of {} ms on average, {} of them of {} ms or more; {} samples of the render thread, {} of them in those slow frames",
				TimeUnit.NANOSECONDS.toSeconds(nanos), frames, frames == 0 ? "?" : String.format(Locale.ROOT, "%.1f", frameNanos / 1.0e6 / frames),
				slowFrames, TimeUnit.NANOSECONDS.toMillis(SLOW_NANOS), all.samples, slow.samples);
		OpalCompat.LOGGER.info("[where] part of the frame, all | slow: {}", shares(all.parts, all.samples, slow.parts, slow.samples, 14));
		OpalCompat.LOGGER.info("[where] whose code was running, all | slow: {}", shares(all.owners, all.samples, slow.owners, slow.samples, 12));
		OpalCompat.LOGGER.info("[where] mods on the stack, all | slow: {}", shares(all.onStack, all.samples, slow.onStack, slow.samples, 12));
		OpalCompat.LOGGER.info("[where] methods, all frames: {}", top(all.places, all.samples, 10));
		if (slow.samples > 0) OpalCompat.LOGGER.info("[where] methods, slow frames: {}", top(slow.places, slow.samples, 10));
		OpalCompat.LOGGER.info("[where] inside the shadow pass altogether {} | {}; not runnable (blocked or waiting) {} | {}",
				percent(all.shadow, all.samples), percent(slow.shadow, slow.samples),
				percent(all.samples - all.runnable, all.samples), percent(slow.samples - slow.runnable, slow.samples));
	}

	private static String percent(int count, int of) {
		return of == 0 ? "-" : Math.round(count * 100.0F / of) + "%";
	}

	private static int count(Map<String, int[]> counts, String key) {
		int[] count = counts.get(key);
		return count == null ? 0 : count[0];
	}

	/** The largest shares of either column, each key with both. */
	private static String shares(Map<String, int[]> all, int allSamples, Map<String, int[]> slow, int slowSamples, int most) {
		Set<String> keys = new HashSet<>(all.keySet());
		keys.addAll(slow.keySet());
		List<String> ordered = new ArrayList<>(keys);
		Comparator<String> byShare = Comparator.comparingDouble(key -> -Math.max(
				allSamples == 0 ? 0.0 : (double) count(all, key) / allSamples,
				slowSamples == 0 ? 0.0 : (double) count(slow, key) / slowSamples));
		ordered.sort(byShare.thenComparing(Comparator.naturalOrder()));
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(most, ordered.size()); i++) {
			String key = ordered.get(i);
			if (i > 0) out.append("; ");
			out.append(key).append(' ').append(percent(count(all, key), allSamples)).append(" | ").append(percent(count(slow, key), slowSamples));
		}
		return out.length() == 0 ? "-" : out.toString();
	}

	private static String top(Map<String, int[]> counts, int samples, int most) {
		List<Map.Entry<String, int[]>> ordered = new ArrayList<>(counts.entrySet());
		ordered.sort(Comparator.<Map.Entry<String, int[]>>comparingInt(entry -> -entry.getValue()[0]).thenComparing(Map.Entry::getKey));
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(most, ordered.size()); i++) {
			if (i > 0) out.append("; ");
			out.append(percent(ordered.get(i).getValue()[0], samples)).append(' ').append(ordered.get(i).getKey());
		}
		return out.length() == 0 ? "-" : out.toString();
	}
}
