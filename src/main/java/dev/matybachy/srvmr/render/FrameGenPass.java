package dev.matybachy.srvmr.render;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A generated frame: DLSS's last picture moved to where the camera is now. Three dispatches of one compute shader.
 * <ol>
 * <li>Every render pixel of the last upscaled frame ({@link MotionPass} kept its point relative to that frame's camera,
 * and its entity's move) is taken to the new camera, its entity moved on by the share of its last move the time since
 * that frame makes, and the nearest distance landing in each cell of a grid the size of the render is kept
 * ({@code imageAtomicMin}).</li>
 * <li>The pixel whose distance won each cell writes itself into that cell.</li>
 * <li>Every pixel of the output takes the colour of the last picture where its cell's winner came from, moved by its own
 * place within the cell, so the picture keeps DLSS's full detail. A cell nothing landed in (what the last frame did not
 * see, or a gap where things came nearer) takes the farthest of its nearest neighbours that has one: what comes into
 * view behind something is more often the background than the thing.</li>
 * </ol>
 * The sky moves only with the camera's turn; the hand keeps its place on the screen.
 */
final class FrameGenPass implements AutoCloseable {

	/** Bytes of the push constants: a mat4, a vec4 and two ivec4. */
	private static final int PUSH = 112;

	private static final String SOURCE = """
			#version 450
			layout(local_size_x = 8, local_size_y = 8) in;
			layout(binding = 0, rgba32f) uniform readonly image2D place;
			layout(binding = 1, rgba16f) uniform readonly image2D moved;
			layout(binding = 2, r32ui) uniform uimage2D nearest;
			layout(binding = 3, r32ui) uniform uimage2D source;
			layout(binding = 4) uniform sampler2D history;
			layout(binding = 5, rgba8) uniform writeonly image2D generated;
			layout(push_constant) uniform Push {
			    mat4 viewProjection;   // the camera now, without jitter
			    vec4 cameraMove;       // the last upscaled frame's camera minus the camera now (xyz), share of the entities' last move (w)
			    ivec4 sizes;           // render size (xy), output size (zw)
			    ivec4 stage;           // 0 nearest distance, 1 source, 2 picture (x); how many rings of cells to search (y)
			} pc;
			const uint EMPTY = 0xFFFFFFFFu;
			const uint SKY = 0x7F000000u;

			// Where the point of this pixel of the last frame lies now, as a cell of the render grid, and how near it is.
			bool destination(ivec2 pixel, out ivec2 cell, out uint key) {
			    ivec2 size = pc.sizes.xy;
			    vec4 p = imageLoad(place, pixel);
			    cell = pixel;
			    key = 0u;
			    if (p.w > 1.5) return true;
			    vec4 clip;
			    if (p.w < 0.5) {
			        clip = pc.viewProjection * vec4(p.xyz, 0.0);
			        key = SKY;
			    } else {
			        vec3 now = p.xyz + pc.cameraMove.xyz + imageLoad(moved, pixel).xyz * pc.cameraMove.w;
			        clip = pc.viewProjection * vec4(now, 1.0);
			        key = floatBitsToUint(max(length(now), 1e-4));
			    }
			    if (clip.w <= 1e-6) return false;
			    vec2 ndc = clip.xy / clip.w;
			    vec2 uv = vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
			    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return false;
			    cell = min(ivec2(uv * vec2(size)), size - 1);
			    return true;
			}

			void main() {
			    ivec2 id = ivec2(gl_GlobalInvocationID.xy);
			    ivec2 size = pc.sizes.xy;
			    if (pc.stage.x < 2) {
			        if (id.x >= size.x || id.y >= size.y) return;
			        ivec2 cell;
			        uint key;
			        if (!destination(id, cell, key)) return;
			        if (pc.stage.x == 0) {
			            imageAtomicMin(nearest, cell, key);
			        } else if (imageLoad(nearest, cell).r == key) {
			            imageStore(source, cell, uvec4(uint(id.x) | (uint(id.y) << 16u), 0u, 0u, 0u));
			        }
			        return;
			    }
			    if (id.x >= pc.sizes.z || id.y >= pc.sizes.w) return;
			    vec2 uv = (vec2(id) + 0.5) / vec2(pc.sizes.zw);
			    ivec2 cell = min(ivec2(uv * vec2(size)), size - 1);
			    uint found = imageLoad(source, cell).r;
			    ivec2 at = cell;
			    if (found == EMPTY) {
			        uint farthest = 0u;
			        for (int r = 1; r <= pc.stage.y && found == EMPTY; r++) {
			            for (int y = -r; y <= r; y++) {
			                for (int x = -r; x <= r; x++) {
			                    if (max(abs(x), abs(y)) != r) continue;
			                    ivec2 c = cell + ivec2(x, y);
			                    if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, size))) continue;
			                    uint s = imageLoad(source, c).r;
			                    if (s == EMPTY) continue;
			                    uint d = imageLoad(nearest, c).r;
			                    if (found == EMPTY || d > farthest) {
			                        found = s;
			                        farthest = d;
			                        at = c;
			                    }
			                }
			            }
			        }
			    }
			    vec3 colour;
			    if (found == EMPTY) {
			        colour = textureLod(history, uv, 0.0).rgb;
			    } else {
			        vec2 from = (vec2(float(found & 0xFFFFu), float(found >> 16u)) + 0.5) / vec2(size);
			        vec2 within = uv - (vec2(at) + 0.5) / vec2(size);
			        colour = textureLod(history, from + within, 0.0).rgb;
			    }
			    imageStore(generated, id, vec4(colour, 1.0));
			}
			""";

	private final VkDevice device;
	private final long sampler;
	private final long setLayout;
	private final long pipelineLayout;
	private final long pipeline;
	private final long pool;
	private final long[] sets;

	FrameGenPass() {
		this.device = Vulkan.getVkDevice();
		int frames = Math.max(4, Renderer.getFramesNum());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);

			// The last picture is read between its pixels: a generated frame moves it by fractions of one.
			VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
					.magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0.0F);
			check(vkCreateSampler(this.device, samplerInfo, null, handle), "vkCreateSampler");
			this.sampler = handle.get(0);

			int[] types = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
					VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
			for (int i = 0; i < types.length; i++) {
				bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			check(vkCreateDescriptorSetLayout(this.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
					.pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
			this.setLayout = handle.get(0);

			VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
					.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH);
			check(vkCreatePipelineLayout(this.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(this.setLayout)).pPushConstantRanges(range), null, handle), "vkCreatePipelineLayout");
			this.pipelineLayout = handle.get(0);

			ByteBuffer spirv = compile();
			long module;
			try {
				check(vkCreateShaderModule(this.device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv),
						null, handle), "vkCreateShaderModule");
				module = handle.get(0);
			} finally {
				MemoryUtil.memFree(spirv);
			}
			try {
				VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
				pipelineInfo.get(0).sType$Default().layout(this.pipelineLayout);
				pipelineInfo.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module)
						.pName(stack.UTF8("main"));
				check(vkCreateComputePipelines(this.device, VK_NULL_HANDLE, pipelineInfo, null, handle), "vkCreateComputePipelines");
				this.pipeline = handle.get(0);
			} finally {
				vkDestroyShaderModule(this.device, module, null);
			}

			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(5 * frames);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(frames);
			check(vkCreateDescriptorPool(this.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(frames).pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
			this.pool = handle.get(0);

			LongBuffer layouts = stack.mallocLong(frames);
			for (int i = 0; i < frames; i++) layouts.put(i, this.setLayout);
			LongBuffer allocated = stack.mallocLong(frames);
			check(vkAllocateDescriptorSets(this.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(this.pool).pSetLayouts(layouts), allocated), "vkAllocateDescriptorSets");
			this.sets = new long[frames];
			for (int i = 0; i < frames; i++) this.sets[i] = allocated.get(i);
		}
	}

	/** Whether this pass still has a set for every frame VulkanMod Reforged keeps in flight. */
	boolean fits() {
		return Renderer.getFramesNum() <= this.sets.length;
	}

	/**
	 * Records the three dispatches, outside any render pass. {@code generated} ends in {@code SHADER_READ_ONLY_OPTIMAL}
	 * for VulkanMod Reforged's blit; the history is left in it; place, moved and the two grids stay in {@code GENERAL}.
	 */
	void record(MemoryStack stack, VkCommandBuffer cmd, VulkanImage place, VulkanImage moved, VulkanImage nearest,
			VulkanImage source, VulkanImage history, VulkanImage generated, Matrix4f viewProjection, float moveX, float moveY,
			float moveZ, float share, int search) {
		long set = this.sets[Renderer.getCurrentFrame() % this.sets.length];

		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
		storage(stack, writes.get(0), set, 0, place);
		storage(stack, writes.get(1), set, 1, moved);
		storage(stack, writes.get(2), set, 2, nearest);
		storage(stack, writes.get(3), set, 3, source);
		writes.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(this.sampler)
						.imageView(history.getImageView()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
		storage(stack, writes.get(5), set, 5, generated);
		vkUpdateDescriptorSets(this.device, writes, null);

		// Full barriers: the last upscaled frame wrote place, moved and the history in an earlier command buffer.
		Layouts.transition(stack, cmd, place, VK_IMAGE_LAYOUT_GENERAL);
		Layouts.transition(stack, cmd, moved, VK_IMAGE_LAYOUT_GENERAL);
		Layouts.read(stack, cmd, history);
		Layouts.transition(stack, cmd, nearest, VK_IMAGE_LAYOUT_GENERAL);
		Layouts.transition(stack, cmd, source, VK_IMAGE_LAYOUT_GENERAL);
		Layouts.transition(stack, cmd, generated, VK_IMAGE_LAYOUT_GENERAL);

		VkClearColorValue empty = VkClearColorValue.calloc(stack);
		empty.uint32(0, -1).uint32(1, -1).uint32(2, -1).uint32(3, -1);
		VkImageSubresourceRange.Buffer whole = VkImageSubresourceRange.calloc(1, stack);
		whole.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
		vkCmdClearColorImage(cmd, nearest.getId(), VK_IMAGE_LAYOUT_GENERAL, empty, whole);
		vkCmdClearColorImage(cmd, source.getId(), VK_IMAGE_LAYOUT_GENERAL, empty, whole);
		Layouts.transition(stack, cmd, nearest, VK_IMAGE_LAYOUT_GENERAL);
		Layouts.transition(stack, cmd, source, VK_IMAGE_LAYOUT_GENERAL);

		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, stack.longs(set), null);
		ByteBuffer push = stack.calloc(PUSH);
		viewProjection.get(0, push);
		push.putFloat(64, moveX).putFloat(68, moveY).putFloat(72, moveZ).putFloat(76, share);
		push.putInt(80, place.width).putInt(84, place.height).putInt(88, generated.width).putInt(92, generated.height);
		push.putInt(100, search);
		int renderGroupsX = (place.width + 7) / 8;
		int renderGroupsY = (place.height + 7) / 8;

		push.putInt(96, 0);
		vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
		vkCmdDispatch(cmd, renderGroupsX, renderGroupsY, 1);
		Layouts.transition(stack, cmd, nearest, VK_IMAGE_LAYOUT_GENERAL);

		push.putInt(96, 1);
		vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
		vkCmdDispatch(cmd, renderGroupsX, renderGroupsY, 1);
		Layouts.transition(stack, cmd, source, VK_IMAGE_LAYOUT_GENERAL);

		push.putInt(96, 2);
		vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
		vkCmdDispatch(cmd, (generated.width + 7) / 8, (generated.height + 7) / 8, 1);

		Layouts.transition(stack, cmd, generated, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	private static void storage(MemoryStack stack, VkWriteDescriptorSet write, long set, int binding, VulkanImage image) {
		write.sType$Default().dstSet(set).dstBinding(binding).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.imageView(image.getImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
	}

	/** Call with the device idle. */
	@Override
	public void close() {
		vkDestroyDescriptorPool(this.device, this.pool, null);
		vkDestroyPipeline(this.device, this.pipeline, null);
		vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
		vkDestroyDescriptorSetLayout(this.device, this.setLayout, null);
		vkDestroySampler(this.device, this.sampler, null);
	}

	private static ByteBuffer compile() {
		long compiler = shaderc_compiler_initialize();
		long options = shaderc_compile_options_initialize();
		long result = 0L;
		try {
			shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
			result = shaderc_compile_into_spv(compiler, SOURCE, shaderc_compute_shader, "srvmr_framegen.comp", "main", options);
			if (result == 0L || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				throw new IllegalStateException("Frame generation shader does not compile: "
						+ (result == 0L ? "no result" : shaderc_result_get_error_message(result)));
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			if (bytes == null) throw new IllegalStateException("Frame generation shader compiled to nothing");
			ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
			copy.put(bytes).flip();
			return copy;
		} finally {
			if (result != 0L) shaderc_result_release(result);
			shaderc_compile_options_release(options);
			shaderc_compiler_release(compiler);
		}
	}

	private static void check(int result, String call) {
		if (result != VK_SUCCESS) throw new IllegalStateException(call + " failed: " + result);
	}
}
