package dev.opalcompat.render;

import dev.opalcompat.OpalCompat;
import dev.opalcompat.diag.RenderSampler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * How the frames went, said in the log every ten seconds while a pack draws: how many, how long, the slowest second
 * as a frame counter would show it, how much of the time the render thread had a processor, and what the pack's mesh
 * draws cost. The numbers a report of low frame rates has to start from, since the game is not run here; where the
 * time goes is {@link RenderSampler}'s. Render thread only.
 */
final class FrameStats {

	private static final long EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);
	private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	private static final boolean PROCESSOR_TIME = THREADS.isCurrentThreadCpuTimeSupported();

	private static long since = System.nanoTime();
	private static long processorSince = -1L;
	private static int frames;
	private static long meshDraws;
	private static long meshNanos;
	private static long waiting;
	private static long longest;
	private static long frameStarted;
	private static long secondStarted;
	private static int secondFrames;
	private static double slowestSecond = Double.MAX_VALUE;
	private static boolean levelDrawn;

	private FrameStats() {
	}

	static void mesh() {
		meshDraws++;
	}

	static void meshTime(long nanos) {
		meshNanos += nanos;
	}

	/** A draw left out because its program or pipeline is still being built. */
	static void waiting() {
		waiting++;
	}

	/** A pack drew a level in the frame being recorded. */
	static void level() {
		levelDrawn = true;
	}

	/** After each frame VulkanMod finishes. Frames of menus and of the game without a pack are not counted. */
	static void frame() {
		if (!levelDrawn) {
			idle();
			return;
		}
		levelDrawn = false;
		long now = System.nanoTime();
		long took = frameStarted != 0L ? now - frameStarted : -1L;
		RenderSampler.frame(took);
		if (took >= 0L) longest = Math.max(longest, took);
		frameStarted = now;
		frames++;
		if (processorSince < 0L && PROCESSOR_TIME) processorSince = THREADS.getCurrentThreadCpuTime();
		if (secondStarted == 0L) secondStarted = now;
		secondFrames++;
		if (now - secondStarted >= SECOND_NANOS) {
			slowestSecond = Math.min(slowestSecond, secondFrames * 1.0e9 / (now - secondStarted));
			secondStarted = now;
			secondFrames = 0;
		}
		if (now - since < EVERY_NANOS) return;
		if (frames > 1) {
			String processor = "?";
			if (PROCESSOR_TIME && processorSince >= 0L) {
				long used = THREADS.getCurrentThreadCpuTime();
				processor = Math.round((used - processorSince) * 100.0 / (now - since)) + "%";
				processorSince = used;
			}
			OpalCompat.LOGGER.info("[pack] {} frames in {} s: {} ms on average, longest {} ms, slowest second {} fps; the render thread had a processor {} of the time; a frame: {} mesh draws taking {} ms; {} draws left out waiting for shaders",
					frames, TimeUnit.NANOSECONDS.toSeconds(now - since),
					String.format(Locale.ROOT, "%.1f", (now - since) / 1.0e6 / frames), TimeUnit.NANOSECONDS.toMillis(longest),
					slowestSecond == Double.MAX_VALUE ? "?" : String.valueOf(Math.round(slowestSecond)), processor,
					meshDraws / frames, String.format(Locale.ROOT, "%.2f", meshNanos / 1.0e6 / frames), waiting);
		}
		since = now;
		frames = 0;
		meshDraws = 0;
		meshNanos = 0;
		waiting = 0;
		longest = 0;
		slowestSecond = Double.MAX_VALUE;
	}

	/** No pack, or no level: the next line starts afresh. */
	private static void idle() {
		frameStarted = 0L;
		since = System.nanoTime();
		processorSince = -1L;
		frames = 0;
		secondStarted = 0L;
		secondFrames = 0;
		slowestSecond = Double.MAX_VALUE;
	}
}
