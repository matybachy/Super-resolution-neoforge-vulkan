package dev.opalcompat.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.vk.HostBuffer;
import dev.opalcompat.vk.Vk;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.lwjgl.vulkan.VK10.*;

/**
 * For the autotest: catches what shows for a frame or three and is gone again, with the camera moving or not. Every
 * frame's image is shrunk on the device and read back. A cell of the small one that jumps and jumps back to where it
 * was blinked; a moving camera changes a cell gradually and never comes back to the same value that fast. The frames
 * around a blink are written out as PNG, off this thread, so that one can see what it was.
 */
public final class BlinkProbe {

	/**
	 * Half the screen, then this many halvings more: one cell is sixty-four pixels of the screen. Any finer and a
	 * flying camera reads as blinking, every fine pattern on the ground passing through a cell and out again.
	 */
	private static final int LEVELS = 6;
	private static final int RING = 16;
	/** How far a cell must jump, of 255, and how close to where it was it must come back. */
	private static final int JUMP = 6;
	private static final int BACK = 2;
	private static final int LONGEST = 3;
	private static final int GONE_FOR_FRAMES = 4;
	/** A blink of this many cells at once is what one sees across the screen, about one percent of it. */
	private static final int WIDE = 8;
	/** A jump this far in a cell this wide is a part of the world gone, sky or far terrain showing where a chunk was. */
	private static final int STRONG_JUMP = 24;
	private static final int STRONG_BACK = 6;
	private static final int SAVED_EVENTS = 6;
	private static final int SAVE_EVERY_FRAMES = 40;

	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "OpalCompat blink probe writer");
		thread.setDaemon(true);
		return thread;
	});

	private static BlinkProbe active;
	private static Path pendingOut;
	private static int runs;

	private final int run;
	private final Path out;
	private final VulkanImage shrunk;
	private final int bigWidth, bigHeight, smallWidth, smallHeight;
	private final HostBuffer[] big = new HostBuffer[RING];
	private final HostBuffer[] small = new HostBuffer[RING];
	private final int[][] history = new int[LONGEST + 2][];
	private final Map<Integer, Long> pendingBySlot = new HashMap<>();

	private final Map<RenderSection, Long> nearSeen = new IdentityHashMap<>();
	private final Map<Long, Long> farSeen = new HashMap<>();
	private long nearFrame = -1;
	private long nearReturned;
	private long farReturned;
	private long recorded;
	private long analysed;
	private long blinkFrames;
	private long wideFrames;
	private long strongFrames;
	private long blinkCells;
	private int saved;
	private long savedAt = -SAVE_EVERY_FRAMES;

	private BlinkProbe(Path out, int width, int height) {
		this.run = ++runs;
		this.out = out;
		this.bigWidth = width / 2;
		this.bigHeight = height / 2;
		this.smallWidth = this.bigWidth >> (LEVELS - 1);
		this.smallHeight = this.bigHeight >> (LEVELS - 1);
		this.shrunk = VulkanImage.builder(this.bigWidth, this.bigHeight).setFormat(VK_FORMAT_R8G8B8A8_UNORM)
				.setUsage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
				.setClamp(true).setMipLevels(LEVELS).createVulkanImage();
		for (int i = 0; i < RING; i++) {
			this.big[i] = new HostBuffer(this.bigWidth * this.bigHeight * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT, 4);
			this.small[i] = new HostBuffer(this.smallWidth * this.smallHeight * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT, 4);
		}
	}

	/** From the script. The size is taken from the first frame after this. */
	public static void start(Path out) {
		pendingOut = out;
	}

	public static String stop() {
		BlinkProbe probe = active;
		active = null;
		pendingOut = null;
		if (probe == null) return "probe: nothing ran";
		dev.opalcompat.vk.QueueLock.deviceWaitIdle();
		for (HostBuffer buffer : probe.big) buffer.close();
		for (HostBuffer buffer : probe.small) buffer.close();
		probe.shrunk.free();
		return String.format("probe %d: %d frames, %d with a blink, %d with a wide one (%d+ cells), %d cells, %d frames with a part of the world gone; "
						+ "gone for a few frames and back: %d near sections of %d, %d far of %d",
				probe.run, probe.analysed, probe.blinkFrames, probe.wideFrames, WIDE, probe.blinkCells, probe.strongFrames,
				probe.nearReturned, probe.nearSeen.size(), probe.farReturned, probe.farSeen.size());
	}

	/** What the frame draws of the near world, once a frame. */
	static void nearSections(StaticQueue<ChunkArea> areas) {
		BlinkProbe probe = active;
		if (probe == null || probe.nearFrame == probe.recorded) return;
		probe.nearFrame = probe.recorded;
		int areaCount = areas.size();
		for (int a = 0; a < areaCount; a++) {
			StaticQueue<RenderSection> sections = areas.get(a).sectionQueue;
			int count = sections.size();
			for (int s = 0; s < count; s++) {
				if (returned(probe.nearSeen, sections.get(s), probe.recorded)) probe.nearReturned++;
			}
		}
	}

	/** One section of the far terrain the frame draws. */
	public static void farSection(long position) {
		BlinkProbe probe = active;
		if (probe != null && returned(probe.farSeen, position, probe.recorded)) probe.farReturned++;
	}

	/** Whether it was drawn, then not for a few frames, and is here again. */
	private static <K> boolean returned(Map<K, Long> seen, K key, long frame) {
		Long last = seen.put(key, frame);
		return last != null && frame - last >= 2 && frame - last <= GONE_FOR_FRAMES + 1;
	}

	static void slotFree(int slot) {
		BlinkProbe probe = active;
		if (probe == null) return;
		Long serial = probe.pendingBySlot.remove(slot);
		if (serial != null) probe.analyse(serial);
	}

	static void record(VkCommandBuffer cmd, MemoryStack stack, VulkanImage screen, int slot) {
		Path out = pendingOut;
		if (out == null) return;
		BlinkProbe probe = active;
		if (probe == null) active = probe = new BlinkProbe(out, screen.width, screen.height);
		if (probe.bigWidth == screen.width / 2 && probe.bigHeight == screen.height / 2) probe.copy(cmd, stack, screen, slot);
	}

	private void copy(VkCommandBuffer cmd, MemoryStack stack, VulkanImage screen, int slot) {
		long serial = this.recorded++;
		int at = (int) (serial % RING);
		screen.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		this.shrunk.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
		region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.srcOffsets(1).set(screen.width, screen.height, 1);
		region.dstOffsets(1).set(this.bigWidth, this.bigHeight, 1);
		vkCmdBlitImage(cmd, screen.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, this.shrunk.getId(),
				VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_LINEAR);
		PackRenderer.mipChain(cmd, stack, this.shrunk);
		copyLevel(cmd, stack, 0, this.bigWidth, this.bigHeight, this.big[at]);
		copyLevel(cmd, stack, LEVELS - 1, this.smallWidth, this.smallHeight, this.small[at]);
		this.pendingBySlot.put(slot, serial);
	}

	private void copyLevel(VkCommandBuffer cmd, MemoryStack stack, int level, int width, int height, HostBuffer to) {
		VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
		copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1);
		copy.imageExtent().set(width, height, 1);
		vkCmdCopyImageToBuffer(cmd, this.shrunk.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to.handle(), copy);
	}

	private void analyse(long serial) {
		int at = (int) (serial % RING);
		int cells = this.smallWidth * this.smallHeight;
		ByteBuffer bytes = this.small[at].view(0, cells * 4).order(ByteOrder.LITTLE_ENDIAN);
		int[] luma = this.history[this.history.length - 1];
		if (luma == null) luma = new int[cells];
		for (int i = 0; i < cells; i++) {
			int rgba = bytes.getInt(i * 4);
			luma[i] = (54 * (rgba & 255) + 183 * (rgba >> 8 & 255) + 19 * (rgba >> 16 & 255)) >> 8;
		}
		System.arraycopy(this.history, 0, this.history, 1, this.history.length - 1);
		this.history[0] = luma;
		this.analysed++;
		if (this.history[this.history.length - 1] == null) return;

		int mostCells = 0, mostLength = 0, strong = 0, strongLength = 0;
		for (int length = 1; length <= LONGEST; length++) {
			int strongBefore = strong;
			// [0] the frame after, [1] the last of the blink, [length] its first, [length + 1] the frame before.
			int[] after = this.history[0], last = this.history[1], first = this.history[length], before = this.history[length + 1];
			int found = 0, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, total = 0;
			for (int i = 0; i < cells; i++) {
				int in = first[i] - before[i], out = after[i] - last[i];
				if (Math.abs(in) >= STRONG_JUMP && Math.abs(out) >= STRONG_JUMP && (in ^ out) < 0
						&& Math.abs(after[i] - before[i]) <= STRONG_BACK) strong++;
				if (Math.abs(in) < JUMP || Math.abs(out) < JUMP || (in ^ out) >= 0) continue;
				if (Math.abs(after[i] - before[i]) > BACK) continue;
				found++;
				total += Math.abs(in);
				int x = i % this.smallWidth, y = i / this.smallWidth;
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
			}
			if (strong > strongBefore) strongLength = length;
			if (found == 0) continue;
			this.blinkCells += found;
			if (found > mostCells) {
				mostCells = found;
				mostLength = length;
			}
			if (found >= WIDE) {
				OpalCompat.LOGGER.info("[probe] {} frame {}: {} cells blinked for {} frames, box {},{} to {},{} px, mean jump {}", this.run, serial, found,
						length, minX << LEVELS, minY << LEVELS, maxX + 1 << LEVELS, maxY + 1 << LEVELS, total / found);
			}
		}
		if (strong > 0) {
			this.strongFrames++;
			OpalCompat.LOGGER.info("[probe] {} frame {}: {} cells gone and back for {} frames", this.run, serial, strong, strongLength);
			// Strong ones first: those are what one wants to see.
			save(serial, strongLength, true);
		}
		if (mostCells == 0) return;
		this.blinkFrames++;
		if (mostCells < WIDE) return;
		this.wideFrames++;
		save(serial, mostLength, false);
	}

	private void save(long serial, int length, boolean strong) {
		if (this.saved >= SAVED_EVENTS * 2 || !strong && this.saved >= SAVED_EVENTS) return;
		if (serial - this.savedAt < SAVE_EVERY_FRAMES) return;
		this.saved++;
		this.savedAt = serial;
		String name = (strong ? "strong_" : "blink_") + this.run + "_" + serial;
		write(serial - length - 1, name + "_before");
		write(serial - length, name + "_during");
		write(serial, name + "_after");
	}

	private void write(long serial, String name) {
		int at = (int) (serial % RING);
		int width = this.bigWidth, height = this.bigHeight;
		byte[] pixels = new byte[width * height * 4];
		this.big[at].view(0, pixels.length).get(pixels);
		Path file = this.out.resolve(name + ".png");
		WRITER.execute(() -> {
			ByteBuffer bytes = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN);
			try (NativeImage png = new NativeImage(width, height, false)) {
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						// The screen's format is BGRA and the blit keeps the channels' meaning, so this is RGBA.
						png.setPixelRGBA(x, y, bytes.getInt((x + y * width) * 4) | 0xFF000000);
					}
				}
				png.writeToFile(file);
			} catch (IOException e) {
				OpalCompat.LOGGER.error("[probe] cannot write {}", file, e);
			}
		});
	}
}
