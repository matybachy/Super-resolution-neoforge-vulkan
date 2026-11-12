package dev.opalcompat.render;

import dev.opalcompat.vk.Vk;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Render passes and framebuffers for the pack's passes, cached by their formats and image views.
 * Every attachment stays in {@code COLOR_ATTACHMENT_OPTIMAL} (or the depth equivalent) across the pass,
 * so the layout VulkanMod Reforged tracks on each {@code VulkanImage} stays true.
 */
final class RenderPasses implements AutoCloseable {

	private record PassKey(List<Integer> formats, int depthFormat, boolean load) {
	}

	private record FramebufferKey(long renderPass, List<Long> views, int width, int height) {
	}

	private final Map<PassKey, Long> passes = new HashMap<>();
	private final Map<FramebufferKey, Long> framebuffers = new HashMap<>();

	/** @param load whether the attachments keep what they held (false: the pass overwrites them all) */
	long renderPass(List<Integer> formats, boolean load) {
		return renderPass(formats, 0, load);
	}

	/** @param depthFormat the depth attachment's format, last in the attachment list, or 0 for none */
	long renderPass(List<Integer> formats, int depthFormat, boolean load) {
		return this.passes.computeIfAbsent(new PassKey(List.copyOf(formats), depthFormat, load), this::create);
	}

	private long create(PassKey key) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int count = key.formats().size();
			boolean depth = key.depthFormat() != 0;
			VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(count + (depth ? 1 : 0), stack);
			VkAttachmentReference.Buffer references = VkAttachmentReference.calloc(count, stack);
			for (int i = 0; i < count; i++) {
				attachments.get(i)
						.format(key.formats().get(i))
						.samples(VK_SAMPLE_COUNT_1_BIT)
						.loadOp(key.load() ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_DONT_CARE)
						.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
						.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
						.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
						.initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
						.finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
				references.get(i).attachment(i).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			}
			VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
					.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
					.colorAttachmentCount(count)
					.pColorAttachments(references);
			if (depth) {
				attachments.get(count)
						.format(key.depthFormat())
						.samples(VK_SAMPLE_COUNT_1_BIT)
						.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
						.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
						.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
						.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
						.initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
						.finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
				subpass.pDepthStencilAttachment(VkAttachmentReference.calloc(stack)
						.attachment(count).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
			}
			// Earlier passes may have written or read these images in the same layout, where no
			// layout transition carries the dependency.
			int stages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
					| VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
			VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
					.srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
					.srcStageMask(stages).dstStageMask(stages)
					.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
					.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
							| VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
							| VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
			VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
					.pAttachments(attachments).pSubpasses(subpass).pDependencies(dependency);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateRenderPass(Vk.device(), info, null, handle), "vkCreateRenderPass");
			return handle.get(0);
		}
	}

	long framebuffer(long renderPass, long[] views, int width, int height) {
		FramebufferKey key = new FramebufferKey(renderPass, Arrays.stream(views).boxed().toList(), width, height);
		return this.framebuffers.computeIfAbsent(key, k -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default()
						.renderPass(renderPass).pAttachments(stack.longs(views))
						.width(width).height(height).layers(1);
				LongBuffer handle = stack.mallocLong(1);
				Vk.check(vkCreateFramebuffer(Vk.device(), info, null, handle), "vkCreateFramebuffer");
				return handle.get(0);
			}
		});
	}

	/** Drops every framebuffer, for when the images behind them are replaced. */
	void forgetFramebuffers() {
		this.framebuffers.values().forEach(fb -> vkDestroyFramebuffer(Vk.device(), fb, null));
		this.framebuffers.clear();
	}

	@Override
	public void close() {
		forgetFramebuffers();
		this.passes.values().forEach(pass -> vkDestroyRenderPass(Vk.device(), pass, null));
		this.passes.clear();
	}
}
