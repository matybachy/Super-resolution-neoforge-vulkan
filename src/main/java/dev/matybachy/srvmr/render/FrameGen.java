package dev.matybachy.srvmr.render;

import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.SrvmrConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Frame generation, experimental: frames shown between the real ones, made without drawing the world. Render thread.
 * <p>
 * After a frame DLSS upscaled, the next one to five frames ({@code frameGeneration} x2 to x6) skip the world: the game
 * still sets up its camera, but {@code LevelRenderer.renderLevel}, the hand and a shader pack's passes are left out,
 * and where VulkanMod Reforged would stretch the world onto the swapchain, DLSS's last picture is moved to where the
 * camera is now ({@link FrameGenPass}). Moving entities are moved on by their own last move, in the share of it the time
 * since that frame makes. The GUI is drawn over it as on every frame.
 * <p>
 * What the world costs the processor is saved on those frames; the camera turns and moves at the full frame rate, the
 * world itself changes only with the real frames. Spinning parts, water, particles and animated textures are not
 * entities that move as a whole and keep the real frames' pace. Nothing is generated while riding (the thing ridden
 * would shake against the camera), after the camera jumped, or until DLSS has upscaled a frame of the current sizes.
 * <p>
 * A generated frame is much quicker than a real one, so they would alternate long and short. It waits until it has
 * lasted {@code framePacing} of a real frame: 0 is the most frames, higher is more even.
 */
public final class FrameGen {

	/** A camera that moved further than this since the last real frame jumped: the moved picture would be wrong. */
	private static final double JUMP = 8.0;
	/** Rings of grid cells searched for something to fill a cell nothing landed in. */
	private static final int SEARCH = 6;
	private static final long REPORT_EVERY = TimeUnit.SECONDS.toNanos(10);
	private static final long MAX_WAIT = TimeUnit.MILLISECONDS.toNanos(25);
	/** Kept for the GUI and presenting after the wait. */
	private static final long MARGIN = TimeUnit.MILLISECONDS.toNanos(1);

	private static boolean generating;
	private static boolean levelSkipped;
	/** This frame's picture, once made: a second resolve in the same frame shows it again. */
	private static VulkanImage resolved;
	private static boolean failed;

	private static boolean history;
	private static int remaining;
	private static int historyRenderWidth;
	private static int historyRenderHeight;
	private static int historyOutputWidth;
	private static int historyOutputHeight;
	private static double realX;
	private static double realY;
	private static double realZ;

	private static long frameStart;
	private static long lastFrameStart;
	private static boolean lastFrameReal;
	private static long realStart;
	private static long previousRealStart;
	/** How long a real frame lasts, smoothed. */
	private static double realNanos;

	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;
	private static final Matrix4f VIEW_PROJECTION = new Matrix4f();

	private static FrameGenPass pass;
	private static VulkanImage nearest;
	private static VulkanImage source;
	private static VulkanImage generated;

	private static long reportedAt = System.nanoTime();
	private static int generatedCount;
	private static int realCount;
	private static long waited;

	private FrameGen() {
	}

	/** Whether this frame is a generated one; decided at its start. */
	public static boolean generating() {
		return generating;
	}

	/** Whether this frame is a generated one and its world was left out. */
	public static boolean levelSkipped() {
		return levelSkipped;
	}

	/** {@code GameRenderer.renderLevel} HEAD, every frame. */
	static void beginFrame(long now) {
		if (lastFrameReal && lastFrameStart != 0L) {
			long spent = now - lastFrameStart;
			if (spent > 0L && spent < TimeUnit.MILLISECONDS.toNanos(250)) {
				realNanos = realNanos == 0.0 ? spent : realNanos * 0.8 + spent * 0.2;
			}
		}
		lastFrameReal = false;
		lastFrameStart = now;
		frameStart = now;
		generating = false;
		levelSkipped = false;
		resolved = null;
	}

	/**
	 * A frame meant to be generated reached the resolve with its world drawn: the hook that leaves the world out did not
	 * take (another mod changed the same call). Generating then only costs DLSS every other frame, so it stops.
	 */
	static void worldNotLeftOut() {
		if (failed) return;
		Srvmr.LOGGER.warn("[framegen] the world could not be left out of a generated frame; frame generation is off for this session");
		failed = true;
		forget();
	}

	/** The last picture is not one to move: none was upscaled, the images went, the frame was not for DLSS. */
	static void forget() {
		history = false;
		remaining = 0;
	}

	/** At the start of a frame DLSS would upscale: whether it is generated instead. */
	static boolean decide(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
		int extra = SrvmrConfig.get().frameGeneration.extra;
		if (extra == 0 || failed || !history || renderWidth != historyRenderWidth || renderHeight != historyRenderHeight
				|| outputWidth != historyOutputWidth || outputHeight != historyOutputHeight) {
			remaining = 0;
			return false;
		}
		if (remaining <= 0) return false;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && minecraft.player.isPassenger()) {
			remaining = 0;
			return false;
		}
		remaining--;
		generating = true;
		return true;
	}

	/** DLSS upscaled this frame: its picture is the one the next generated frames move. */
	static void realUpscaled(int renderWidth, int renderHeight, int outputWidth, int outputHeight, double x, double y, double z) {
		history = true;
		historyRenderWidth = renderWidth;
		historyRenderHeight = renderHeight;
		historyOutputWidth = outputWidth;
		historyOutputHeight = outputHeight;
		realX = x;
		realY = y;
		realZ = z;
		previousRealStart = realStart;
		realStart = frameStart;
		lastFrameReal = true;
		remaining = SrvmrConfig.get().frameGeneration.extra;
		realCount++;
	}

	/**
	 * {@code GameRenderer.renderLevel}, in place of {@code LevelRenderer.renderLevel}: whether the world is left out of
	 * this frame. The camera is set up by then, and its matrices are the ones handed to the world.
	 */
	public static boolean skipLevel(Camera camera, Matrix4f modelView, Matrix4f projection) {
		if (!generating) return false;
		try {
			Vec3 position = camera.getPosition();
			double dx = position.x - realX;
			double dy = position.y - realY;
			double dz = position.z - realZ;
			if (dx * dx + dy * dy + dz * dz > JUMP * JUMP) {
				// Drawn as the game draws it, not for DLSS; the next frame is a real one.
				generating = false;
				forget();
				return false;
			}
			cameraX = position.x;
			cameraY = position.y;
			cameraZ = position.z;
			VIEW_PROJECTION.set(projection).mul(modelView);
			levelSkipped = true;
			return true;
		} catch (Throwable e) {
			fail("leaving the world out", e);
			generating = false;
			return false;
		}
	}

	/**
	 * In VulkanMod Reforged's resolve of a frame whose world was left out: the generated picture, or DLSS's last one as it
	 * is where it cannot be moved, so that the frame never shows the empty scaled target.
	 */
	static VulkanImage resolve(VkCommandBuffer cmd, SwapChain swapChain) {
		if (resolved != null) return resolved;
		VulkanImage last = DlssResolve.output;
		resolved = last;
		try {
			VulkanImage place = DlssResolve.place;
			VulkanImage moved = DlssResolve.moved;
			if (!history || last == null || place == null || moved == null || swapChain == null) return last;
			int outputWidth = swapChain.getWidth();
			int outputHeight = swapChain.getHeight();
			if (outputWidth != last.width || outputHeight != last.height || place.width != historyRenderWidth
					|| place.height != historyRenderHeight) {
				return last;
			}
			if (pass != null && !pass.fits()) {
				Vulkan.waitIdle();
				pass.close();
				pass = null;
			}
			if (pass == null) pass = new FrameGenPass();
			images(place.width, place.height, outputWidth, outputHeight);
			long interval = realStart - previousRealStart;
			float share = previousRealStart == 0L || interval <= 0L ? 0.0F
					: (float) Math.min(1.5, Math.max(0.0, (double) (frameStart - realStart) / interval));
			try (MemoryStack stack = MemoryStack.stackPush()) {
				pass.record(stack, cmd, place, moved, nearest, source, last, generated, VIEW_PROJECTION,
						(float) (realX - cameraX), (float) (realY - cameraY), (float) (realZ - cameraZ), share, SEARCH);
			}
			resolved = generated;
			generatedCount++;
			pace();
			report();
			return generated;
		} catch (Throwable e) {
			fail("generating a frame", e);
			return last;
		}
	}

	/** Waits until this generated frame has lasted its share of a real one. */
	private static void pace() {
		float pacing = SrvmrConfig.get().framePacing;
		if (pacing <= 0.0F || realNanos <= 0.0) return;
		long begin = System.nanoTime();
		long until = Math.min(frameStart + (long) (realNanos * pacing) - MARGIN, begin + MAX_WAIT);
		while (true) {
			long now = System.nanoTime();
			long left = until - now;
			if (left <= 0L) break;
			// The last part spun: a sleep on Windows can overshoot by a millisecond or more.
			if (left > TimeUnit.MICROSECONDS.toNanos(1500)) {
				LockSupport.parkNanos(left - TimeUnit.MILLISECONDS.toNanos(1));
			} else {
				Thread.onSpinWait();
			}
		}
		waited += System.nanoTime() - begin;
	}

	private static void report() {
		long now = System.nanoTime();
		if (now - reportedAt < REPORT_EVERY) return;
		Srvmr.LOGGER.info("[framegen] {} s: {} generated and {} real frames; a real frame took {} ms, a generated one waited {} ms",
				TimeUnit.NANOSECONDS.toSeconds(now - reportedAt), generatedCount, realCount,
				String.format(Locale.ROOT, "%.1f", realNanos / 1.0e6),
				String.format(Locale.ROOT, "%.1f", generatedCount == 0 ? 0.0 : waited / 1.0e6 / generatedCount));
		reportedAt = now;
		generatedCount = 0;
		realCount = 0;
		waited = 0L;
	}

	/** The two grids at the render size and the generated picture at the output size; made again when a size changes. */
	private static void images(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
		if (generated != null && nearest.width == renderWidth && nearest.height == renderHeight
				&& generated.width == outputWidth && generated.height == outputHeight) return;
		if (generated != null) {
			Vulkan.waitIdle();
			freeImages();
		}
		int grid = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		nearest = VulkanImage.builder(renderWidth, renderHeight).setFormat(VK_FORMAT_R32_UINT)
				.setUsage(grid).setLinearFiltering(false).setClamp(true).createVulkanImage();
		source = VulkanImage.builder(renderWidth, renderHeight).setFormat(VK_FORMAT_R32_UINT)
				.setUsage(grid).setLinearFiltering(false).setClamp(true).createVulkanImage();
		generated = VulkanImage.builder(outputWidth, outputHeight).setFormat(VK_FORMAT_R8G8B8A8_UNORM)
				.setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
				.setLinearFiltering(true).setClamp(true).createVulkanImage();
	}

	private static void freeImages() {
		nearest.free();
		source.free();
		generated.free();
		nearest = null;
		source = null;
		generated = null;
	}

	/** DLSS let go of its images, with the device idle: these go with them. */
	static void release() {
		forget();
		if (generated != null) freeImages();
	}

	/** VulkanMod Reforged is tearing its Vulkan down. */
	static void shutdown() {
		try {
			if (pass == null) return;
			Vulkan.waitIdle();
			pass.close();
			pass = null;
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[framegen] did not shut down cleanly", e);
		}
	}

	/** Frame generation is off for the session; DLSS goes on. */
	private static void fail(String what, Throwable e) {
		Srvmr.LOGGER.error("[framegen] frame generation failed {} and is off for this session", what, e);
		failed = true;
		forget();
	}
}
