package dev.opalcompat.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/** Persistent GPU storage; the owner releases it only after the device is idle. */
public final class StorageBuffer implements AutoCloseable {

	private long buffer;
	private long memory;
	private final long size;

	public StorageBuffer(long size) {
		if (size <= 0 || size > Long.MAX_VALUE - 3) throw new IllegalArgumentException("Invalid SSBO size");
		this.size = (size + 3) & ~3L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(this.size)
					.usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)
					.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			Vk.check(vkCreateBuffer(Vk.device(), info, null, handle), "create pack SSBO");
			this.buffer = handle.get(0);
			VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(Vk.device(), this.buffer, requirements);
			VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
					.allocationSize(requirements.size()).memoryTypeIndex(Vk.memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
			Vk.check(vkAllocateMemory(Vk.device(), allocation, null, handle), "allocate pack SSBO");
			this.memory = handle.get(0);
			Vk.check(vkBindBufferMemory(Vk.device(), this.buffer, this.memory, 0), "bind pack SSBO");
		} catch (RuntimeException | Error failure) { close(); throw failure; }
	}

	public long handle() { return this.buffer; }
	public long size() { return this.size; }

	@Override
	public void close() {
		if (this.buffer != 0) vkDestroyBuffer(Vk.device(), this.buffer, null);
		if (this.memory != 0) vkFreeMemory(Vk.device(), this.memory, null);
		this.buffer = this.memory = 0;
	}
}
