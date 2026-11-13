package dev.matybachy.srvmr.render;

import dev.matybachy.srvmr.SrvmrConfig;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
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
 * Motion vectors and a depth for DLSS, worked out from the depth of the world, the camera and the entities.
 * <p>
 * Every pixel is put back where it was in the frame DLSS last upscaled. Its depth makes a point of this frame's clip
 * space; {@link DlssFrame#inverseCurrent} makes that a position relative to the camera; if the position lies in the box
 * of an entity that moved ({@link EntityMotion}) it is moved back by as much; {@link DlssFrame#previous} takes it to the
 * last frame's clip space. The difference to where the pixel is now, without jitter, is its motion in render pixels,
 * pointing back.
 * <p>
 * The depth DLSS gets is written here as well, lying the way the picture does and running from near to far whatever
 * the depth image holds. The hand moves with the camera, so its motion is nought: with a shader pack it is the band of
 * depth Opal Compat squeezes it into, as Iris does; without one it is whatever the game drew over the cleared depth.
 */
final class MotionPass implements AutoCloseable {

	/** Bytes of the push constants: a vec4, two ivec4 and a vec4. */
	private static final int PUSH = 64;
	/** Bytes of a frame's buffer: two matrices and the camera's move, then the entity boxes. */
	private static final int HEADER = 144;
	private static final int FRAME_BYTES = align(HEADER + EntityMotion.MAX * EntityMotion.FLOATS * Float.BYTES, 256);

	/** Hand modes: none, the game's own hand over a cleared depth, a shader pack's band of depth. */
	static final int HAND_NONE = 0;
	static final int HAND_CLEARED = 1;
	static final int HAND_BAND = 2;

	private static final String SOURCE = """
			#version 450
			layout(local_size_x = 8, local_size_y = 8) in;
			layout(binding = 0) uniform sampler2D worldDepth;
			layout(binding = 1, rgba16f) uniform writeonly image2D motionOut;
			layout(binding = 2, r32f) uniform writeonly image2D depthOut;
			layout(std430, binding = 3) readonly buffer Frame {
			    mat4 inverseCurrent;   // this frame's clip space, jittered, to positions relative to the camera
			    mat4 previous;         // those positions to the last upscaled frame's clip space, without jitter
			    vec4 cameraMove;       // this frame's camera minus the last upscaled frame's (xyz), previousPlace holds that frame (w)
			    vec4 boxes[];          // per moved entity: min, max, move
			} frame;
			layout(binding = 4) uniform sampler2D handDepth;
			layout(binding = 5) uniform sampler2D distantDepth;
			layout(binding = 6, rgba32f) uniform writeonly image2D placeOut;
			layout(binding = 7, rgba16f) uniform writeonly image2D moveOut;
			layout(binding = 8, rgba32f) uniform readonly image2D previousPlace;
			layout(push_constant) uniform Push {
			    vec4 jitterHandSign;   // jitter in clip space (xy), hand depth band (z), sign of motion y (w)
			    ivec4 sizeFlipMode;    // size (xy), depth images upside down (z), depth mode (w)
			    ivec4 handCount;       // hand mode (x), entity boxes (y), Distant Horizons' depth there (z)
			    vec4 planes;           // the z row of DH's projection (xy) and of the game's (zw)
			} pc;

			// How far what the last upscaled frame saw, where this point (relative to this frame's camera) would have been
			// on its screen, lies from the point: near nought if the last frame saw this very point there.
			float missed(vec3 point, ivec2 size) {
			    vec4 clip = frame.previous * vec4(point, 1.0);
			    if (clip.w <= 1e-6) return 1e30;
			    vec2 ndc = clip.xy / clip.w;
			    vec2 uv = vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
			    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return 1e30;
			    vec4 was = imageLoad(previousPlace, min(ivec2(uv * vec2(size)), size - 1));
			    if (abs(was.w - 1.0) > 0.5) return 1e30;
			    return distance(was.xyz, point + frame.cameraMove.xyz);
			}

			// Whether a point in a moved entity's box is the entity's and not what stands around it: the box is grown for
			// the model sticking out of the hit box, and holds grass, walls and ground beside the entity as well. The last
			// frame saw the entity's points where they were before the move, and what stands still where it is now.
			bool followsEntity(vec3 position, vec3 move, ivec2 size) {
			    if (frame.cameraMove.w < 0.5) return false;
			    float withEntity = missed(position - move, size);
			    float inPlace = missed(position, size);
			    float tolerance = max(0.15, 0.02 * length(position));
			    return withEntity < tolerance && withEntity < 0.5 * inPlace;
			}

			void main() {
			    ivec2 size = pc.sizeFlipMode.xy;
			    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
			    if (pixel.x >= size.x || pixel.y >= size.y) return;
			    ivec2 source = pc.sizeFlipMode.z != 0 ? ivec2(pixel.x, size.y - 1 - pixel.y) : pixel;
			    float d = texelFetch(worldDepth, source, 0).r;
			    int mode = pc.sizeFlipMode.w;
			    float z = mode == 0 ? d * 2.0 - 1.0 : (mode == 1 ? d : 1.0 - d * 2.0);
			    float nearToFar = mode == 2 ? 1.0 - d : d;
			    // Nothing of the world here: Distant Horizons' terrain may be, drawn with its own planes. Its depth gives
			    // the distance along the view, and the game's z row turns that into the game's clip depth.
			    float distantView = 0.0;
			    if (pc.handCount.z != 0 && nearToFar >= 1.0) {
			        float far = texelFetch(distantDepth, source, 0).r;
			        if (far < 1.0) {
			            float view = -pc.planes.y / ((far * 2.0 - 1.0) + pc.planes.x);
			            z = (pc.planes.z * view + pc.planes.w) / -view;
			            nearToFar = clamp(z * 0.5 + 0.5, 0.0, 1.0);
			            distantView = view;
			        }
			    }
			    bool hand = false;
			    if (pc.handCount.x == 1) {
			        hand = texelFetch(handDepth, source, 0).r < 1.0;
			    } else if (pc.handCount.x == 2) {
			        hand = nearToFar < pc.jitterHandSign.z;
			    }
			    // DLSS's depth. Without Distant Horizons, the game's own. With it, the far terrain lies past the game's far plane
			    // and its depth above was clamped to 1.0, the sky's: DLSS saw one depth for all the far terrain and the sky, and
			    // could not tell one far hill from the next or from the sky when deciding which history to throw away. Every
			    // pixel's distance goes instead into one projection with the game's near plane and DH's far plane.
			    float dlssDepth = nearToFar;
			    if (pc.handCount.z != 0) {
			        float nearPlane = pc.planes.w / (pc.planes.z - 1.0);
			        float farPlane = pc.planes.y / (pc.planes.x + 1.0);
			        float view = distantView < 0.0 ? distantView
			                : (nearToFar < 1.0 ? -pc.planes.w / (z + pc.planes.z) : 0.0);
			        dlssDepth = view < 0.0 && farPlane > nearPlane
			                ? clamp(farPlane / (farPlane - nearPlane) * (1.0 + nearPlane / view), 0.0, 1.0) : 1.0;
			    }
			    imageStore(depthOut, pixel, vec4(hand ? 0.0 : dlssDepth, 0.0, 0.0, 0.0));
			    vec2 motion = vec2(0.0);
			    // For frame generation: the point of this pixel relative to this frame's camera (w 1), the direction of the
			    // sky (w 0), or the hand, which keeps its place on the screen (w 2); and how far its entity moved since the
			    // last upscaled frame.
			    vec4 place = vec4(0.0, 0.0, 0.0, 2.0);
			    vec3 moved = vec3(0.0);
			    if (!hand) {
			        vec2 ndc = vec2((float(pixel.x) + 0.5) / float(size.x) * 2.0 - 1.0,
			                        1.0 - (float(pixel.y) + 0.5) / float(size.y) * 2.0);
			        bool sky = nearToFar >= 1.0 && distantView >= 0.0;
			        vec4 point = frame.inverseCurrent * vec4(ndc, z, 1.0);
			        vec4 before = frame.previous * point;
			        if (sky) {
			            vec4 farPoint = frame.inverseCurrent * vec4(ndc, 1.0, 1.0);
			            vec3 direction = abs(farPoint.w) > 1e-7 ? farPoint.xyz / farPoint.w : vec3(0.0);
			            if (dot(direction, direction) > 1e-12) place = vec4(normalize(direction), 0.0);
			        }
			        if (abs(point.w) > 1e-7) {
			            vec3 position = point.xyz / point.w;
			            if (!sky) place = vec4(position, 1.0);
			            for (int i = 0; i < pc.handCount.y; i++) {
			                if (all(greaterThanEqual(position, frame.boxes[i * 3].xyz))
			                        && all(lessThanEqual(position, frame.boxes[i * 3 + 1].xyz))) {
			                    vec3 move = frame.boxes[i * 3 + 2].xyz;
			                    // DLSS takes the whole box with the entity, as before: it only steers its history. A
			                    // generated frame shows what it moves, so it moves only what the last frame confirms.
			                    before = frame.previous * vec4(position - move, 1.0);
			                    if (followsEntity(position, move, size)) moved = move;
			                    break;
			                }
			            }
			        }
			        if (abs(before.w) > 1e-7) {
			            vec2 delta = before.xy / before.w - (ndc - pc.jitterHandSign.xy);
			            motion = vec2(delta.x * 0.5 * float(size.x), -delta.y * 0.5 * float(size.y) * pc.jitterHandSign.w);
			        }
			    }
			    imageStore(motionOut, pixel, vec4(motion, 0.0, 0.0));
			    imageStore(placeOut, pixel, place);
			    imageStore(moveOut, pixel, vec4(moved, 0.0));
			}
			""";

	private final VkDevice device;
	private final long sampler;
	private final long setLayout;
	private final long pipelineLayout;
	private final long pipeline;
	private final long pool;
	private final long[] sets;
	private final long buffer;
	private final long memory;
	private final ByteBuffer mapped;

	MotionPass() {
		this.device = Vulkan.getVkDevice();
		int frames = Math.max(4, Renderer.getFramesNum());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);

			VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
					.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0.0F);
			check(vkCreateSampler(this.device, samplerInfo, null, handle), "vkCreateSampler");
			this.sampler = handle.get(0);

			int[] types = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
					VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
					VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
					VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
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

			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3 * frames);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(5 * frames);
			sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(frames);
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

			// One host-visible buffer, a slice of it per frame slot, written while that slot's last frame is done.
			long size = (long) FRAME_BYTES * frames;
			check(vkCreateBuffer(this.device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
					.usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "vkCreateBuffer");
			this.buffer = handle.get(0);
			VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(this.device, this.buffer, requirements);
			int type = memoryType(requirements.memoryTypeBits(),
					VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
			check(vkAllocateMemory(this.device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
					.allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "vkAllocateMemory");
			this.memory = handle.get(0);
			check(vkBindBufferMemory(this.device, this.buffer, this.memory, 0L), "vkBindBufferMemory");
			PointerBuffer pointer = stack.mallocPointer(1);
			check(vkMapMemory(this.device, this.memory, 0L, size, 0, pointer), "vkMapMemory");
			this.mapped = MemoryUtil.memByteBuffer(pointer.get(0), (int) size);
		}
	}

	/** Whether this pass still has a set for every frame VulkanMod Reforged keeps in flight. */
	boolean fits() {
		return Renderer.getFramesNum() <= this.sets.length;
	}

	/**
	 * Records the pass. The depth sources must be in {@code SHADER_READ_ONLY_OPTIMAL}, the two outputs in
	 * {@code GENERAL}. The set and the buffer slice of this frame's slot are rewritten: VulkanMod Reforged has waited for
	 * that slot's last frame.
	 */
	void record(MemoryStack stack, VkCommandBuffer cmd, VulkanImage worldDepth, VulkanImage handDepth, int handMode,
			OpalDepth.Distant distant, boolean upsideDown, SrvmrConfig.DepthMode mode, float handBand, VulkanImage motion,
			VulkanImage depth, VulkanImage place, VulkanImage previousPlace, boolean previousValid, VulkanImage moved,
			float jitterNdcX, float jitterNdcY, boolean invertMotionY) {
		int slot = Renderer.getCurrentFrame() % this.sets.length;
		long set = this.sets[slot];
		int base = slot * FRAME_BYTES;

		DlssFrame.inverseCurrent(new Matrix4f()).get(base, this.mapped);
		DlssFrame.previous(new Matrix4f()).get(base + 64, this.mapped);
		float[] cameraMove = DlssFrame.cameraMove();
		this.mapped.putFloat(base + 128, cameraMove[0]).putFloat(base + 132, cameraMove[1])
				.putFloat(base + 136, cameraMove[2]).putFloat(base + 140, previousValid ? 1.0F : 0.0F);
		int boxes = DlssFrame.entityCount();
		for (int i = 0; i < boxes * EntityMotion.FLOATS; i++) {
			this.mapped.putFloat(base + HEADER + i * Float.BYTES, EntityMotion.BOXES[i]);
		}

		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(9, stack);
		writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(this.sampler)
						.imageView(worldDepth.getImageView()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
		writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.imageView(motion.getImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.imageView(depth.getImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
						.buffer(this.buffer).offset(base).range(FRAME_BYTES));
		writes.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(this.sampler)
						.imageView(handDepth.getImageView()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
		VulkanImage far = distant != null ? distant.depth() : worldDepth;
		writes.get(5).sType$Default().dstSet(set).dstBinding(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(this.sampler)
						.imageView(far.getImageView()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
		writes.get(6).sType$Default().dstSet(set).dstBinding(6).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.imageView(place.getImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		writes.get(7).sType$Default().dstSet(set).dstBinding(7).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.imageView(moved.getImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		writes.get(8).sType$Default().dstSet(set).dstBinding(8).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(1).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.imageView(previousPlace.getImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		vkUpdateDescriptorSets(this.device, writes, null);

		ByteBuffer push = stack.calloc(PUSH);
		push.putFloat(0, jitterNdcX).putFloat(4, jitterNdcY).putFloat(8, handBand).putFloat(12, invertMotionY ? -1.0F : 1.0F);
		push.putInt(16, motion.width).putInt(20, motion.height).putInt(24, upsideDown ? 1 : 0).putInt(28, mode.ordinal());
		push.putInt(32, handMode).putInt(36, boxes).putInt(40, distant != null ? 1 : 0);
		if (distant != null) {
			push.putFloat(48, distant.dhM22()).putFloat(52, distant.dhM32()).putFloat(56, distant.gameM22())
					.putFloat(60, distant.gameM32());
		}

		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
		vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, stack.longs(set), null);
		vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
		vkCmdDispatch(cmd, (motion.width + 7) / 8, (motion.height + 7) / 8, 1);
	}

	/** Call with the device idle. */
	@Override
	public void close() {
		vkUnmapMemory(this.device, this.memory);
		vkDestroyBuffer(this.device, this.buffer, null);
		vkFreeMemory(this.device, this.memory, null);
		vkDestroyDescriptorPool(this.device, this.pool, null);
		vkDestroyPipeline(this.device, this.pipeline, null);
		vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
		vkDestroyDescriptorSetLayout(this.device, this.setLayout, null);
		vkDestroySampler(this.device, this.sampler, null);
	}

	private static int memoryType(int allowed, int wanted) {
		VkPhysicalDeviceMemoryProperties properties = DeviceManager.memoryProperties;
		for (int i = 0; i < properties.memoryTypeCount(); i++) {
			if ((allowed & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & wanted) == wanted) return i;
		}
		throw new IllegalStateException("No host-visible memory for the motion pass");
	}

	private static int align(int value, int alignment) {
		return (value + alignment - 1) / alignment * alignment;
	}

	private static ByteBuffer compile() {
		long compiler = shaderc_compiler_initialize();
		long options = shaderc_compile_options_initialize();
		long result = 0L;
		try {
			shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
			result = shaderc_compile_into_spv(compiler, SOURCE, shaderc_compute_shader, "srvmr_motion.comp", "main", options);
			if (result == 0L || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				throw new IllegalStateException("Motion vector shader does not compile: "
						+ (result == 0L ? "no result" : shaderc_result_get_error_message(result)));
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			if (bytes == null) throw new IllegalStateException("Motion vector shader compiled to nothing");
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
