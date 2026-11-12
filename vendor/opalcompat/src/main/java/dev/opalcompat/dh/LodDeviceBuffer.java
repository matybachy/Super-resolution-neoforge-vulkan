package dev.opalcompat.dh;

import dev.opalcompat.vk.Vk;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.Buffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

/**
 * Device memory for one buffer of far terrain, made straight through VulkanMod's allocator, which is safe from any
 * thread. VulkanMod's own {@code MemoryManager.createBuffer} keeps its books in a map that its render thread also changes
 * outside that method's lock, so it is not called from DH's threads. A VulkanMod {@link Buffer} still, so that its upload
 * queue takes it on the render thread.
 */
final class LodDeviceBuffer extends Buffer {

	LodDeviceBuffer(int size) {
		super(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, MemoryTypes.GPU_MEM);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
					.usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
			LodTransfer.share(info, stack);
			VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
					.requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
			LongBuffer buffer = stack.mallocLong(1);
			PointerBuffer memory = stack.mallocPointer(1);
			Vk.check(vmaCreateBuffer(Vulkan.getAllocator(), info, allocation, buffer, memory, null), "vmaCreateBuffer (far terrain)");
			setId(buffer.get(0));
			setAllocation(memory.get(0));
			setBufferSize(size);
		}
	}

	/**
	 * Through VulkanMod's queue of frames, as its own buffers go: a frame recorded before this may still read it. Never
	 * through {@code freeBuffer}, whose books do not have this buffer.
	 */
	void release() {
		long buffer = getId();
		long memory = getAllocation();
		MemoryManager.getInstance().addFrameOp(() -> vmaDestroyBuffer(Vulkan.getAllocator(), buffer, memory));
	}
}
