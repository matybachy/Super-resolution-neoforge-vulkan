package dev.opalcompat.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A host visible, coherent, persistently mapped buffer handed out front to back within a frame.
 * One per frame in flight, rewound when that frame begins again.
 */
public final class HostBuffer implements AutoCloseable {

	private final long buffer;
	private final long memory;
	private final long size;
	private final long mapped;
	private final int alignment;
	private long head;

	public HostBuffer(long size, int usage, int alignment) {
		this.size = size;
		this.alignment = alignment;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
					.size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateBuffer(Vk.device(), info, null, handle), "vkCreateBuffer");
			this.buffer = handle.get(0);
			VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(Vk.device(), this.buffer, requirements);
			VkMemoryAllocateInfo allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
					.allocationSize(requirements.size())
					.memoryTypeIndex(Vk.memoryType(requirements.memoryTypeBits(),
							VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
			Vk.check(vkAllocateMemory(Vk.device(), allocate, null, handle), "vkAllocateMemory");
			this.memory = handle.get(0);
			Vk.check(vkBindBufferMemory(Vk.device(), this.buffer, this.memory, 0), "vkBindBufferMemory");
			PointerBuffer pointer = stack.mallocPointer(1);
			Vk.check(vkMapMemory(Vk.device(), this.memory, 0, size, 0, pointer), "vkMapMemory");
			this.mapped = pointer.get(0);
		}
	}

	public void rewind() {
		this.head = 0;
	}

	public boolean fits(int bytes) {
		long offset = (this.head + this.alignment - 1) / this.alignment * this.alignment;
		return offset + bytes <= this.size;
	}

	/** Space for {@code bytes}, aligned; returns the offset into the buffer. */
	public long allocate(int bytes) {
		long offset = (this.head + this.alignment - 1) / this.alignment * this.alignment;
		if (offset + bytes > this.size) {
			throw new IllegalStateException("Host buffer of " + this.size + " bytes is full");
		}
		this.head = offset + bytes;
		return offset;
	}

	public ByteBuffer view(long offset, int bytes) {
		return MemoryUtil.memByteBuffer(this.mapped + offset, bytes);
	}

	public long handle() {
		return this.buffer;
	}

	@Override
	public void close() {
		vkUnmapMemory(Vk.device(), this.memory);
		vkDestroyBuffer(Vk.device(), this.buffer, null);
		vkFreeMemory(Vk.device(), this.memory, null);
	}
}
