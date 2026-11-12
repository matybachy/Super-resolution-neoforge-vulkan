package dev.opalcompat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.opalcompat.OpalCompat;
import dev.opalcompat.compile.SpirvCompiler;
import dev.opalcompat.dh.LodBuffer;
import dev.opalcompat.dh.LodIndices;
import dev.opalcompat.vk.Vk;
import dev.vitrail.pack.model.ProgramStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Distant Horizons' far terrain with no shader pack: its own {@code terrain/gl} program on Vulkan, drawn straight into
 * the main image ahead of VulkanMod's chunks, which then cover it. It has a depth image of its own, because its planes
 * have nothing to do with the world's, as on OpenGL. DH does its fog and its far fade afterwards over the whole
 * picture from the depth it drew; here the program works them out per fragment from the same formulas.
 * <p>
 * Loads no class of DH's: what it reads of DH comes in through {@link Values}.
 */
public final class DistantPlain {

	/** What DH's settings say, written into the program's uniform block in this order. */
	public static final class Values {
		public float worldYOffset;
		public float microOffset;
		public float earthRadius;
		public float clipDistance;
		public boolean dither;
		public boolean whiteWorld;
		public boolean noise;
		public int noiseSteps;
		public float noiseIntensity;
		public int noiseDropoff;
		public boolean fog;
		public float fogRed;
		public float fogGreen;
		public float fogBlue;
		public float fogScale;
		public float fogVerticalScale;
		public int fogFalloff;
		public float farFogStart;
		public float farFogLength;
		public float farFogMin;
		public float farFogRange;
		public float farFogDensity;
		public float heightFogStart;
		public float heightFogLength;
		public float heightFogMin;
		public float heightFogRange;
		public float heightFogDensity;
		public boolean heightFog;
		public int heightFogFalloff;
		public boolean heightFromCamera;
		public float heightFogBase;
		public boolean heightFogUp;
		public boolean heightFogDown;
		public boolean sphericalFog;
		public int heightFogMix;
		public float cameraY;
		public float fadeStart;
		public float fadeEnd;

		private void write(ByteBuffer to, Matrix4fc combined, boolean textured, boolean opaque) {
			combined.get(0, to);
			to.position(64);
			to.putFloat(this.fogRed).putFloat(this.fogGreen).putFloat(this.fogBlue).putFloat(1.0F);
			to.putFloat(this.worldYOffset).putFloat(this.microOffset).putFloat(this.earthRadius).putFloat(this.clipDistance);
			to.putInt(flag(this.dither)).putInt(flag(this.whiteWorld)).putInt(flag(this.noise)).putInt(this.noiseSteps);
			to.putFloat(this.noiseIntensity).putInt(this.noiseDropoff).putInt(flag(textured)).putInt(flag(opaque));
			to.putInt(flag(this.fog)).putFloat(this.fogScale).putFloat(this.fogVerticalScale).putInt(this.fogFalloff);
			to.putFloat(this.farFogStart).putFloat(this.farFogLength).putFloat(this.farFogMin).putFloat(this.farFogRange);
			to.putFloat(this.farFogDensity).putFloat(this.heightFogStart).putFloat(this.heightFogLength).putFloat(this.heightFogMin);
			to.putFloat(this.heightFogRange).putFloat(this.heightFogDensity).putInt(flag(this.heightFog)).putInt(this.heightFogFalloff);
			to.putInt(flag(this.heightFromCamera)).putFloat(this.heightFogBase).putInt(flag(this.heightFogUp)).putInt(flag(this.heightFogDown));
			to.putInt(flag(this.sphericalFog)).putInt(this.heightFogMix).putFloat(this.cameraY).putFloat(this.fadeStart);
			to.putFloat(this.fadeEnd);
		}

		private static int flag(boolean value) {
			return value ? 1 : 0;
		}
	}

	private static final String BLOCK = """
			layout(std140, set = 0, binding = 0) uniform Values {
				mat4 uCombinedMatrix;
				vec4 uFogColor;
				float uWorldYOffset;
				float uMircoOffset;
				float uEarthRadius;
				float uClipDistance;
				int uDitherDhRendering;
				int uIsWhiteWorld;
				int uNoiseEnabled;
				int uNoiseSteps;
				float uNoiseIntensity;
				int uNoiseDropoff;
				int uTextured;
				int uOpaque;
				int uFogEnabled;
				float uFogScale;
				float uFogVerticalScale;
				int uFogFalloffType;
				float uFarFogStart;
				float uFarFogLength;
				float uFarFogMin;
				float uFarFogRange;
				float uFarFogDensity;
				float uHeightFogStart;
				float uHeightFogLength;
				float uHeightFogMin;
				float uHeightFogRange;
				float uHeightFogDensity;
				int uHeightFogEnabled;
				int uHeightFogFalloffType;
				int uHeightBasedOnCamera;
				float uHeightFogBaseHeight;
				int uHeightFogAppliesUp;
				int uHeightFogAppliesDown;
				int uUseSphericalFog;
				int uHeightFogMixingMode;
				float uCameraBlockYPos;
				float uFarFadeStart;
				float uFarFadeEnd;
			};
			""";

	/** The size of {@link #BLOCK}: 80 bytes of matrix and colour, 37 scalars of four, rounded up to a vec4. */
	private static final int BLOCK_BYTES = 240;

	/** DH's {@code terrain/gl/vert.vert}, less its anti-aliasing jitter. */
	private static final String VERTEX = "#version 450\n\n" + BLOCK + """

			layout(set = 0, binding = 1) uniform sampler2D uLightMap;
			layout(push_constant) uniform Section {
				vec3 uModelOffset;
			};

			layout(location = 0) in uvec4 vPosition;
			layout(location = 1) in vec4 color;
			layout(location = 2) in uvec4 irisData;

			layout(location = 0) out vec4 vPos;
			layout(location = 1) out vec4 vertexColor;
			layout(location = 2) out vec3 vertexWorldPos;
			layout(location = 3) out vec3 vBlockPos;
			layout(location = 4) flat out uint vNormalIndex;
			layout(location = 5) flat out uint vTextureTileId;

			void main() {
				vPos = vec4(vPosition);
				vBlockPos = vec3(vPosition.xyz);
				vNormalIndex = irisData.y;
				vTextureTileId = irisData.z | (irisData.w << 8u);

				vertexWorldPos = vec3(vPosition.xyz) + uModelOffset;
				float vertexYPos = float(vPosition.y) + uWorldYOffset;

				uint meta = vPosition.a;
				uint mirco = (meta & 0xFF00u) >> 8u;
				float mx = (mirco & 1u) != 0u ? uMircoOffset : 0.0;
				mx = (mirco & 2u) != 0u ? -mx : mx;
				float mz = (mirco & 16u) != 0u ? uMircoOffset : 0.0;
				mz = (mirco & 32u) != 0u ? -mz : mz;
				vertexWorldPos.x += mx;
				vertexWorldPos.z += mz;

				if (uEarthRadius < -1.0 || uEarthRadius > 1.0) {
					float localRadius = uEarthRadius + vertexYPos;
					float phi = length(vertexWorldPos.xz) / localRadius;
					vertexWorldPos.y += (cos(phi) - 1.0) * localRadius;
					vertexWorldPos.xz = vertexWorldPos.xz * sin(phi) / phi;
				}

				uint lights = meta & 0xFFu;
				float skyLight = (float(lights / 16u) + 0.5) / 16.0;
				float blockLight = (mod(float(lights), 16.0) + 0.5) / 16.0;
				vertexColor = vec4(textureLod(uLightMap, vec2(skyLight, blockLight), 0.0).xyz, 1.0);
				if (uIsWhiteWorld == 0) vertexColor *= color;

				gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);
			}
			""";

	/** DH's {@code terrain/gl/frag.frag}, then what its {@code fog/gl/fog.frag} and {@code fade/gl/dh_fade.frag} do. */
	private static final String FRAGMENT = "#version 450\n\n" + BLOCK + """

			layout(set = 0, binding = 2) uniform sampler2D uBlockAtlas;

			layout(location = 0) in vec4 vPos;
			layout(location = 1) in vec4 vertexColor;
			layout(location = 2) in vec3 vertexWorldPos;
			layout(location = 3) in vec3 vBlockPos;
			layout(location = 4) flat in uint vNormalIndex;
			layout(location = 5) flat in uint vTextureTileId;

			layout(location = 0) out vec4 fragColor;

			float rand(float co) { return fract(sin(co * 91.3458) * 47453.5453); }
			float rand(vec2 co) { return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453); }
			float rand(vec3 co) { return rand(co.xy + rand(co.z)); }

			vec3 quantize(vec3 val, int stepSize) {
				return floor(val * float(stepSize)) / float(stepSize);
			}

			void applyNoise(inout vec4 colour, float viewDist) {
				vec3 across = cross(dFdy(vPos.xyz), dFdx(vPos.xyz));
				vec3 vertexNormal = dot(across, across) > 0.0 ? normalize(across) : vec3(0.0);
				vec3 fixedVPos = vPos.xyz + vertexNormal * 0.001;

				float centred = (colour.r + colour.g + colour.b) / 3.0 * 2.0 - 1.0;
				float noiseAmplification = (1.0 - centred * centred) * uNoiseIntensity * colour.a;

				float randomValue = rand(quantize(fixedVPos, uNoiseSteps)) * 2.0 * noiseAmplification - noiseAmplification;
				vec3 newCol = clamp(colour.rgb + (1.0 - colour.rgb) * randomValue, 0.0, 1.0);

				if (uNoiseDropoff != 0) {
					newCol = mix(newCol, colour.rgb, min(viewDist / float(uNoiseDropoff), 1.0));
				}
				colour.rgb = newCol;
			}

			float bayerMatrix4x4(vec2 st) {
				int x = int(mod(st.x, 4.0));
				int y = int(mod(st.y, 4.0));
				float bayer4x4[16] = float[16](
					0.0, 8.0, 2.0, 10.0,
					12.0, 4.0, 14.0, 6.0,
					3.0, 11.0, 1.0, 9.0,
					15.0, 7.0, 13.0, 5.0
				);
				return bayer4x4[y * 4 + x] / 16.0;
			}

			vec2 blockFaceUv() {
				vec3 pos = fract(vBlockPos);
				switch (vNormalIndex) {
					case 0u: return vec2(pos.x, 1.0 - pos.z);
					case 1u: return vec2(pos.x, pos.z);
					case 2u: return vec2(1.0 - pos.x, 1.0 - pos.y);
					case 3u: return vec2(pos.x, 1.0 - pos.y);
					case 4u: return vec2(pos.z, 1.0 - pos.y);
					default: return vec2(1.0 - pos.z, 1.0 - pos.y);
				}
			}

			float linearFog(float worldDist, float fogStart, float fogLength, float fogMin, float fogRange) {
				return fogMin + fogRange * clamp((worldDist - fogStart) / fogLength, 0.0, 1.0);
			}

			float exponentialFog(float x, float fogStart, float fogLength, float fogMin, float fogRange, float fogDensity) {
				x = max((x - fogStart) / fogLength, 0.0) * fogDensity;
				return fogMin + fogRange - fogRange / exp(x);
			}

			float exponentialSquaredFog(float x, float fogStart, float fogLength, float fogMin, float fogRange, float fogDensity) {
				x = max((x - fogStart) / fogLength, 0.0) * fogDensity;
				return fogMin + fogRange - fogRange / exp(x * x);
			}

			float fogThickness(int falloff, float dist, float start, float len, float low, float range, float density) {
				if (falloff == 0) return linearFog(dist, start, len, low, range);
				if (falloff == 1) return exponentialFog(dist, start, len, low, range, density);
				return exponentialSquaredFog(dist, start, len, low, range, density);
			}

			float heightFogDepth(float worldYPos) {
				if (uHeightFogEnabled == 0) return 0.0;
				if (uHeightBasedOnCamera == 0) worldYPos -= uHeightFogBaseHeight - uCameraBlockYPos;
				if (uHeightFogAppliesDown != 0 && uHeightFogAppliesUp != 0) return abs(worldYPos) * uFogVerticalScale;
				if (uHeightFogAppliesDown != 0) return -worldYPos * uFogVerticalScale;
				if (uHeightFogAppliesUp != 0) return worldYPos * uFogVerticalScale;
				return 0.0;
			}

			float mixFogThickness(float far, float height) {
				switch (uHeightFogMixingMode) {
					case 2: return max(far, height);
					case 3: return far + height;
					case 4: return far * height;
					case 5: return 1.0 - (1.0 - far) * (1.0 - height);
					case 6: return far + max(far, height);
					case 7: return far + far * height;
					case 8: return far + 1.0 - (1.0 - far) * (1.0 - height);
					case 9: return far * 0.5 + height * 0.5;
					default: return far;
				}
			}

			void main() {
				fragColor = vertexColor;

				bool textured = uTextured != 0 && vTextureTileId != 0u;
				if (textured) {
					vec2 tileOrigin = vec2(float(vTextureTileId % 256u), float(vTextureTileId / 256u)) * 16.0;
					vec2 uv = (tileOrigin + blockFaceUv() * 16.0) / vec2(textureSize(uBlockAtlas, 0));
					vec4 tile = texture(uBlockAtlas, uv);
					vec3 clampedColor = clamp(fragColor.rgb * (tile.rgb * 2.0), 0.0, 1.0);
					fragColor.rgb = mix(fragColor.rgb, clampedColor, tile.a);
				}

				float viewDist = length(vertexWorldPos);

				if (uDitherDhRendering != 0) {
					float worldNoise = bayerMatrix4x4(gl_FragCoord.xy) + 0.001;
					if (smoothstep(uClipDistance, uClipDistance * 1.5, viewDist) <= worldNoise) discard;
				} else if (viewDist < uClipDistance && uClipDistance > 0.0) {
					discard;
				}

				if (uNoiseEnabled != 0 && !textured) applyNoise(fragColor, viewDist);

				if (uFogEnabled != 0) {
					float horizontal = length(vertexWorldPos.xz) * uFogScale;
					float spherical = viewDist * uFogScale;
					float far = fogThickness(uFogFalloffType, uUseSphericalFog != 0 ? spherical : horizontal,
						uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity);
					float height = uHeightFogEnabled == 0 ? 0.0 : fogThickness(uHeightFogFalloffType, heightFogDepth(vertexWorldPos.y),
						uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange, uHeightFogDensity);
					fragColor.rgb = mix(fragColor.rgb, uFogColor.rgb, clamp(mixFogThickness(far, height), 0.0, 1.0));
				}

				float fade = 1.0 - smoothstep(uFarFadeStart, uFarFadeEnd, viewDist);
				if (fade <= 0.0) discard;
				fragColor.a = (uOpaque != 0 ? 1.0 : fragColor.a) * fade;
			}
			""";

	private static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
	private static final int STRIDE = LodBuffer.STRIDE;
	private static final int UBO_ALIGNMENT = 256;
	private static final int UBO_PAGE_BYTES = 16 * UBO_ALIGNMENT;
	/** Two a level, and a mod may draw the level more than once a frame. */
	private static final int SETS_PER_FRAME = 64;

	private static DistantPlain instance;
	private static boolean failed;

	private final RenderPasses renderPasses = new RenderPasses();
	private final FrameArena ubo = new FrameArena(UBO_PAGE_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, UBO_ALIGNMENT);
	private final Map<Integer, Long> pools = new HashMap<>();
	private final Map<Long, Long> pipelines = new HashMap<>();
	private final long vertexModule;
	private final long fragmentModule;
	private final long setLayout;
	private final long layout;
	private VulkanImage depth;
	private boolean depthCleared;
	private long pool;
	private long slotSerial = -1L;

	private DistantPlain() {
		try (SpirvCompiler compiler = new SpirvCompiler()) {
			this.vertexModule = module(compiler, "distant_plain.vert", VERTEX, ProgramStage.VERTEX);
			this.fragmentModule = module(compiler, "distant_plain.frag", FRAGMENT, ProgramStage.FRAGMENT);
		}
		this.setLayout = createSetLayout();
		this.layout = createLayout();
		// The swap chain's image views die with it, whatever its size: no framebuffer over one may be met again.
		Renderer.getInstance().addOnResizeCallback(this.renderPasses::forgetFramebuffers);
	}

	/**
	 * One half of the far terrain, from DH's terrain renderer: the opaque one comes first in a level.
	 *
	 * @param atlas    DH's block textures, or null to do without them
	 * @param corners  xyz of each section's corner relative to the camera
	 * @param sections the buffers of each section, near to far as DH lists them
	 * @return whether it was drawn
	 */
	public static boolean draw(Values values, VulkanImage atlas, boolean opaque, float near, float far, float[] corners,
			LodBuffer[][] sections, int count) {
		if (failed || Renderer.skipRendering || !Renderer.isRecording()) return false;
		try {
			if (instance == null) instance = new DistantPlain();
			if (opaque) instance.depthCleared = false;
			if (count == 0) return false;
			instance.half(values, atlas, opaque, near, far, corners, sections, count);
			return true;
		} catch (RuntimeException e) {
			// The world goes on without it: nothing here is worth stopping the game for, or DH's renderer as a whole.
			failed = true;
			OpalCompat.LOGGER.error("[dh] the far terrain with no shader pack failed; it stays undrawn", e);
			return false;
		}
	}

	private void half(Values values, VulkanImage atlas, boolean opaque, float near, float far, float[] corners,
			LodBuffer[][] sections, int count) {
		Renderer renderer = Renderer.getInstance();
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		openSlot();
		long indices = LodIndices.handle();
		try (Renderer.RenderTargetScope scope = renderer.temporaryRenderTarget(); MemoryStack stack = MemoryStack.stackPush()) {
			renderer.endRenderPass(cmd);
			LodBuffer.afterCopies(cmd, stack);
			VulkanImage screen = PackRenderer.screen();
			fitDepth(screen);
			if (!this.depthCleared) {
				PackRenderer.clearDepth(cmd, stack, this.depth);
				this.depthCleared = true;
			}
			long set = writeSet(cmd, stack, values, atlas, opaque, near, far);

			screen.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			this.depth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
			long renderPass = this.renderPasses.renderPass(List.of(screen.format), DEPTH_FORMAT, true);
			long framebuffer = this.renderPasses.framebuffer(renderPass,
					new long[] {screen.getImageView(), this.depth.getImageView()}, screen.width, screen.height);
			long pipeline = this.pipelines.computeIfAbsent(renderPass, this::createPipeline);

			// The main image's way up, which VulkanMod's own viewport gives it.
			PackRenderer.beginPass(cmd, stack, renderPass, framebuffer, screen.width, screen.height, true);
			try {
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
				vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.layout, 0, stack.longs(set), null);
				vkCmdBindIndexBuffer(cmd, indices, 0, VK_INDEX_TYPE_UINT32);
				FloatBuffer corner = stack.mallocFloat(4);
				LongBuffer vertexBuffer = stack.mallocLong(1);
				LongBuffer vertexOffset = stack.mallocLong(1);
				for (int s = 0; s < count; s++) {
					corner.put(0, corners[s * 3]).put(1, corners[s * 3 + 1]).put(2, corners[s * 3 + 2]).put(3, 0.0F);
					vkCmdPushConstants(cmd, this.layout, VK_SHADER_STAGE_VERTEX_BIT, 0, corner);
					for (LodBuffer buffer : sections[s]) {
						if (buffer == null || !buffer.ready()) continue;
						vertexBuffer.put(0, buffer.handle());
						vertexOffset.put(0, buffer.offset());
						vkCmdBindVertexBuffers(cmd, 0, vertexBuffer, vertexOffset);
						vkCmdDrawIndexed(cmd, buffer.indices(), 1, 0, 0, 0);
					}
				}
			} finally {
				vkCmdEndRenderPass(cmd);
			}
			// VulkanMod's chunks go on drawing into this image.
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
					.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
					.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
					0, barrier, null, null);
		}
	}

	/** What this frame slot's last frame used is free again, once per frame: a level drawn twice shares the slot. */
	private void openSlot() {
		long serial = PackRenderer.frameSerial();
		if (this.slotSerial == serial) return;
		this.slotSerial = serial;
		int frame = Renderer.getCurrentFrame();
		this.ubo.begin(frame);
		this.pool = this.pools.computeIfAbsent(frame, unused -> createPool());
		vkResetDescriptorPool(Vk.device(), this.pool, 0);
	}

	private void fitDepth(VulkanImage screen) {
		if (this.depth != null && this.depth.width == screen.width && this.depth.height == screen.height) return;
		dev.opalcompat.vk.QueueLock.deviceWaitIdle();
		this.renderPasses.forgetFramebuffers();
		if (this.depth != null) this.depth.free();
		int usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
				| VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		this.depth = VulkanImage.createDepthImage(DEPTH_FORMAT, screen.width, screen.height, usage, false, true);
	}

	private long writeSet(VkCommandBuffer cmd, MemoryStack stack, Values values, VulkanImage atlas, boolean opaque, float near, float far) {
		FrameArena.Slice slice = this.ubo.allocate(BLOCK_BYTES);
		values.write(slice.bytes(), combined(near, far), atlas != null, opaque);

		VulkanImage lightmap = lightmap();
		VulkanImage tiles = atlas != null ? atlas : VTextureSelector.getWhiteTexture();
		lightmap.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		tiles.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

		VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
				.descriptorPool(this.pool).pSetLayouts(stack.longs(this.setLayout));
		LongBuffer handle = stack.mallocLong(1);
		Vk.check(vkAllocateDescriptorSets(Vk.device(), allocate, handle), "vkAllocateDescriptorSets");
		long set = handle.get(0);

		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
		writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
				.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(slice.buffer()).offset(slice.offset()).range(BLOCK_BYTES));
		writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
				.pImageInfo(imageInfo(stack, lightmap));
		writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
				.pImageInfo(imageInfo(stack, tiles));
		vkUpdateDescriptorSets(Vk.device(), writes, null);
		return set;
	}

	private static VkDescriptorImageInfo.Buffer imageInfo(MemoryStack stack, VulkanImage image) {
		return VkDescriptorImageInfo.calloc(1, stack).imageView(image.getImageView()).sampler(image.getSampler())
				.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	/**
	 * The frame's camera with DH's planes, depth from 0 to 1. The planes go into the camera's own projection and the walk
	 * bob on top of that: written into the bobbed matrix they would not be the planes, and the far terrain would not
	 * sway with the chunks standing in front of it.
	 */
	private static Matrix4f combined(float near, float far) {
		Matrix4f matrix = new Matrix4f(LevelCapture.cleanProjection());
		matrix.m02(0.0F);
		matrix.m12(0.0F);
		matrix.m22(far / (near - far));
		matrix.m32(far * near / (near - far));
		return matrix.mul(LevelCapture.bob()).mul(LevelCapture.modelView());
	}

	/** The game's own light map, the same picture DH keeps a copy of on OpenGL. Asked for as {@code PackRenderer} asks. */
	private static VulkanImage lightmap() {
		LightTexture light = Minecraft.getInstance().gameRenderer.lightTexture();
		light.turnOnLightLayer();
		VulkanImage image = PackRenderer.glImage(RenderSystem.getShaderTexture(2));
		light.turnOffLightLayer();
		return image != null ? image : VTextureSelector.getWhiteTexture();
	}

	private static long module(SpirvCompiler compiler, String name, String source, ProgramStage stage) {
		SpirvCompiler.Result result = compiler.compile(name, source, stage);
		if (!result.ok()) throw new IllegalStateException(name + " does not compile: " + result.diagnostics());
		ByteBuffer code = MemoryUtil.memAlloc(result.spirv().length);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			code.put(result.spirv()).flip();
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateShaderModule(Vk.device(), info, null, handle), "vkCreateShaderModule");
			return handle.get(0);
		} finally {
			MemoryUtil.memFree(code);
		}
	}

	private static long createSetLayout() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
			bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
			bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
			bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
			VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateDescriptorSetLayout(Vk.device(), info, null, handle), "vkCreateDescriptorSetLayout");
			return handle.get(0);
		}
	}

	private long createLayout() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(this.setLayout))
					// The section's corner, a vec3.
					.pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(16));
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreatePipelineLayout(Vk.device(), info, null, handle), "vkCreatePipelineLayout");
			return handle.get(0);
		}
	}

	private static long createPool() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SETS_PER_FRAME);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_FRAME * 2);
			VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(SETS_PER_FRAME).pPoolSizes(sizes);
			LongBuffer handle = stack.mallocLong(1);
			Vk.check(vkCreateDescriptorPool(Vk.device(), info, null, handle), "vkCreateDescriptorPool");
			return handle.get(0);
		}
	}

	/**
	 * Both halves draw alike, as on OpenGL: depth tested and written, the back culled. Blending is on for the opaque
	 * half as well, for the far fade, whose alpha is all it ever gets there.
	 */
	private long createPipeline(long renderPass) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(this.vertexModule).pName(stack.UTF8("main"));
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(this.fragmentModule).pName(stack.UTF8("main"));

			VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
			binding.get(0).binding(0).stride(STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
			VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(3, stack);
			attributes.get(0).location(0).binding(0).format(VK_FORMAT_R16G16B16A16_UINT).offset(0);
			attributes.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(8);
			attributes.get(2).location(2).binding(0).format(VK_FORMAT_R8G8B8A8_UINT).offset(12);
			VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
					.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes);

			VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
					.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
			VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
					.viewportCount(1).scissorCount(1);
			VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
					// The viewport is flipped, so GL's counter-clockwise is still counter-clockwise here.
					.polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_BACK_BIT).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
					.lineWidth(1.0F);
			VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
					.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
			VkPipelineDepthStencilStateCreateInfo depthState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
					.depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blend.get(0).colorWriteMask(0xF).blendEnable(true)
					.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
					.colorBlendOp(VK_BLEND_OP_ADD)
					.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
					.alphaBlendOp(VK_BLEND_OP_ADD);
			VkPipelineColorBlendStateCreateInfo colourBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
					.pAttachments(blend);
			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					// VulkanMod Reforged's own dynamic states as well, see PackPipeline.
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
							VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_LINE_WIDTH, VK_DYNAMIC_STATE_BLEND_CONSTANTS,
							VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK, VK_DYNAMIC_STATE_STENCIL_WRITE_MASK,
							VK_DYNAMIC_STATE_STENCIL_REFERENCE));

			VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
					.pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(assembly)
					.pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample)
					.pDepthStencilState(depthState).pColorBlendState(colourBlend).pDynamicState(dynamic)
					.layout(this.layout).renderPass(renderPass).subpass(0);
			LongBuffer handle = stack.mallocLong(1);
			long started = System.nanoTime();
			Vk.check(vkCreateGraphicsPipelines(Vk.device(), PipelineCache.handle(), info, null, handle), "vkCreateGraphicsPipelines distant_plain");
			PipelineCache.created(started);
			return handle.get(0);
		}
	}
}
