package dev.matybachy.srvmr.render;

import dev.matybachy.srvmr.Srvmr;
import dev.matybachy.srvmr.SrvmrConfig;
import dev.matybachy.srvmr.ngx.Ngx;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.vulkan.VK10.*;

/**
 * DLSS in VulkanMod Reforged's resolve of the scaled world to the swapchain. Render thread, outside any render pass.
 * <p>
 * Per frame: the depth of the world (Opal Compat's with a shader pack, VulkanMod Reforged's without) goes through
 * {@link MotionPass} into motion vectors and a depth DLSS reads; DLSS reads those and the scaled colour and writes a
 * picture the size of the swapchain, which VulkanMod Reforged then draws over the swapchain where it would have drawn
 * the scaled colour. Any failure leaves DLSS off for the session and the frame to VulkanMod Reforged.
 */
public final class DlssResolve {

	private static final long REPORT_EVERY = TimeUnit.SECONDS.toNanos(10);
	private static final String[] QUALITY_NAMES = {"performance", "balanced", "quality", "ultra performance", "ultra quality", "DLAA"};

	private static MotionPass motionPass;
	private static VulkanImage motion;
	private static VulkanImage depth;
	/** DLSS's last picture, which a generated frame moves ({@link FrameGen}). */
	static VulkanImage output;
	/** Per render pixel of the last upscaled frame: its point and its entity's move, for {@link FrameGen}. */
	static VulkanImage place;
	static VulkanImage moved;
	/** The points of the upscaled frame before, which tell an entity's points from what stands in its box. */
	private static VulkanImage previousPlace;
	/** Whether {@link #place} holds the last frame DLSS upscaled. */
	private static boolean placeWritten;
	private static int imagesRenderWidth;
	private static int imagesRenderHeight;
	private static int imagesOutputWidth;
	private static int imagesOutputHeight;

	private static long feature;
	private static int featureKey;
	private static int featureQuality = -1;
	/** Sizes DLSS could not be made for, and when that was: tried again after {@link #RETRY_AFTER}. */
	private static final Map<Integer, Long> UNFIT = new HashMap<>();
	private static final long RETRY_AFTER = TimeUnit.SECONDS.toNanos(60);
	/**
	 * Frames drawn for DLSS in a row that it did not upscale. Past {@link #GIVE_UP_AFTER} the frames stop being jittered,
	 * since a jittered picture nothing undoes shimmers, until the sizes change.
	 */
	private static int leftInRow;
	private static int leftInRowKey;
	private static long gaveUpAt;
	private static final int GIVE_UP_AFTER = 120;
	private static final long GIVE_UP_FOR = TimeUnit.SECONDS.toNanos(30);
	/** The last frame DLSS upscaled; what it holds is let go of after {@link #UNUSED_FOR} without one. */
	private static long lastUpscale;
	private static final long UNUSED_FOR = TimeUnit.SECONDS.toNanos(60);

	private static long lastEvaluation;
	private static long reportedAt = System.nanoTime();
	private static int upscaled;
	private static int left;
	private static String leftReason = "";

	private DlssResolve() {
	}

	/** Whether frames of these sizes are to be drawn for DLSS: it takes them, and has not kept leaving them to VulkanMod Reforged. */
	public static boolean wanted(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
		int key = key(renderWidth, renderHeight, outputWidth, outputHeight);
		if (unfit(key)) return false;
		if (key != leftInRowKey) {
			leftInRowKey = key;
			leftInRow = 0;
		}
		// A problem that lasted a while (a shader pack loading, say) is not one for the rest of the session.
		if (leftInRow >= GIVE_UP_AFTER && System.nanoTime() - gaveUpAt > GIVE_UP_FOR) leftInRow = 0;
		return leftInRow < GIVE_UP_AFTER;
	}

	/**
	 * At the start of each frame of the world: DLSS's feature and images, unused for {@link #UNUSED_FOR} (the Render Scale
	 * back at 100 %, DLSS given up), are let go of. Some hundred MB of video memory at 1440p.
	 */
	public static void collect() {
		try {
			if (feature == 0L && motion == null) return;
			if (lastUpscale != 0L && System.nanoTime() - lastUpscale < UNUSED_FOR) return;
			Vulkan.waitIdle();
			Ngx.release(feature);
			feature = 0L;
			featureKey = 0;
			FrameGen.release();
			if (motion != null) {
				freeImages();
			}
			lastUpscale = 0L;
			Srvmr.LOGGER.info("[dlss] DLSS unused for a while; its memory is released");
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] cannot release DLSS's memory", e);
		}
	}

	private static boolean unfit(int key) {
		Long since = UNFIT.get(key);
		if (since == null) return false;
		if (System.nanoTime() - since < RETRY_AFTER) return true;
		UNFIT.remove(key);
		return false;
	}

	private static int key(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
		return ((renderWidth * 31 + renderHeight) * 31 + outputWidth) * 31 + outputHeight;
	}

	/**
	 * The settings page applied new settings: the feature is made again with them on the next frame, sizes given up on are
	 * tried again, and the history starts afresh.
	 */
	public static void settingsChanged() {
		featureKey = -1;
		UNFIT.clear();
		leftInRow = 0;
	}

	/** A frame drawn for DLSS that VulkanMod Reforged never resolved: it counts as left, and the next starts afresh. */
	static void unresolved() {
		leave("VulkanMod Reforged did not resolve the frame");
		FrameGen.forget();
		DlssFrame.finish(false);
		if (++leftInRow == GIVE_UP_AFTER) {
			gaveUpAt = System.nanoTime();
			Srvmr.LOGGER.warn("[dlss] {} frames in a row were left to VulkanMod Reforged (last: {}); DLSS tries again in 30 s",
					GIVE_UP_AFTER, leftReason);
		}
	}

	/** The upscaled picture to draw instead of the scaled colour, or null for VulkanMod Reforged's own. */
	public static VulkanImage resolve(VkCommandBuffer cmd, Framebuffer scaled, SwapChain swapChain) {
		// A generated frame drew no world: its picture is the last upscaled one, moved to where the camera is now.
		if (FrameGen.levelSkipped()) return FrameGen.resolve(cmd, swapChain);
		if (FrameGen.generating()) {
			FrameGen.worldNotLeftOut();
			return null;
		}
		if (!DlssFrame.planned) return null;
		boolean done = false;
		try {
			VulkanImage result = upscale(cmd, scaled, swapChain);
			done = result != null;
			return result;
		} catch (Throwable e) {
			Srvmr.LOGGER.error("[dlss] DLSS failed in the middle of a frame and is off for this session", e);
			Ngx.giveUp();
			return null;
		} finally {
			if (done) {
				FrameGen.realUpscaled(DlssFrame.renderWidth, DlssFrame.renderHeight, DlssFrame.outputWidth,
						DlssFrame.outputHeight, DlssFrame.cameraX(), DlssFrame.cameraY(), DlssFrame.cameraZ());
			} else {
				FrameGen.forget();
			}
			DlssFrame.finish(done);
			if (done) {
				upscaled++;
				leftInRow = 0;
				lastUpscale = System.nanoTime();
			} else if (++leftInRow == GIVE_UP_AFTER) {
				gaveUpAt = System.nanoTime();
				Srvmr.LOGGER.warn("[dlss] {} frames in a row were left to VulkanMod Reforged (last: {}); DLSS tries again in 30 s",
						GIVE_UP_AFTER, leftReason);
			}
			report();
		}
	}

	private static VulkanImage upscale(VkCommandBuffer cmd, Framebuffer scaled, SwapChain swapChain) {
		if (!DlssFrame.captured) return leave("the world was not drawn");
		if (!Ngx.ready()) return leave("DLSS is off");
		VulkanImage color = scaled != null ? scaled.getColorAttachment() : null;
		if (color == null || swapChain == null) return leave("no target");
		int renderWidth = color.width;
		int renderHeight = color.height;
		int outputWidth = swapChain.getWidth();
		int outputHeight = swapChain.getHeight();
		if (renderWidth != DlssFrame.renderWidth || renderHeight != DlssFrame.renderHeight
				|| outputWidth != DlssFrame.outputWidth || outputHeight != DlssFrame.outputHeight) {
			return leave("the size changed during the frame");
		}

		// The depth of the world, and where the hand is. A shader pack keeps its own depth, with the hand in a band of
		// its own; without one the world's depth was copied before the game cleared it for the hand, and what the
		// hand drew over the cleared depth is in VulkanMod Reforged's depth now.
		SrvmrConfig config = SrvmrConfig.get();
		VulkanImage world = OpalDepth.current();
		boolean pack = world != null;
		VulkanImage hand;
		int handMode;
		OpalDepth.Distant distant = null;
		if (pack) {
			// The pack drew the picture: VulkanMod Reforged's depth has nothing of it, so no other depth will do.
			if (world.width != renderWidth || world.height != renderHeight) return leave("the pack's depth is not at the render size");
			hand = world;
			handMode = MotionPass.HAND_BAND;
			distant = OpalDepth.distant();
			if (distant != null && (distant.depth().width != renderWidth || distant.depth().height != renderHeight)) distant = null;
		} else {
			hand = scaled.getDepthAttachment();
			world = DlssFrame.worldDepth;
			handMode = MotionPass.HAND_CLEARED;
			if (world == null) {
				// No copy this frame (the hand is not drawn, or the copy failed): the depth there is the world's.
				world = hand;
				handMode = MotionPass.HAND_NONE;
			}
		}
		if (world == null || hand == null || world.width != renderWidth || world.height != renderHeight) {
			return leave("no depth of the world at the render size");
		}

		if (motionPass != null && !motionPass.fits()) {
			Vulkan.waitIdle();
			motionPass.close();
			motionPass = null;
		}
		if (motionPass == null) motionPass = new MotionPass();
		images(renderWidth, renderHeight, outputWidth, outputHeight);
		boolean fresh = feature(renderWidth, renderHeight, outputWidth, outputHeight, config.quality);
		if (feature == 0L) return leave("DLSS does not take " + renderWidth + "x" + renderHeight + " for " + outputWidth + "x" + outputHeight);

		long now = System.nanoTime();
		float frameMillis = lastEvaluation == 0L ? 16.6F : Math.min(1000.0F, (now - lastEvaluation) / 1.0e6F);
		lastEvaluation = now;
		boolean reset = fresh || DlssFrame.reset();

		// The depth images go back to the layouts they were in: VulkanMod Reforged expects its main depth in its
		// attachment layout (its depth snapshot throws otherwise) and begins its passes from there.
		int worldLayout = world.getCurrentLayout();
		int handLayout = hand.getCurrentLayout();
		VulkanImage far = distant != null ? distant.depth() : null;
		int farLayout = far != null ? far.getCurrentLayout() : VK_IMAGE_LAYOUT_UNDEFINED;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Layouts.read(stack, cmd, world);
			if (hand != world) Layouts.read(stack, cmd, hand);
			if (far != null) Layouts.read(stack, cmd, far);
			Layouts.transition(stack, cmd, motion, VK_IMAGE_LAYOUT_GENERAL);
			Layouts.transition(stack, cmd, depth, VK_IMAGE_LAYOUT_GENERAL);
			// The last upscaled frame's points become the previous ones; this frame's go into the other image.
			boolean previousValid = placeWritten && !reset;
			VulkanImage swap = previousPlace;
			previousPlace = place;
			place = swap;
			placeWritten = false;
			// Read by a generated frame in GENERAL too: they stay in it.
			Layouts.transition(stack, cmd, place, VK_IMAGE_LAYOUT_GENERAL);
			Layouts.transition(stack, cmd, previousPlace, VK_IMAGE_LAYOUT_GENERAL);
			Layouts.transition(stack, cmd, moved, VK_IMAGE_LAYOUT_GENERAL);
			motionPass.record(stack, cmd, world, hand, handMode, distant, pack && config.packDepthUpsideDown,
					pack ? config.packDepth : config.vanillaDepth, config.handDepth, motion, depth, place, previousPlace,
					previousValid, moved, DlssFrame.jitterNdcX(), DlssFrame.jitterNdcY(), config.invertMotionY);
			restore(stack, cmd, world, worldLayout);
			if (hand != world) restore(stack, cmd, hand, handLayout);
			if (far != null) restore(stack, cmd, far, farLayout);
			Layouts.transition(stack, cmd, motion, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			Layouts.transition(stack, cmd, depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			Layouts.read(stack, cmd, color);
			Layouts.transition(stack, cmd, output, VK_IMAGE_LAYOUT_GENERAL);

			long[] handles = {
					color.getId(), color.getImageView(),
					depth.getId(), depth.getImageView(),
					motion.getId(), motion.getImageView(),
					output.getId(), output.getImageView()};
			int[] info = {
					color.format, renderWidth, renderHeight,
					depth.format, renderWidth, renderHeight,
					motion.format, renderWidth, renderHeight,
					output.format, outputWidth, outputHeight};
			float jitterX = config.invertJitterX ? -DlssFrame.jitterX : DlssFrame.jitterX;
			float jitterY = config.invertJitterY ? -DlssFrame.jitterY : DlssFrame.jitterY;
			int result = Ngx.evaluate(cmd.address(), feature, handles, info, jitterX, jitterY, reset, frameMillis,
					renderWidth, renderHeight);
			// The output is read by VulkanMod Reforged's blit from here on, whatever DLSS did.
			Layouts.transition(stack, cmd, output, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			if (!Ngx.succeeded(result)) {
				Srvmr.LOGGER.error("[dlss] DLSS evaluation failed: {}; DLSS is off for this session", Ngx.describe(result));
				Ngx.giveUp();
				return null;
			}
		}
		placeWritten = true;
		return output;
	}

	/**
	 * VulkanMod Reforged is tearing its Vulkan down ({@code Vulkan.cleanUp}): DLSS's feature, the motion pass and NGX
	 * let go of the device first. The images are VulkanMod Reforged's own kind and go with its memory.
	 */
	public static void shutdown() {
		TextureBias.shutdown();
		FrameGen.shutdown();
		try {
			if (feature == 0L && motionPass == null && Ngx.failed()) return;
			Vulkan.waitIdle();
			Ngx.release(feature);
			feature = 0L;
			if (motionPass != null) {
				motionPass.close();
				motionPass = null;
			}
			Ngx.shutdown();
		} catch (Throwable e) {
			Srvmr.LOGGER.warn("[dlss] DLSS did not shut down cleanly", e);
		}
	}

	/** Back to a layout it was in before, where that was a layout at all. */
	private static void restore(MemoryStack stack, VkCommandBuffer cmd, VulkanImage image, int layout) {
		if (layout != VK_IMAGE_LAYOUT_UNDEFINED && layout != VK_IMAGE_LAYOUT_PREINITIALIZED) {
			Layouts.transition(stack, cmd, image, layout);
		}
	}

	private static VulkanImage leave(String reason) {
		left++;
		leftReason = reason;
		return null;
	}

	/** With the device idle. */
	private static void freeImages() {
		motion.free();
		depth.free();
		output.free();
		place.free();
		previousPlace.free();
		moved.free();
		motion = null;
		depth = null;
		output = null;
		place = null;
		previousPlace = null;
		moved = null;
		placeWritten = false;
	}

	/** The motion, depth and output images at their sizes; made again, with the device idle, when a size changes. */
	private static void images(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
		if (motion != null && renderWidth == imagesRenderWidth && renderHeight == imagesRenderHeight
				&& outputWidth == imagesOutputWidth && outputHeight == imagesOutputHeight) return;
		// Generated frames move the last picture: with new images there is none yet.
		FrameGen.forget();
		if (motion != null) {
			Vulkan.waitIdle();
			freeImages();
		}
		int storage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
		motion = VulkanImage.builder(renderWidth, renderHeight).setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
				.setUsage(storage).setLinearFiltering(false).setClamp(true).createVulkanImage();
		depth = VulkanImage.builder(renderWidth, renderHeight).setFormat(VK_FORMAT_R32_SFLOAT)
				.setUsage(storage).setLinearFiltering(false).setClamp(true).createVulkanImage();
		place = VulkanImage.builder(renderWidth, renderHeight).setFormat(VK_FORMAT_R32G32B32A32_SFLOAT)
				.setUsage(storage).setLinearFiltering(false).setClamp(true).createVulkanImage();
		previousPlace = VulkanImage.builder(renderWidth, renderHeight).setFormat(VK_FORMAT_R32G32B32A32_SFLOAT)
				.setUsage(storage).setLinearFiltering(false).setClamp(true).createVulkanImage();
		placeWritten = false;
		moved = VulkanImage.builder(renderWidth, renderHeight).setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
				.setUsage(storage).setLinearFiltering(false).setClamp(true).createVulkanImage();
		output = VulkanImage.builder(outputWidth, outputHeight).setFormat(VK_FORMAT_R8G8B8A8_UNORM)
				.setUsage(storage | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
				.setLinearFiltering(true).setClamp(true).createVulkanImage();
		imagesRenderWidth = renderWidth;
		imagesRenderHeight = renderHeight;
		imagesOutputWidth = outputWidth;
		imagesOutputHeight = outputHeight;
	}

	/**
	 * The DLSS feature for these sizes, made in this frame's command buffer when there is none or the sizes changed.
	 * Returns whether it is new, in which case its history is empty anyway.
	 */
	private static boolean feature(int renderWidth, int renderHeight, int outputWidth, int outputHeight,
			SrvmrConfig.Quality wanted) {
		int key = key(renderWidth, renderHeight, outputWidth, outputHeight);
		if (feature != 0L && key == featureKey) return false;
		if (feature != 0L) {
			Vulkan.waitIdle();
			Ngx.release(feature);
			feature = 0L;
		}
		featureKey = key;
		if (unfit(key)) return false;
		int quality = quality(renderWidth, renderHeight, outputWidth, outputHeight, wanted);
		if (quality < 0) {
			UNFIT.put(key, System.nanoTime());
			Srvmr.LOGGER.warn("[dlss] DLSS takes no render size of {}x{} for {}x{}: set VulkanMod Reforged's Render Scale to "
					+ "65-70 % (quality), 55-60 % (balanced), 50 % (performance) or 33 % (ultra performance)",
					renderWidth, renderHeight, outputWidth, outputHeight);
			return false;
		}
		feature = createNow(renderWidth, renderHeight, outputWidth, outputHeight, quality);
		if (feature == 0L) {
			UNFIT.put(key, System.nanoTime());
			Srvmr.LOGGER.warn("[dlss] DLSS could not be made for {}x{} to {}x{}: {}", renderWidth, renderHeight,
					outputWidth, outputHeight, Ngx.describe(Ngx.lastResult()));
			return false;
		}
		featureQuality = quality;
		Srvmr.LOGGER.info("[dlss] DLSS {} from {}x{} to {}x{}", name(quality), renderWidth, renderHeight, outputWidth, outputHeight);
		return true;
	}

	/**
	 * Makes the feature in a command buffer of its own, submitted and waited for, not in the frame's: VulkanMod Reforged
	 * drops a frame's commands unsubmitted when its swapchain has to be made again, and a feature whose making never ran
	 * would be used from the next frame on.
	 */
	private static long createNow(int renderWidth, int renderHeight, int outputWidth, int outputHeight, int quality) {
		Queue queue = DeviceManager.getGraphicsQueue();
		CommandPool.CommandBuffer commands = queue.beginCommands();
		long handle = Ngx.create(commands.getHandle().address(), renderWidth, renderHeight, outputWidth, outputHeight, quality);
		long fence = queue.submitCommands(commands);
		Synchronization.waitFence(fence);
		Synchronization.INSTANCE.addCommandBuffer(commands);
		return handle;
	}

	private static final SrvmrConfig.Quality[] ANY = {SrvmrConfig.Quality.QUALITY, SrvmrConfig.Quality.BALANCED,
			SrvmrConfig.Quality.PERFORMANCE, SrvmrConfig.Quality.ULTRA_PERFORMANCE, SrvmrConfig.Quality.DLAA};

	/**
	 * The quality whose range of render sizes holds this one: the one asked for if the size is in its range, otherwise (and
	 * with {@code auto}) the one whose optimal size is nearest. -1 where none does.
	 * <p>
	 * A mode asked for at a Render Scale it is not made for gives way: NGX gives each mode a model of its own for its own
	 * ratio (in DLSS 310.9.16 Preset L for Ultra Performance's third of the screen, Preset M for Performance's half, as its
	 * log says), and Ultra Performance at a Render Scale of 50 % had a model for a third upscale a half.
	 */
	private static int quality(int renderWidth, int renderHeight, int outputWidth, int outputHeight, SrvmrConfig.Quality wanted) {
		if (wanted != SrvmrConfig.Quality.AUTO) {
			int asked = quality(renderWidth, renderHeight, outputWidth, outputHeight, new SrvmrConfig.Quality[] {wanted}, false);
			if (asked >= 0) return asked;
			int fits = quality(renderWidth, renderHeight, outputWidth, outputHeight, ANY, true);
			if (fits >= 0 && fits != wanted.ngx) {
				Srvmr.LOGGER.info("[dlss] {} is not made for {}x{} of {}x{}; using {}, which the Render Scale fits",
						name(wanted.ngx), renderWidth, renderHeight, outputWidth, outputHeight, name(fits));
			}
			return fits;
		}
		return quality(renderWidth, renderHeight, outputWidth, outputHeight, ANY, true);
	}

	private static int quality(int renderWidth, int renderHeight, int outputWidth, int outputHeight,
			SrvmrConfig.Quality[] candidates, boolean nearestWhenNone) {
		int best = -1;
		long bestDistance = Long.MAX_VALUE;
		int nearest = -1;
		long nearestDistance = Long.MAX_VALUE;
		for (SrvmrConfig.Quality candidate : candidates) {
			int[] sizes = Ngx.renderSizes(outputWidth, outputHeight, candidate.ngx);
			if (sizes == null || sizes[0] <= 0 || sizes[1] <= 0) continue;
			long distance = Math.abs((long) sizes[0] - renderWidth) + Math.abs((long) sizes[1] - renderHeight);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = candidate.ngx;
			}
			int maxWidth = Math.max(sizes[2], sizes[0]);
			int maxHeight = Math.max(sizes[3], sizes[1]);
			int minWidth = sizes[4] > 0 ? sizes[4] : sizes[0];
			int minHeight = sizes[5] > 0 ? sizes[5] : sizes[1];
			if (renderWidth < minWidth || renderWidth > maxWidth || renderHeight < minHeight || renderHeight > maxHeight) continue;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate.ngx;
			}
		}
		// VulkanMod Reforged's scale rounds its own way and NGX's ranges can be one size wide: a render size outside every
		// range goes to the mode it is nearest, which DLSS takes as a ratio of its own (as with DLSS set to a custom
		// ratio); only if creating it fails is the size given up.
		if (best < 0 && nearest >= 0 && nearestWhenNone) {
			Srvmr.LOGGER.info("[dlss] {}x{} for {}x{} is outside the ranges NGX lists; trying {}", renderWidth, renderHeight,
					outputWidth, outputHeight, name(nearest));
			return nearest;
		}
		return best;
	}

	private static String name(int quality) {
		return quality >= 0 && quality < QUALITY_NAMES.length ? QUALITY_NAMES[quality] : Integer.toString(quality);
	}

	private static void report() {
		long now = System.nanoTime();
		if (now - reportedAt < REPORT_EVERY) return;
		if (upscaled + left > 0) {
			Srvmr.LOGGER.info("[dlss] {} s: {} frames upscaled ({}){}", TimeUnit.NANOSECONDS.toSeconds(now - reportedAt),
					upscaled, featureQuality >= 0 ? name(featureQuality) : "-",
					left > 0 ? String.format(Locale.ROOT, ", %d left to VulkanMod Reforged (last: %s)", left, leftReason) : "");
		}
		reportedAt = now;
		upscaled = 0;
		left = 0;
	}
}
