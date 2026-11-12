package dev.opalcompat.render;

import dev.opalcompat.vk.HostBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-visible memory handed out front to back, retained until VulkanMod reuses the frame slot. Grows
 * by a page instead of failing: how many draws a level makes is not known ahead of it.
 */
final class FrameArena implements AutoCloseable {
	private final int pageBytes;
	private final int usage;
	private final int alignment;
	private final Map<Integer, List<HostBuffer>> frames = new HashMap<>();
	private List<HostBuffer> pages;
	private int pageIndex;

	record Slice(long buffer, long offset, ByteBuffer bytes) {
	}

	FrameArena(int pageBytes, int usage, int alignment) {
		this.pageBytes = pageBytes;
		this.usage = usage;
		this.alignment = alignment;
	}

	/** Call only after the frame slot's fence has been waited by VulkanMod. */
	void begin(int frame) {
		this.pages = this.frames.computeIfAbsent(frame, unused -> new ArrayList<>());
		this.pages.forEach(HostBuffer::rewind);
		this.pageIndex = 0;
	}

	Slice allocate(int bytes) {
		if (this.pages == null) throw new IllegalStateException("Frame arena has not begun a frame");
		if (bytes <= 0) throw new IllegalArgumentException("Allocation must be positive");
		while (this.pageIndex < this.pages.size()) {
			HostBuffer page = this.pages.get(this.pageIndex);
			if (page.fits(bytes)) return slice(page, bytes);
			this.pageIndex++;
		}
		HostBuffer page = new HostBuffer(Math.max(this.pageBytes, bytes), this.usage, this.alignment);
		this.pages.add(page);
		return slice(page, bytes);
	}

	private static Slice slice(HostBuffer page, int bytes) {
		long offset = page.allocate(bytes);
		return new Slice(page.handle(), offset, page.view(offset, bytes).order(ByteOrder.nativeOrder()));
	}

	@Override
	public void close() {
		this.frames.values().forEach(list -> list.forEach(HostBuffer::close));
		this.frames.clear();
		this.pages = null;
	}
}
